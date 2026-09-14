package tw.springfestival.domain

class WorldRules(val events: List<StoryEvent>) {
    init {
        require(events.map { it.id }.distinct().size == events.size)
        val knownFlags = events.flatMap { it.branches.flatMap { b -> b.flags } }.toSet()
        events.forEach { e ->
            require(
                e.category in setOf(
                    "main",
                    "character",
                    "life",
                    "memory",
                    "atmosphere"
                )
            ) { "事件類別不正確：${e.id}" }
            require(e.participants.all { it in setOf("Elia", "Miro") }) { "事件角色引用不正確：${e.id}" }
            require(e.chapter == null || e.chapter in 0..5)
            require(e.minChapter in 0..6 && e.cooldown >= 0 && e.priority in 0..200)
            require(knownFlags.containsAll(e.require + e.exclude + e.branches.flatMap { it.require })) { "旗標引用不正確：${e.id}" }
            require(e.branches.all { it.character == null || it.character in setOf("Elia", "Miro") })

            require(e.branches.size in 2..4 && e.branches.map { it.id }.distinct().size == e.branches.size)
            require(e.hooks.all { hook -> events.any { it.id == hook } })
            require(e.branches.all { it.skill == null || it.skill in setOf("cooking", "observation", "folkMagic") })
        }
    }

    fun eligible(w: World) = events.filter { e ->
        e.place == w.place && (e.chapter == null || e.chapter == w.chapter) && w.chapter >= e.minChapter &&
                w.flags.containsAll(e.require) && e.exclude.none { it in w.flags } &&
                (!e.once || e.id !in w.completed) && (e.id !in w.completed || w.tick - w.completed.getValue(e.id) >= e.cooldown) &&
                e.branches.any { w.flags.containsAll(it.require) }
    }.sortedWith(compareByDescending<StoryEvent> {
        it.priority + if (it.id !in w.completed) 5 else (w.tick - w.completed.getValue(it.id)).coerceAtMost(30)
    }
        .thenBy { (w.seed xor it.id.hashCode().toLong()) and Long.MAX_VALUE })

    fun event(w: World) = eligible(w).firstOrNull()
    fun suggestions(w: World): List<Suggestion> {
        val selected = event(w)
        val choices = selected?.branches?.filter { w.flags.containsAll(it.require) }
            ?.map { Suggestion("event:${selected.id}:${it.id}", it.label) }.orEmpty().toMutableList()
        if (w.chapter == 5 && w.place == Place.DOCK && selected?.id != "departure") {
            choices += Suggestion("event:departure:postpone", "一起延期，約好下次再出發")
        }
        if (selected?.id == "festival" && "allies" !in w.flags) {
            val helper = eligible(w).firstOrNull { e -> e.branches.any { "allies" in it.flags } }
            helper?.branches?.firstOrNull { "allies" in it.flags }
                ?.let { branch -> choices += Suggestion("event:${helper.id}:${branch.id}", branch.label) }
        }
        if (choices.size < 2) choices += Suggestion("chat", "聊聊今天與共同經歷")
        if (choices.size < 2) choices += Suggestion("rest", "休息，讓故事慢慢走")
        return choices.take(4)
    }

    fun scene(w: World, mode: String): Scene {
        val e = event(w)
        return Scene(
            w.id,
            w.name,
            w.revision,
            w.day(),
            w.period(),
            w.weather(),
            w.place,
            w.place.label,
            (w.npcPlaces().filterValues { it == w.place }.keys + e?.participants.orEmpty()).distinct(),
            w.chapter,
            e?.title,
            (e?.text?.let { text -> text + if (e.id == "festival" && w.skills.values.any { it.size >= 2 }) " 先前累積的生活練習，讓你辨認細節時更有把握。" else "" })
                ?: "${w.place.label}的風帶著河水的氣味。你可以停留，也可以去別處看看。",
            suggestions(w),
            buildList {
                if ("promise" in w.flags) add("與 Elia 約定春祭後一起去北方"); if ("bridgeClosed" in w.flags) add(
                "北橋已封閉"
            ); if ("truth" in w.flags) add(WORLD_TRUTH); if ("ticket" in w.flags) add("已取得渡船船票"); if ("festivalDone" in w.flags) add(
                "春祭已完成"
            )
            },
            w.ending,
            w.relationships.mapValues { it.value.description() },
            w.skills.mapValues { w.skillLevel(it.key) },
            mode
        )
    }

    fun resolve(
        w: World,
        intent: PlayerIntent,
        plans: List<NpcPlan>,
        turnId: String,
        originalText: String? = null
    ): ValidatedOutcome {
        fun reject(message: String) = ValidatedOutcome(w, false, message)
        val action = intent.actionId ?: return reject(intent.clarification ?: "你想做哪一件事？請選擇下方行動。")
        if (plans.any { it.sourceRevision != w.revision }) throw Conflict("角色計畫已過期，請重新讀取場景。")
        if (action == "chat" || action == "topic") {
            val query = originalText.orEmpty()
            val speakers = scene(w, "offline").characters
            val knownBy = (speakers + "player").toSet()
            val candidate =
                intent.memoryCandidate?.takeIf { it == originalText && it.startsWith("我喜歡") && it.length in 4..100 }
            val working = Memory(
                "$turnId:working",
                MemoryLayer.WORKING,
                originalText ?: "一起聊了今天。",
                turnId,
                knownBy,
                updatedRevision = w.revision + 1
            )
            val semantic = candidate?.let {
                Memory(
                    "$turnId:preference",
                    MemoryLayer.SEMANTIC,
                    it,
                    turnId,
                    knownBy,
                    importance = 3,
                    updatedRevision = w.revision + 1
                )
            }
            val retained =
                w.memories.filter { it.layer != MemoryLayer.WORKING } + w.memories.filter { it.layer == MemoryLayer.WORKING }
                    .takeLast(7)
            val next = w.copy(
                revision = w.revision + 1,
                memories = (retained + listOfNotNull(
                    working,
                    semantic
                )).let { all -> all.filter { it.pinned } + all.filterNot { it.pinned }.takeLast(100) })
            val reply = candidate?.let { "好，我會記得你說的：$it。之後想改變也可以告訴我。" }
                ?: if (MemoryRecall.isRecallQuestion(query)) {
                    // Only facts known to everyone speaking can become a shared author response.
                    val relevant = w.visibleMemories("player", query).filter { it.knownBy.containsAll(speakers) }
                    if (relevant.isEmpty()) "目前沒有找到能回答這個問題的共同記憶，可以再告訴我一次嗎？"
                    else relevant.joinToString("\n") { "記錄：${it.effectiveText()}（來源：${it.sourceId}）" }
                } else "你們停下來聊聊此刻，沒有推進行程，也沒有增加新的承諾。"
            return ValidatedOutcome(
                next,
                true,
                reply,
                memoryIds = listOfNotNull(working.id, semantic?.id),
                speakers = speakers
            )
        }
        if (action == "rest" || action.startsWith("move:")) {
            val place =
                if (action == "rest") w.place else runCatching { Place.valueOf(action.substringAfter(':')) }.getOrNull()
                    ?: return reject("找不到這個地點。")
            if (place == Place.LIGHTHOUSE && w.ending != "departed") return reject("北方星燈台仍隔著河。先完成春祭，再安排交通；也可以一起延期。")
            val next = advance(w.copy(place = place, revision = w.revision + 1), plans)
            return ValidatedOutcome(
                next,
                true,
                if (action == "rest") "你讓肩膀放鬆，休息了一個時段。沒有人催促你。" else "你沿著河畔走到${place.label}。",
                "routine"
            )
        }
        if (suggestions(w).none { it.id == action }) return reject("這個行動目前不可用，請重新讀取合法建議。")
        val e = eligible(w).firstOrNull { action.startsWith("event:${it.id}:") }
            ?: return reject("這裡的事件已經改變，請從目前建議行動重新選擇。")
        val b = e.branches.firstOrNull { "event:${e.id}:${it.id}" == action }
            ?: return reject("這個行動目前不可用。請依照場景中的建議繼續。")
        if (!w.flags.containsAll(b.require)) return reject("條件尚未成立，先完成場景中的合法下一步。")
        val memory = b.memory?.let {
            Memory(
                "$turnId:memory",
                b.layer,
                it,
                e.id,
                importance = if (b.pinned) 5 else 2,
                pinned = b.pinned,
                knownBy = (e.participants + "player").toSet(),
                updatedRevision = w.revision + 1
            )
        }
        val relations = w.relationships.toMutableMap()
        b.character?.let { npc ->
            val old = relations.getValue(npc); relations[npc] = old.copy(
            trust = (old.trust + b.trust).coerceIn(0, 10),
            closeness = (old.closeness + b.closeness).coerceIn(0, 10),
            mood = "期待"
        )
        }
        val skills = w.skills.toMutableMap()
        b.skill?.let { skills[it] = skills[it].orEmpty() + e.id }
        val memories = (w.memories + listOfNotNull(memory)).let { all ->
            all.filter { it.pinned } + all.filterNot { it.pinned }.takeLast(100)
        }
        val branchText = b.text + when {
            e.id == "departure" && "joke" in w.flags -> " Elia 小聲補了一句：這次的早餐可別又加了河風調味。"
            e.id == "departure" && "soup" in w.flags -> " 你想起第一晚的熱湯；Elia 說，以後也會留一碗給你。"
            e.category == "character" && b.character != null && relations.getValue(b.character).trust >= 5 -> " 因為你們已經一起處理過不少事情，這回對話少了一點客氣，多了一點放心。"
            else -> ""
        }
        val next = w.copy(
            revision = w.revision + 1,
            flags = w.flags + b.flags,
            completed = if (b.id == "listen") w.completed else w.completed + (e.id to w.tick),
            chapter = w.chapter + if (e.advanceChapter) 1 else 0,
            relationships = relations,
            skills = skills,
            memories = memories,
            ending = b.ending ?: w.ending,
            place = if (b.ending == "departed") Place.LIGHTHOUSE else w.place,
            journal = w.journal + JournalEntry(e.id, e.title, branchText, w.revision + 1)
        )
        return ValidatedOutcome(advance(next, plans), true, branchText, e.id, listOfNotNull(memory?.id), e.participants)
    }

    private fun advance(w: World, plans: List<NpcPlan>): World {
        // NPC 只提交自己的資訊活動；路徑中的玩家行動絕不自動執行。
        val legal = setOf(
            "check_bridge",
            "ask_ferry",
            "suggest_delay",
            "read_archive",
            "ask_witness",
            "inspect_lamp",
            "ask_helpers",
            "prepare_tools"
        )
        return w.copy(tick = w.tick + 1, npcActivities = plans.groupBy { it.npc }.mapNotNull { (npc, p) ->
            p.firstNotNullOfOrNull {
                it.nextStep?.takeIf { a ->
                    a in legal && when (a) {
                        "check_bridge" -> "bridgeClosed" !in w.flags
                        "ask_ferry" -> w.weather() != "細雨"
                        "read_archive" -> w.tick % 3 != 2
                        "ask_witness" -> w.tick % 3 == 2
                        "prepare_tools" -> "truth" in w.flags
                        else -> true
                    }
                }
            }?.let { npc to it }
        }.toMap())
    }
}

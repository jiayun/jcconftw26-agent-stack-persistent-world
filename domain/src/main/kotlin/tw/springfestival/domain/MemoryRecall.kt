package tw.springfestival.domain

/** Shared by the game, character tools and MCP. No model infers facts during retrieval. */
object MemoryRecall {
    private val vocabulary = linkedMapOf(
        "drink" to listOf(
            "飲料",
            "飲品",
            "喝",
            "茶",
            "咖啡",
            "果汁",
            "可可",
            "牛奶",
            "豆漿",
            "無糖",
            "不加糖",
            "甜度",
            "熱湯",
            "drin",
            "tea",
            "coffee"
        ),
        "food" to listOf("食物", "吃", "料理", "早餐", "晚餐", "口味", "麵包", "food", "eat"),
        "travel" to listOf(
            "旅行",
            "北方",
            "出發",
            "去哪",
            "哪裡",
            "哪裏",
            "約定",
            "說好",
            "答應",
            "承諾",
            "延期",
            "船",
            "travel",
            "promise"
        ),
        "festival" to listOf("星燈", "修燈", "配置", "負載", "祭典", "春祭", "燈架", "festival"),
        "joke" to listOf("玩笑", "笑話", "河風調味", "joke"),
        "letter" to listOf("信", "老闆", "值班", "letter"),
    )

    fun topics(text: String): Set<String> =
        vocabulary.filterValues { words -> words.any { text.contains(it, true) } }.keys

    fun preferenceTopic(memory: Memory): String? = if (memory.layer == MemoryLayer.SEMANTIC)
        topics(memory.effectiveText()).firstOrNull { it in setOf("drink", "food") } else null

    fun isRecallQuestion(text: String) = listOf(
        "記得",
        "記不記",
        "之前",
        "上次",
        "說過",
        "說好",
        "答應",
        "約定",
        "偏好",
        "喜歡什麼",
        "remember"
    ).any { text.contains(it, true) }

    private fun terms(text: String): Set<String> = Regex("[\\p{IsHan}]{2,}|[a-zA-Z]{3,}").findAll(text.lowercase())
        .flatMap { m -> if (m.value.any { it.code > 127 }) m.value.windowed(2).asSequence() else sequenceOf(m.value) }
        .filterNot {
            it in setOf(
                "記得",
                "還記",
                "得我",
                "我喜",
                "喜歡",
                "歡喝",
                "什麼",
                "嗎呢",
                "我們",
                "之前",
                "你們",
                "現在",
                "說過",
                "the",
                "you"
            )
        }.toSet()

    fun select(memories: List<Memory>, viewer: String, query: String = "", limit: Int = 6): List<Memory> {
        require(limit in 0..20 && query.length <= 2000)
        // Working messages may contain questions, claims or obsolete preferences, not established facts.
        val eligible =
            memories.withIndex().filter { viewer in it.value.knownBy && it.value.layer != MemoryLayer.WORKING }
                .sortedWith(compareByDescending<IndexedValue<Memory>> { it.value.updatedRevision }.thenByDescending { it.index })
        val seen = mutableSetOf<String>()
        val current = eligible.filter { item ->
            val key = preferenceTopic(item.value)?.let { "preference:$it" } ?: "id:${item.value.id}"
            seen.add(key)
        }
        val queryTopics = topics(query)
        val queryTerms = terms(query)
        val preferenceQuestion = query.contains("喜歡") || query.contains("偏好") || query.contains("口味")
        fun relevance(m: Memory): Int {
            if (query.isBlank()) return 0
            val text = m.effectiveText()
            val topicMatches = topics(text).intersect(queryTopics).size
            val termMatches = terms(text).intersect(queryTerms).size
            return (if (text.contains(query, true)) 100 else 0) + topicMatches * 20 + termMatches * 2 +
                    (if (preferenceQuestion && m.layer == MemoryLayer.SEMANTIC && (topicMatches > 0 || queryTopics.isEmpty())) 30 else 0)
        }
        return current.filter { query.isBlank() || relevance(it.value) > 0 }
            .sortedWith(compareByDescending<IndexedValue<Memory>> { relevance(it.value) }
                .thenByDescending { it.value.updatedRevision }.thenByDescending { it.value.importance }
                .thenByDescending { it.index })
            .take(limit)
            .map { item -> item.value.let { if (it.correction != null) it.copy(text = it.effectiveText()) else it } }
    }
}

fun Memory.effectiveText(): String = correction ?: text

/** Corrections/deletions also remove older copies of the same preference, so they cannot reappear. */
fun World.changeMemory(memoryId: String, replacement: String?): World {
    require(replacement == null || replacement.isNotBlank() && replacement.length <= 2000)
    val target =
        memories.firstOrNull { it.id == memoryId && "player" in it.knownBy } ?: throw Missing("找不到這筆可見記憶。")
    val topic = MemoryRecall.preferenceTopic(target)
    val remaining = memories.filterNot {
        it.id == target.id ||
                (topic != null && "player" in it.knownBy && MemoryRecall.preferenceTopic(it) == topic)
    }
    val changed = replacement?.let { target.copy(correction = it, updatedRevision = revision + 1) }
    return copy(revision = revision + 1, memories = remaining + listOfNotNull(changed))
}

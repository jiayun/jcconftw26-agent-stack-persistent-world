package tw.springfestival.application

import tw.springfestival.domain.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

data class TurnRecord(val id: String, val saveId: String, val command: TurnCommand, val status: String,
    val entry: String, val outcome: ValidatedOutcome? = null, val result: TurnResult? = null, val error: String? = null)
data class TurnReceipt(val turnId: String, val status: String)
interface WorldStore {
    fun list(): List<World>
    fun load(id: String): World
    fun create(world: World): World
    fun accept(saveId: String, command: TurnCommand, entry: String): TurnRecord
    fun latest(saveId: String): TurnRecord?
    fun turn(saveId: String, turnId: String): TurnRecord
    fun commit(record: TurnRecord, outcome: ValidatedOutcome, plans: List<NpcPlan>): ValidatedOutcome
    fun finish(record: TurnRecord, result: TurnResult)
    fun fail(record: TurnRecord, category: String)
    fun pending(): List<TurnRecord>
    fun editMemory(id: String, expectedRevision: Long, memoryId: String, text: String?): World
}
class GameService(val store: WorldStore, val rules: WorldRules, val model: StoryModel, private val workflow: TurnWorkflow) : AutoCloseable {
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun create(name: String, demo: Boolean = false, seed: Long = 26): World {
        require(name.isNotBlank() && name.length <= 80) { "存檔名稱須為 1 到 80 字。" }
        var w = World(UUID.randomUUID().toString(), name, seed)
        if (demo) {
            w = w.copy(chapter = 3, tick = 4, place = Place.BRIDGE, flags = setOf("tea", "joke", "promise", "anomaly"),
                completed = mapOf("arrival" to 0, "promise" to 1, "anomaly" to 2, "elia_recipe" to 3),
                relationships = mapOf("Elia" to Relationship(trust = 4, closeness = 3), "Miro" to Relationship(trust = 1)),
                skills = mapOf("cooking" to setOf("elia_recipe"), "observation" to setOf("anomaly"), "folkMagic" to emptySet()),
                memories = listOf(Memory("demo-promise", MemoryLayer.RELATIONSHIP, "你與 Elia 約定春祭結束後一起去北方。", "promise", importance = 5, pinned = true),
                    Memory("demo-tea", MemoryLayer.SEMANTIC, "你喜歡無糖茶。", "arrival"),
                    Memory("demo-joke", MemoryLayer.RELATIONSHIP, "你與 Elia 把廚房的小失誤叫作河風調味。", "elia_recipe")),
                journal = listOf(JournalEntry("promise", "範例存檔的共同經歷", "已認識 Elia、約好北方旅行，也發現星燈異常；尚未取得船票。", 0)))
        }
        return store.create(w)
    }
    fun scene(id: String) = rules.scene(store.load(id), model.mode)
    fun submit(id: String, command: TurnCommand, entry: String): TurnReceipt {
        command.validate()
        val record = store.accept(id, command, entry)
        schedule(record)
        return TurnReceipt(record.id, record.status)
    }
    fun recover() { store.pending().forEach(::schedule) }
    private fun schedule(record: TurnRecord) {
        if (record.status in setOf("COMPLETE", "FAILED") || !running.add(record.id)) return
        workers.submit {
            try {
                val current = store.turn(record.saveId, record.id)
                val w = current.outcome?.world ?: store.load(record.saveId)
                if (current.outcome == null && w.revision != record.command.expectedRevision) throw Conflict("場景版本已改變，請重新讀取後再選擇行動。")
                val result = workflow.execute(TurnWork(record.id, w, record.command, current.outcome)) { outcome, plans -> store.commit(record, outcome, plans) }
                store.finish(record, result)
            } catch (_: Conflict) { store.fail(record, "REVISION_CONFLICT：場景已更新，請重新選擇行動。") }
              catch (_: Exception) { store.fail(record, "PROCESSING_FAILED：回合無法完成；已提交的世界會保留，可在重新啟動後補回演出。") }
            finally { running.remove(record.id) }
        }
    }
    fun result(id: String, turnId: String, debug: Boolean = false): TurnRecord {
        val r = store.turn(id, turnId)
        return r.copy(result = r.result?.let { if (debug) it else it.copy(trace = null) }, outcome = null)
    }
    fun import(world: World): World {
        require(world.schemaVersion == 1 && world.contentVersion == CONTENT_VERSION) { "存檔版本不相容，原檔未修改。" }
        require(world.name.isNotBlank() && world.name.length <= 80 && world.revision >= 0 && world.tick in 0..1_000_000 && world.chapter in 0..6)
        require(world.memories.size <= 150 && world.memories.all { it.text.length <= 2000 && it.sourceId.isNotBlank() })
        require(world.completed.keys.all { id -> rules.events.any { it.id == id } })
        require(world.skills.keys == setOf("cooking", "observation", "folkMagic"))
        require(world.skills.values.flatten().all { id -> rules.events.any { it.id == id } })
        require(world.relationships.keys == setOf("Elia", "Miro") && world.relationships.values.all { it.trust in 0..10 && it.closeness in 0..10 && it.tension in 0..10 })
        require(world.ending in setOf(null, "departed", "postponed"))
        require(world.chapter < 6 || world.ending != null)
        require(world.ending == null || "festivalDone" in world.flags)
        require(world.ending != "departed" || "ticket" in world.flags)
        return store.create(world.copy(id = UUID.randomUUID().toString(), name = world.name.take(76) + "（匯入）"))
    }
    override fun close() { workers.close() }
}

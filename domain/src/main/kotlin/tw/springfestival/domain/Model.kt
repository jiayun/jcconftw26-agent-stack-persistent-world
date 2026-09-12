package tw.springfestival.domain

const val CONTENT_VERSION = "spring-festival-1"
const val WORLD_TRUTH = "舊星燈設施與今年祭典配置不相容"
enum class Place(val label: String) { INN("旅館"), MARKET("市集"), TEAHOUSE("茶書屋"), BRIDGE("舊橋"), DOCK("渡口"), LIGHTHOUSE("北方星燈台") }
enum class MemoryLayer { WORKING, EPISODIC, SEMANTIC, RELATIONSHIP, STORY }
data class Memory(val id: String, val layer: MemoryLayer, val text: String, val sourceId: String,
    val knownBy: Set<String> = setOf("player", "Elia", "Miro"), val importance: Int = 1, val pinned: Boolean = false,
    val correction: String? = null, val updatedRevision: Long = 0)
data class Relationship(val trust: Int = 0, val closeness: Int = 0, val tension: Int = 0, val mood: String = "平靜") {
    fun description() = when { tension > 3 -> "願意聽你說，但有些拘謹"; closeness >= 5 -> "把你當成可以一起商量未來的人"; trust >= 3 -> "開始放心把心事交給你"; else -> "正在慢慢認識你" }
}
data class World(
    val id: String, val name: String, val seed: Long, val schemaVersion: Int = 1, val contentVersion: String = CONTENT_VERSION,
    val revision: Long = 0, val tick: Int = 0, val place: Place = Place.INN, val chapter: Int = 0,
    val flags: Set<String> = emptySet(), val completed: Map<String, Int> = emptyMap(),
    val relationships: Map<String, Relationship> = mapOf("Elia" to Relationship(), "Miro" to Relationship()),
    val skills: Map<String, Set<String>> = mapOf("cooking" to emptySet(), "observation" to emptySet(), "folkMagic" to emptySet()),
    val memories: List<Memory> = emptyList(), val journal: List<JournalEntry> = emptyList(),
    val npcActivities: Map<String, String> = emptyMap(), val ending: String? = null
) {
    fun day() = tick / 3 + 1
    fun period() = listOf("早晨", "午後", "夜晚")[tick % 3]
    fun weather() = if ((tick / 3 + seed).mod(4) == 2) "細雨" else "晴朗"
    fun npcPlaces(): Map<String, Place> = mapOf(
        "Elia" to listOf(Place.INN, Place.MARKET, Place.BRIDGE)[tick % 3],
        "Miro" to listOf(Place.TEAHOUSE, Place.BRIDGE, Place.MARKET)[tick % 3])
    fun skillLevel(skill: String) = when (skills[skill].orEmpty().size) { 0, 1 -> "初識"; 2, 3 -> "熟悉"; else -> "拿手" }
    fun visibleMemories(viewer: String, query: String = "", limit: Int = 6) =
        MemoryRecall.select(memories, viewer, query, limit)
}
data class JournalEntry(val eventId: String, val title: String, val text: String, val revision: Long)
data class Branch(val id: String, val label: String, val text: String, val require: Set<String> = emptySet(),
    val flags: Set<String> = emptySet(), val skill: String? = null, val character: String? = null,
    val trust: Int = 0, val closeness: Int = 0, val memory: String? = null, val layer: MemoryLayer = MemoryLayer.EPISODIC,
    val pinned: Boolean = false, val ending: String? = null)
data class StoryEvent(val id: String, val category: String, val title: String, val place: Place,
    val text: String, val chapter: Int? = null, val minChapter: Int = 0, val require: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(), val priority: Int = 10, val cooldown: Int = 3, val once: Boolean = true,
    val advanceChapter: Boolean = false, val lockedFacts: List<String> = emptyList(),
    val generationScope: String = "只可補充動作、表情與對話，不可新增世界事實。", val hooks: List<String> = emptyList(),
    val participants: List<String> = emptyList(), val branches: List<Branch>)
data class Suggestion(val id: String, val label: String)
data class Scene(val saveId: String, val name: String, val revision: Long, val day: Int, val period: String,
    val weather: String, val place: Place, val placeName: String, val characters: List<String>, val chapter: Int,
    val eventTitle: String?, val description: String, val suggestions: List<Suggestion>, val facts: List<String>,
    val ending: String?, val relationships: Map<String, String>, val skills: Map<String, String>, val mode: String)
data class TurnCommand(val requestId: String, val expectedRevision: Long, val text: String? = null, val suggestionId: String? = null) {
    fun validate() {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "requestId 格式不正確。" }
        require(expectedRevision >= 0) { "revision 不可為負數。" }
        require((text != null) xor (suggestionId != null)) { "text 與 suggestionId 請擇一提供。" }
        require(text == null || (text.isNotBlank() && text.length <= 2000)) { "文字須介於 1 到 2000 字。" }
        require(suggestionId == null || suggestionId.length in 1..150) { "建議行動格式不正確。" }
    }
}
data class PlayerIntent(val actionId: String? = null, val clarification: String? = null, val memoryCandidate: String? = null)
data class NpcPlan(val npc: String, val goal: String, val sourceRevision: Long, val conditions: Map<String, Boolean>, val actionIds: List<String>, val nextStep: String?)
data class ValidatedOutcome(val world: World, val accepted: Boolean, val text: String, val eventId: String? = null, val memoryIds: List<String> = emptyList(), val speakers: List<String> = emptyList())
data class NarrativeDraft(val text: String)
data class ExecutionTrace(val nodes: List<String>, val plans: List<NpcPlan>, val memorySources: List<String>, val elapsedMs: Long, val modelRequests: Int = 0, val toolCalls: Int = 0, val tokens: Long = 0, val fallback: Boolean = false, val stateDiff: Map<String, String> = emptyMap(), val recovered: Boolean = false)
data class TurnResult(val turnId: String, val revision: Long, val accepted: Boolean, val narrative: String, val scene: Scene, val memoryIds: List<String>, val trace: ExecutionTrace? = null)
class Conflict(message: String) : RuntimeException(message)
class Missing(message: String) : RuntimeException(message)

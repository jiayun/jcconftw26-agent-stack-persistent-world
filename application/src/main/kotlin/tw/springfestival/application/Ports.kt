package tw.springfestival.application

import tw.springfestival.domain.*

interface NpcPlanner {
    fun plan(world: World): List<NpcPlan>
}

interface StoryModel {
    val mode: String
    fun understand(command: TurnCommand, scene: Scene, budget: TurnBudget): PlayerIntent
    fun narrate(
        outcome: ValidatedOutcome,
        character: String,
        memories: List<Memory>,
        budget: TurnBudget,
        context: NarrationContext = NarrationContext()
    ): NarrativeDraft
}

class TurnBudget(val started: Long = System.nanoTime()) {
    var requests = 0; private set
    var tools = 0; private set
    var tokens = 0L; private set
    fun remainingMs() = 45000L - (System.nanoTime() - started) / 1_000_000
    @Synchronized
    fun request() {
        check(remainingMs() > 0 && requests < 6) { "本回合模型預算已用完。" }; requests++
    }

    @Synchronized
    fun tool() {
        check(remainingMs() > 0 && tools < 3) { "本回合查詢預算已用完。" }; tools++
    }

    @Synchronized
    fun recordTokens(count: Long) {
        tokens += count
    }
}

data class TurnWork(
    val turnId: String,
    val world: World,
    val command: TurnCommand,
    val committed: ValidatedOutcome? = null
)

interface TurnWorkflow {
    fun execute(work: TurnWork, commit: (ValidatedOutcome, List<NpcPlan>) -> ValidatedOutcome): TurnResult
}

/** Original question stays separate from committed facts, including during crash recovery. */
data class NarrationContext(
    val playerText: String? = null,
    val eventTitle: String? = null,
    val lockedFacts: List<String> = emptyList(),
    val generationScope: String = "僅補充當下動作、表情與對話，不新增世界事實。",
    val chosenAction: String? = null
)

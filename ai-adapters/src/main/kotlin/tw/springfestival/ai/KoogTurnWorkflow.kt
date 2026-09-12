package tw.springfestival.ai

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.*
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import tw.springfestival.application.*
import tw.springfestival.domain.*

// Koog 只執行自訂節點；所有 LLM 請求集中到 Spring AI。
private class NoDirectModelExecutor : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("模型呼叫必須經 Spring AI")
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("不使用 token streaming")
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("模型呼叫必須經 Spring AI")
    override fun close() {}
}
class KoogTurnWorkflow(private val rules: WorldRules, private val model: StoryModel, private val planner: NpcPlanner) : TurnWorkflow {
    override fun execute(work: TurnWork, commit: (ValidatedOutcome, List<NpcPlan>) -> ValidatedOutcome): TurnResult = runBlocking {
        val budget = TurnBudget()
        val nodes = mutableListOf<String>()
        var memories = emptyList<Memory>()
        var plans = emptyList<NpcPlan>()
        var intent = PlayerIntent()
        var outcome = work.committed
        var narrative = ""
        var fallback = false
        val graph = strategy<String, String>("persistent-player-turn") {
            val context by node<String, String>("read-world-and-knowledge") { input ->
                nodes += "read-world-and-knowledge"; memories = work.world.visibleMemories("player", work.command.text.orEmpty()); input
            }
            val understand by node<String, String>("understand-or-clarify") { input ->
                nodes += "understand-or-clarify"
                if (outcome == null) intent = try { model.understand(work.command, rules.scene(work.world, model.mode), budget) }
                    catch (_: Exception) { PlayerIntent(clarification = "目前無法理解這句話，請使用建議行動再試一次。") }
                input
            }
            val plan by node<String, String>("embabel-planning") { input ->
                nodes += "embabel-planning"; plans = planner.plan(work.world); input
            }
            val judge by node<String, String>("domain-validation") { input ->
                nodes += "domain-validation"; outcome = rules.resolve(work.world, intent, plans, work.turnId, work.command.text); input
            }
            val clarify by node<String, String>("clarification") { input ->
                nodes += "clarification"; outcome = ValidatedOutcome(work.world, false, intent.clarification ?: "請選擇一個明確行動。"); input
            }
            val persist by node<String, String>("transaction-commit") { input ->
                nodes += "transaction-commit"; outcome = commit(outcome!!, plans); input
            }
            val perform by node<String, String>("spring-ai-performance") { input ->
                nodes += "spring-ai-performance"
                val saved = outcome!!
                val present = saved.speakers
                narrative = try {
                    if (present.isEmpty()) saved.text else if (model.mode == "offline") model.narrate(saved, present.first(), saved.world.visibleMemories(present.first()), budget).text
                    else present.joinToString("\n\n") { npc -> "$npc：${model.narrate(saved, npc, saved.world.visibleMemories(npc), budget).text}" }
                } catch (_: Exception) { fallback = true; saved.text }
                input
            }
            val fallbackNode by node<String, String>("author-fallback") { input -> nodes += "author-fallback"; narrative = outcome!!.text; input }
            val finish by node<String, String>("persist-result") { input -> nodes += "persist-result"; input }
            edge(nodeStart forwardTo context)
            edge(context forwardTo perform onCondition { work.committed != null })
            edge(context forwardTo understand onCondition { work.committed == null })
            edge(understand forwardTo plan onCondition { intent.actionId != null })
            edge(understand forwardTo clarify onCondition { intent.actionId == null })
            edge(plan forwardTo judge); edge(judge forwardTo persist); edge(clarify forwardTo persist)
            edge(persist forwardTo perform)
            edge(perform forwardTo fallbackNode onCondition { fallback })
            edge(perform forwardTo finish onCondition { !fallback })
            edge(fallbackNode forwardTo finish); edge(finish forwardTo nodeFinish)
        }
        val agent = GraphAIAgent(NoDirectModelExecutor(), AIAgentConfig.withSystemPrompt("自訂節點執行世界回合", maxAgentIterations = 20), graph)
        try { agent.run(work.turnId) } finally { agent.close() }
        val saved = outcome!!
        TurnResult(work.turnId, saved.world.revision, saved.accepted, narrative, rules.scene(saved.world, model.mode), saved.memoryIds,
            ExecutionTrace(nodes, plans, memories.map { it.sourceId }, (System.nanoTime() - budget.started) / 1_000_000, budget.requests, budget.tools, budget.tokens, fallback, mapOf(
                "revision" to "${work.world.revision} → ${saved.world.revision}",
                "tick" to "${work.world.tick} → ${saved.world.tick}",
                "place" to "${work.world.place} → ${saved.world.place}",
                "flagsAdded" to (saved.world.flags - work.world.flags).joinToString(),
                "memories" to "${work.world.memories.size} → ${saved.world.memories.size}",
                "skills" to saved.world.skills.toString(),
                "relationships" to saved.world.relationships.toString()
            ), work.committed != null))
    }
}

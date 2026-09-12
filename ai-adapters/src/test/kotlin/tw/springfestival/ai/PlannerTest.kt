package tw.springfestival.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tw.springfestival.domain.*
import tw.springfestival.application.*

class PlannerTest {
    private val planner = EmbabelNpcPlanner()
    private val w = World("test", "規劃測試", 0)
    @Test fun `旅行路徑依橋路渡船及天氣實際改變`() {
        assertEquals(listOf("check_bridge"), planner.planGoal(w, "Elia", "travel").actionIds)
        assertEquals(listOf("ask_ferry", "propose_ferry"), planner.planGoal(w.copy(flags = setOf("bridgeClosed")), "Elia", "travel").actionIds)
        assertEquals(listOf("suggest_delay"), planner.planGoal(w.copy(flags = setOf("bridgeClosed"), tick = 6), "Elia", "travel").actionIds)
    }
    @Test fun `調查依開館時間選擇真正 planner action path`() {
        assertEquals(listOf("read_archive", "compare_diagrams"), planner.planGoal(w, "Miro", "investigate").actionIds)
        assertEquals(listOf("ask_witness", "inspect_lamp"), planner.planGoal(w.copy(tick = 2), "Miro", "investigate").actionIds)
    }
    @Test fun `春祭依知識與協作條件改變路徑`() {
        assertEquals(listOf("ask_helpers", "propose_cooperation"), planner.planGoal(w, "Miro", "festival").actionIds)
        assertEquals(listOf("prepare_tools", "propose_repair"), planner.planGoal(w.copy(flags = setOf("truth")), "Miro", "festival").actionIds)
        assertEquals(listOf("propose_cooperation"), planner.planGoal(w.copy(flags = setOf("allies")), "Miro", "festival").actionIds)
    }
    @Test fun `私人記憶不進入其他角色條件`() {
        val privateWorld = w.copy(memories = listOf(Memory("secret", MemoryLayer.SEMANTIC, "Elia 的私人秘密", "private", setOf("Elia"))))
        assertEquals(planner.plan(w), planner.plan(privateWorld))
        assertTrue(privateWorld.visibleMemories("Miro").isEmpty())
    }
    @Test fun `Koog 澄清分支不裁決世界`() {
        val rules = WorldRules(emptyList())
        val failingModel = object : StoryModel {
            override val mode = "live"
            override fun understand(command: TurnCommand, scene: Scene, budget: TurnBudget) = PlayerIntent(clarification = "請選一件事。")
            override fun narrate(outcome: ValidatedOutcome, character: String, memories: List<Memory>, budget: TurnBudget): NarrativeDraft = error("模型斷線")
        }
        var commits = 0
        val result = KoogTurnWorkflow(rules, failingModel, planner).execute(TurnWork("turn", w, TurnCommand("request", 0, text = "也許吧"))) { outcome, _ -> commits++; outcome }
        assertFalse(result.accepted); assertEquals(0, result.revision); assertEquals(1, commits)
        assertTrue(result.trace!!.nodes.containsAll(listOf("clarification")))
        assertFalse("domain-validation" in result.trace!!.nodes)
    }
    @Test fun `提交後恢復只補敘事`() {
        val rules = WorldRules(emptyList())
        val outcome = ValidatedOutcome(w.copy(revision = 1), true, "已到旅館")
        val result = KoogTurnWorkflow(rules, SpringStoryModel(null, "offline"), planner).execute(TurnWork("saved", w, TurnCommand("request", 0, suggestionId = "rest"), outcome)) { _, _ -> error("不應重新提交") }
        assertEquals("已到旅館", result.narrative)
        assertFalse("transaction-commit" in result.trace!!.nodes)
    }

    @Test fun `演出失敗走作者備援並保留已提交結果`() {
        val model = object : StoryModel {
            override val mode = "live"
            override fun understand(command: TurnCommand, scene: Scene, budget: TurnBudget) = error("不應重新理解")
            override fun narrate(outcome: ValidatedOutcome, character: String, memories: List<Memory>, budget: TurnBudget): NarrativeDraft = error("模型逾時")
        }
        val outcome = ValidatedOutcome(w.copy(revision = 1), true, "已保存的事件", speakers = listOf("Elia"))
        val result = KoogTurnWorkflow(WorldRules(emptyList()), model, planner).execute(TurnWork("fallback", w, TurnCommand("req", 0, suggestionId = "rest"), outcome)) { _, _ -> error("不可重複提交") }
        assertEquals("已保存的事件", result.narrative)
        assertTrue("author-fallback" in result.trace!!.nodes)
    }
    @Test fun `多角色共用最多六次模型及三次工具預算`() {
        val b = TurnBudget(); repeat(6) { b.request() }; assertThrows(IllegalStateException::class.java) { b.request() }
        repeat(3) { b.tool() }; assertThrows(IllegalStateException::class.java) { b.tool() }
    }
}

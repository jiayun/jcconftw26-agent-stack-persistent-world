package tw.springfestival.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import tw.springfestival.application.TurnBudget
import tw.springfestival.application.TurnWork
import tw.springfestival.domain.*

class AdviceIntentTest {
    private class Replies(private vararg val replies: String) : ChatModel {
        val prompts = mutableListOf<String>()
        override fun getOptions() = ToolCallingChatOptions.builder().build()
        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt.instructions.joinToString("\n") { it.text.orEmpty() }
            return ChatResponse(listOf(Generation(AssistantMessage(replies[(prompts.size - 1).coerceAtMost(replies.lastIndex)]))))
        }
    }

    private val rules = WorldRules(emptyList())
    private val world = World("advice", "詢問意見", 26)
    private fun model(fake: Replies) = SpringStoryModel(ChatClient.create(fake), "live")
    private fun run(
        fake: Replies,
        command: TurnCommand = TurnCommand("req", 0, text = "Elia 你說怎麼辦？")
    ): TurnResult =
        KoogTurnWorkflow(rules, model(fake), EmbabelNpcPlanner()).execute(
            TurnWork(
                "turn",
                world,
                command
            )
        ) { outcome, _ -> outcome }

    @Test
    fun `未知角色動作與字串 null 會重試成聊天且不推進世界`() {
        for (invalid in listOf("ask_Elia", "event:missing:advice", "null", "move:UNKNOWN")) {
            val fake = Replies(
                """{"actionId":"$invalid"}""",
                """{"actionId":"chat"}""",
                """{"text":"可以先聊聊你最擔心的事，再一起商量。"}"""
            )
            val result = run(fake)
            assertTrue(result.accepted, invalid)
            assertEquals(world.revision + 1, result.revision)
            assertEquals(world.place, result.scene.place)
            assertEquals(world.day(), result.scene.day)
            assertEquals(world.period(), result.scene.period)
            assertEquals("0 → 0", result.trace!!.stateDiff["tick"])
            assertEquals("", result.trace!!.stateDiff["flagsAdded"])
            assertTrue(fake.prompts[1].contains("上一輪意圖格式或行動不合法"))
            assertTrue(fake.prompts.last().contains("本回合玩家原文：Elia 你說怎麼辦？"))
            assertFalse(result.narrative.contains("行動目前不可用"))
        }
    }

    @Test
    fun `持續無效的意圖自然澄清且不改動世界`() {
        for (invalid in listOf(
            """{"actionId":"Elia"}""",
            """{}""",
            """{"actionId":"rest","clarification":"要休息嗎？"}"""
        )) {
            val fake = Replies(invalid)
            val result = run(fake)
            assertFalse(result.accepted)
            assertEquals(rules.scene(world, "live"), result.scene)
            assertTrue(result.memoryIds.isEmpty())
            assertTrue(result.narrative.contains("你想先聽聽角色的看法"))
            assertEquals(2, fake.prompts.size)
        }
    }

    @Test
    fun `模糊指令可以回傳澄清而不自動執行`() {
        val fake = Replies("""{"actionId":null,"clarification":"你想讓我幫你考慮哪件事？"}""")
        val result = run(fake, TurnCommand("req", 0, text = "你替我決定吧。"))
        assertFalse(result.accepted)
        assertEquals(rules.scene(world, "live"), result.scene)
        assertEquals("你想讓我幫你考慮哪件事？", result.narrative)
        assertEquals(1, fake.prompts.size)
    }

    @Test
    fun `合法事件與明確移動不會被一律降為聊天`() {
        val fake = Replies("""{"actionId":"event:choice:go"}""", """{"actionId":"move:DOCK"}""")
        val model = model(fake)
        val scene = rules.scene(world, "live").copy(suggestions = listOf(Suggestion("event:choice:go", "前去調查")))
        assertEquals(
            "event:choice:go",
            model.understand(TurnCommand("one", 0, text = "我們去調查吧。"), scene, TurnBudget()).actionId
        )
        assertEquals(
            "move:DOCK",
            model.understand(TurnCommand("two", 0, text = "去渡口吧。"), scene, TurnBudget()).actionId
        )
    }

    @Test
    fun `移動條件與過期按鈕仍由世界拒絕`() {
        val fake = Replies("""{"actionId":"move:LIGHTHOUSE"}""")
        val result = run(fake, TurnCommand("req", 0, text = "去北方星燈台吧。"))
        assertFalse(result.accepted)
        assertEquals(rules.scene(world, "live"), result.scene)
        assertTrue(result.narrative.contains("仍隔著河"))
        val stale = run(Replies("不可呼叫模型"), TurnCommand("req", 0, suggestionId = "event:old:choice"))
        assertFalse(stale.accepted)
        assertEquals(rules.scene(world, "live"), stale.scene)
    }
}

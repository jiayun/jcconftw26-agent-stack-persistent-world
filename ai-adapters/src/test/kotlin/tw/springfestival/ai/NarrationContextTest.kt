package tw.springfestival.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.*
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import tw.springfestival.application.*
import tw.springfestival.domain.*

class NarrationContextTest {
    private class RecordingModel(private val replies: List<String>) : ChatModel {
        val prompts = mutableListOf<String>()
        override fun getOptions() = ToolCallingChatOptions.builder().build()
        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt.instructions.joinToString("\n") { it.text.orEmpty() }
            return ChatResponse(listOf(Generation(AssistantMessage(replies[(prompts.size - 1).coerceAtMost(replies.lastIndex)]))))
        }
    }
    @Test fun `模型收到原始問題有效偏好與來源但不含舊副本或秘密`() {
        val fake = RecordingModel(listOf("""{"text":"你喜歡黑咖啡，我記著呢。"}"""))
        val memory = listOf(Memory("tea", MemoryLayer.SEMANTIC, "我喜歡無糖茶。", "tea"),
            Memory("coffee", MemoryLayer.SEMANTIC, "我喜歡無糖茶。", "updated", correction = "我喜歡黑咖啡。", updatedRevision = 2),
            Memory("secret", MemoryLayer.SEMANTIC, "秘密果汁配方", "private", knownBy = setOf("Miro")))
        SpringStoryModel(ChatClient.create(fake), "live").narrate(ValidatedOutcome(World("test", "test", 26), true, "回想飲料偏好"),
            "Elia", memory, TurnBudget(), NarrationContext("還記得我喜歡喝什麼嗎？"))
        val prompt = fake.prompts.single()
        assertTrue(prompt.contains("本回合玩家原文：還記得我喜歡喝什麼嗎？"))
        assertTrue(prompt.contains("我喜歡黑咖啡。")); assertTrue(prompt.contains("來源=updated"))
        assertFalse(prompt.contains("我喜歡無糖茶。")); assertFalse(prompt.contains("秘密果汁配方"))
    }
    @Test fun `威脅語氣會使用同一預算修復`() {
        val fake = RecordingModel(listOf("""{"text":"把你丟進河裡。"}""", """{"text":"無糖茶，記住了。我把糖罐移遠一點。"}"""))
        val budget = TurnBudget()
        val draft = SpringStoryModel(ChatClient.create(fake), "live").narrate(ValidatedOutcome(World("test", "test", 26), true, "偏好已保存"),
            "Elia", emptyList(), budget, NarrationContext("我喜歡無糖茶。"))
        assertFalse(draft.text.contains("丟進")); assertEquals(2, budget.requests)
        assertTrue(fake.prompts.last().contains("上一輪未通過"))
    }
    @Test fun `已出發事件保留作者事實且恢復時仍傳遞原始問題`() {
        val fake = RecordingModel(listOf("""{"text":"到了北方，今天可以慢慢看看。"}"""))
        val world = World("test", "test", 26, revision = 1, place = Place.LIGHTHOUSE, ending = "departed")
        val outcome = ValidatedOutcome(world, true, "渡船離岸，你們抵達北方星燈台。", "departure", speakers = listOf("Elia"))
        val model = SpringStoryModel(ChatClient.create(fake), "live")
        val result = KoogTurnWorkflow(WorldRules(emptyList()), model, EmbabelNpcPlanner()).execute(
            TurnWork("recovered", world, TurnCommand("req", 0, text = "我們出發吧。"), outcome)) { _, _ -> error("不可重新提交") }
        assertTrue(result.narrative.startsWith(outcome.text))
        assertTrue(fake.prompts.single().contains("本回合玩家原文：我們出發吧。"))
        assertTrue(fake.prompts.single().contains("現在位置：北方星燈台"))
        assertTrue(result.trace!!.recovered)
    }
    @Test fun `自由聊天提示允許聊天但模糊指令仍要澄清`() {
        val fake = RecordingModel(listOf("""{"actionId":"chat"}"""))
        val intent = SpringStoryModel(ChatClient.create(fake), "live").understand(TurnCommand("req",0,text="先聊聊你今天的心情吧。"),
            WorldRules(emptyList()).scene(World("test","test",26),"live"), TurnBudget())
        assertEquals("chat",intent.actionId)
        assertTrue(fake.prompts.single().contains("詢問心情"))
        assertTrue(fake.prompts.single().contains("你替我決定"))
    }
    @Test fun `回想不能改談北方或捏造已準備好的飲品`() {
        val fake = RecordingModel(listOf("""{"text":"我記得你喜歡無糖茶，我剛好有準備。"}""", """{"text":"無糖茶，我記得。口味改變時也可以再告訴我。"}"""))
        val budget = TurnBudget()
        val draft = SpringStoryModel(ChatClient.create(fake), "live").narrate(
            ValidatedOutcome(World("test", "test", 26), true, "你喜歡無糖茶。"), "Elia",
            listOf(Memory("tea",MemoryLayer.SEMANTIC,"你喜歡無糖茶。","tea")), budget, NarrationContext("還記得我喝什麼嗎？"))
        assertEquals(2, budget.requests)
        assertFalse(draft.text.contains("準備"))
        assertTrue(draft.text.contains("無糖茶"))
    }

}

package tw.springfestival.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.*
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import tw.springfestival.application.*
import tw.springfestival.domain.*

class ModelBoundaryTest {
    @Test fun `唯讀工具先過濾角色且共用三次上限`() {
        val b=TurnBudget()
        val tools=ReadOnlyKnowledge("公開場景",listOf(Memory("private",MemoryLayer.SEMANTIC,"秘密","private",setOf("Elia")),Memory("known",MemoryLayer.EPISODIC,"一起修燈","festival")),"Miro",b)
        assertEquals("公開場景",tools.scene())
        assertEquals(listOf("known"),tools.memories("秘密").map{it.id})
        assertEquals(listOf("festival"),tools.events())
        assertThrows(IllegalStateException::class.java){tools.scene()}
    }
    @Test fun `Spring AI 工具往返與模型請求都被計數`() {
        var calls=0
        val fake=object: ChatModel {
            override fun getOptions()=org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build()
            override fun call(prompt:Prompt):ChatResponse {
                calls++
                val message=if(calls==1)AssistantMessage.builder().content("").toolCalls(listOf(AssistantMessage.ToolCall("tool-1","function","read_known_memories","{\"query\":\"約定\"}"))).build()
                    else AssistantMessage("{\"text\":\"還記得北方的約定。\"}")
                return ChatResponse(listOf(Generation(message)))
            }
        }
        val model=SpringStoryModel(ChatClient.create(fake),"live")
        val budget=TurnBudget()
        val draft=model.narrate(ValidatedOutcome(World("test","測試",0),true,"回想約定"),"Elia",listOf(Memory("promise",MemoryLayer.RELATIONSHIP,"北方約定","promise")),budget)
        assertTrue(draft.text.contains("約定"));assertEquals(2,budget.requests);assertEquals(1,budget.tools)
    }
    @Test fun `持續要求工具的模型不能超過整回合預算`() {
        var calls=0
        val fake=object:ChatModel {
            override fun getOptions()=org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build()
            override fun call(prompt:Prompt):ChatResponse {
                calls++
                return ChatResponse(listOf(Generation(AssistantMessage.builder().content("").toolCalls(listOf(AssistantMessage.ToolCall("tool-$calls","function","read_public_scene","{}"))).build())))
            }
        }
        val budget=TurnBudget()
        assertThrows(Exception::class.java){SpringStoryModel(ChatClient.create(fake),"live").narrate(ValidatedOutcome(World("test","測試",0),true,"場景"),"Elia",emptyList(),budget)}
        assertTrue(calls<=6);assertEquals(3,budget.tools)
    }
}

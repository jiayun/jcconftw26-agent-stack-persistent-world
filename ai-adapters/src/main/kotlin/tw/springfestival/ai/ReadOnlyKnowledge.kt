package tw.springfestival.ai

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisor
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import tw.springfestival.application.TurnBudget
import tw.springfestival.domain.Memory

class ReadOnlyKnowledge(private val publicScene: String, memories: List<Memory>, viewer: String, private val budget: TurnBudget) {
    private val visible = memories.filter { viewer in it.knownBy }
    @Tool(name = "read_public_scene", description = "唯讀：查閱本回合已提交的公開場景，不可更新世界。")
    fun scene(): String { budget.tool(); return publicScene }
    @Tool(name = "read_known_memories", description = "唯讀：查詢此角色已知記憶，最多六筆並附來源。不能指定其他角色。")
    fun memories(query: String): List<Memory> {
        budget.tool(); require(query.length <= 2000)
        return visible.sortedByDescending { (if ((it.correction ?: it.text).contains(query, true)) 100 else 0) + it.importance + if (it.pinned) 10 else 0 }.take(6)
    }
    @Tool(name = "read_known_events", description = "唯讀：列出此角色已知記憶的事件來源，不包含尚未揭露的作者事件。")
    fun events(): List<String> { budget.tool(); return visible.map { it.sourceId }.distinct().take(6) }
}
// 位於工具迴圈之後、模型之前，連工具續回合也會逐次計數。
class BudgetAdvisor(private val budget: TurnBudget) : CallAdvisor {
    override fun getName() = "world-turn-model-budget"
    override fun getOrder() = 0
    override fun adviseCall(request: ChatClientRequest, chain: CallAdvisorChain): ChatClientResponse {
        budget.request()
        val response = chain.nextCall(request)
        response.chatResponse()?.metadata?.usage?.totalTokens?.let { budget.recordTokens(it.toLong()) }
        return response
    }
}

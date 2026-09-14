package tw.springfestival.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tw.springfestival.application.GameService
import tw.springfestival.domain.Conflict
import tw.springfestival.domain.Missing
import tw.springfestival.domain.TurnCommand

@Configuration
@ConditionalOnProperty(name = ["spring.ai.mcp.server.enabled"], havingValue = "true")
class McpWorldAdapter {
    @Bean
    fun worldTransport() = WebMvcStreamableServerTransportProvider.builder().mcpEndpoint("/mcp")
        .contextExtractor { request ->
            McpTransportContext.create(
                mapOf(
                    "saveId" to (request.servletRequest().getAttribute("authorizedSave") ?: error("未授權"))
                )
            )
        }.build()

    @Bean
    fun worldTools(game: GameService, json: ObjectMapper): List<McpServerFeatures.SyncToolSpecification> {
        fun tool(
            name: String,
            description: String,
            properties: Map<String, Any> = emptyMap(),
            required: List<String> = emptyList(),
            handler: (String, Map<String, Any?>) -> Any
        ): McpServerFeatures.SyncToolSpecification {
            val schema = mapOf<String, Any>(
                "type" to "object",
                "properties" to properties,
                "required" to required,
                "additionalProperties" to false
            )
            val definition = McpSchema.Tool.builder(name, schema)
                .description(description + " 請將遊戲保存的結果與你額外的評論分開呈現；額外文字不會成為世界事實。")
                .build()
            return McpServerFeatures.SyncToolSpecification(definition) { exchange, request ->
                try {
                    val args = request.arguments().orEmpty()
                    require(args.keys.all { it in properties.keys } && required.all { it in args }) { "工具參數不正確，不可覆寫存檔或角色身分。" }
                    val id = exchange.transportContext().get("saveId") as? String ?: error("未授權")
                    McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(handler(id, args)))
                        .isError(false).build()
                } catch (e: Exception) {
                    val kind = when (e) {
                        is Conflict -> "CONFLICT"; is Missing -> "NOT_FOUND"; is IllegalArgumentException -> "INVALID_INPUT"; else -> "UNAVAILABLE"
                    }
                    McpSchema.CallToolResult.builder().addTextContent(
                        json.writeValueAsString(
                            mapOf(
                                "error" to kind, "message" to when (e) {
                                    is Conflict, is Missing, is IllegalArgumentException -> e.message; else -> "目前無法完成，請重新連線或回到 Web 查看。"
                                }
                            )
                        )
                    ).isError(true).build()
                }
            }
        }

        val string = mapOf("type" to "string")
        val integer = mapOf("type" to "integer", "minimum" to 0)
        return listOf(
            tool(
                "get_current_scene",
                "取得授權存檔的已提交場景、revision、在場人物及合法建議行動，不推進時間。"
            ) { id, _ -> game.scene(id) },
            tool("get_story_journal", "分頁讀取玩家已知故事與承諾。", mapOf("cursor" to integer)) { id, a ->
                journalPage(
                    game.store.load(id),
                    (a["cursor"] as? Number)?.toInt() ?: 0
                )
            },
            tool(
                "search_known_memories",
                "搜尋玩家可見記憶，最多六筆並附來源 ID。",
                mapOf("query" to string),
                listOf("query")
            ) { id, a ->
                val q = a["query"] as? String
                    ?: throw IllegalArgumentException("query 必須是文字。"); require(q.length <= 2000); game.store.load(
                id
            ).visibleMemories("player", q)
            },
            tool(
                "submit_player_turn",
                "提交一個玩家行動，持久化後立即回傳 turnId。text 與 suggestionId 擇一，expectedRevision 必須來自最新場景。衝突時重新讀取場景，不可自動改 revision 重送舊行動。",
                mapOf("requestId" to string, "expectedRevision" to integer, "text" to string, "suggestionId" to string),
                listOf("requestId", "expectedRevision")
            ) { id, a ->
                val command = json.convertValue(a, TurnCommand::class.java); game.submit(id, command, "MCP")
            },
            tool(
                "get_turn_result",
                "查詢同一存檔的回合進度與保存結果；斷線重連後仍使用原 turnId。",
                mapOf("turnId" to string),
                listOf("turnId")
            ) { id, a ->
                game.result(
                    id,
                    a["turnId"] as? String ?: throw IllegalArgumentException("turnId 必須是文字。")
                )
            }
        )
    }
}

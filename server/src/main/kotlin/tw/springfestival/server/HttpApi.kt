package tw.springfestival.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tw.springfestival.application.GameService
import tw.springfestival.application.SaveDeletion
import tw.springfestival.application.TurnRecord
import tw.springfestival.domain.*

data class CreateSave(val name: String, val demo: Boolean = false, val seed: Long = 26)
data class DeleteSaves(val saves: List<SaveDeletion>)
data class EditMemory(val expectedRevision: Long, val text: String? = null)

@RestController
@RequestMapping("/api")
class HttpApi(
    private val game: GameService,
    private val tokens: ConnectionTokens,
    @param:Value("\${world.debug}") private val debug: Boolean,
    @param:Value("\${spring.ai.mcp.server.enabled}") private val mcp: Boolean
) {
    @GetMapping("/settings")
    fun settings() = mapOf(
        "mode" to game.model.mode, "mcpEnabled" to mcp, "debugEnabled" to debug,
        "privacy" to "離線模式不傳送資料。live 模式會將輸入、目前場景及角色可見記憶傳送給設定的模型服務。存檔保存在本機 H2；外部 MCP 客戶端有自己的資料政策。"
    )

    @GetMapping("/saves")
    fun saves(): List<Map<String, Any?>> {
        val pending = game.store.pending().map { it.saveId }.toSet()
        return game.store.list().map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "revision" to it.revision,
                "day" to it.day(),
                "chapter" to it.chapter,
                "placeName" to it.place.label,
                "ending" to it.ending,
                "busy" to (it.id in pending)
            )
        }
    }

    @PostMapping("/saves/delete")
    fun delete(@RequestBody body: DeleteSaves) = mapOf("deletedIds" to game.store.delete(body.saves))
    @PostMapping("/saves")
    fun create(@RequestBody body: CreateSave) = game.create(body.name, body.demo, body.seed).let { game.scene(it.id) }
    @GetMapping("/saves/{id}/scene")
    fun scene(@PathVariable id: String) = game.scene(id)
    @GetMapping("/saves/{id}/journal")
    fun journal(@PathVariable id: String, @RequestParam(defaultValue = "0") cursor: Int) =
        journalPage(game.store.load(id), cursor)

    @GetMapping("/saves/{id}/memories")
    fun memories(@PathVariable id: String) = game.store.load(id).memories.filter { "player" in it.knownBy }
    @PatchMapping("/saves/{id}/memories/{memoryId}")
    fun edit(@PathVariable id: String, @PathVariable memoryId: String, @RequestBody body: EditMemory) =
        game.store.editMemory(id, body.expectedRevision, memoryId, body.text).let { game.scene(it.id) }

    @GetMapping("/saves/{id}/export")
    fun export(@PathVariable id: String) = game.store.load(id)
    @PostMapping("/saves/import")
    fun import(@RequestBody world: World) = game.import(world).let { game.scene(it.id) }

    @PostMapping("/saves/{id}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(@PathVariable id: String, @RequestBody body: TurnCommand) = game.submit(id, body, "WEB")
    @GetMapping("/saves/{id}/turns/latest")
    fun latest(@PathVariable id: String): Map<String, Any?> =
        mapOf("turn" to game.store.latest(id)?.let { game.result(id, it.id) })

    @GetMapping("/saves/{id}/turns/{turnId}")
    fun result(@PathVariable id: String, @PathVariable turnId: String) = game.result(id, turnId)
    @GetMapping("/saves/{id}/debug/{turnId}")
    fun debug(@PathVariable id: String, @PathVariable turnId: String): TurnRecord {
        if (!debug) throw Missing("開發觀察工具未啟用。")
        return game.result(id, turnId, true)
    }

    @PostMapping("/saves/{id}/connections")
    fun issue(@PathVariable id: String): Map<String, String> {
        require(mcp) { "請先設定 WORLD_MCP_ENABLED=true 並重新啟動。" }; game.store.load(id); return tokens.issue(id)
    }

    @GetMapping("/saves/{id}/connections")
    fun connections(@PathVariable id: String) = tokens.list(id)
    @DeleteMapping("/saves/{id}/connections/{tokenId}")
    fun revoke(@PathVariable id: String, @PathVariable tokenId: String) = tokens.revoke(id, tokenId)
}

fun journalPage(world: World, cursor: Int): Map<String, Any?> {
    require(cursor >= 0) { "分頁游標不可為負數。" }
    val items = world.journal.drop(cursor).take(20)
    return mapOf(
        "items" to items, "nextCursor" to if (world.journal.size > cursor + items.size) cursor + items.size else null,
        "promises" to world.memories.filter { it.layer == MemoryLayer.RELATIONSHIP && it.pinned && "player" in it.knownBy })
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(Conflict::class)
    fun conflict(e: Conflict) = ResponseEntity.status(409).body(mapOf("error" to e.message))
    @ExceptionHandler(Missing::class)
    fun missing(e: Missing) = ResponseEntity.status(404).body(mapOf("error" to e.message))
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException) = ResponseEntity.badRequest()
        .body(mapOf("error" to (e.message?.takeUnless { it == "Failed requirement." }
            ?: "輸入或存檔內容不符合規則，原資料未修改。")))

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun unreadable() = ResponseEntity.badRequest().body(mapOf("error" to "無法讀取資料，請確認格式與版本。"))
}

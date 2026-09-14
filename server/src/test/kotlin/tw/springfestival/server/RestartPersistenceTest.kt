package tw.springfestival.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import tw.springfestival.application.GameService
import tw.springfestival.domain.PlayerIntent
import tw.springfestival.domain.TurnCommand
import tw.springfestival.domain.effectiveText
import java.nio.file.Path

class RestartPersistenceTest {
    @TempDir
    lateinit var directory: Path
    @Test
    fun `H2 file 真正關閉重開後恢復已接受與已提交回合`() {
        val url = "jdbc:h2:file:${directory.resolve("world")};DB_CLOSE_ON_EXIT=FALSE"
        fun open() = SpringApplicationBuilder(WorldApplication::class.java).web(WebApplicationType.NONE)
            .run("--spring.datasource.url=$url", "--spring.ai.mcp.server.enabled=false", "--world.mode=offline")

        var id = "";
        var accepted = "";
        var committed = ""
        open().use { ctx ->
            val game = ctx.getBean(GameService::class.java)
            val a = game.create("已接受復原"); id = a.id
            accepted = game.store.accept(a.id, TurnCommand("accepted", 0, suggestionId = "rest"), "MCP").id
            val b = game.create("已提交復原")
            val r = game.store.accept(b.id, TurnCommand("committed", 0, suggestionId = "rest"), "WEB")
            game.store.commit(r, game.rules.resolve(b, PlayerIntent("rest"), emptyList(), r.id), emptyList())
            committed = b.id
        }
        open().use { ctx ->
            val game = ctx.getBean(GameService::class.java)
            val until = System.nanoTime() + 10_000_000_000
            while (game.store.latest(committed) == null || game.result(id, accepted).status != "COMPLETE") {
                check(System.nanoTime() < until) { "重啟恢復逾時" }; Thread.sleep(20)
            }
            assertEquals(1, game.store.load(id).revision)
            assertEquals(1, game.store.load(committed).revision)
            assertEquals(1, game.store.load(committed).tick)
            assertNotNull(game.store.latest(id)?.result)
        }
    }

    @Test
    fun `更正與刪除偏好在重啟及匯入後仍有效`() {
        val url = "jdbc:h2:file:${directory.resolve("memory-recall")};DB_CLOSE_ON_EXIT=FALSE"
        fun open() = SpringApplicationBuilder(WorldApplication::class.java).web(WebApplicationType.NONE)
            .run("--spring.datasource.url=$url", "--spring.ai.mcp.server.enabled=false", "--world.mode=offline")

        var id = ""
        open().use { ctx ->
            val game = ctx.getBean(GameService::class.java)
            val w = game.create("持久記憶", true)
            id = w.id
            game.store.editMemory(id, w.revision, "demo-tea", "你現在喜歡黑咖啡。")
        }
        open().use { ctx ->
            val game = ctx.getBean(GameService::class.java)
            val w = game.store.load(id)
            val found = w.visibleMemories("Elia", "還記得我喜歡喝什麼嗎？")
            assertEquals(listOf("你現在喜歡黑咖啡。"), found.map { it.effectiveText() })
            assertEquals(1, found.single().updatedRevision)
            val imported = game.import(w)
            assertEquals(found, imported.visibleMemories("Elia", "飲料"))
            game.store.editMemory(id, w.revision, "demo-tea", null)
        }
        open().use { ctx ->
            val game = ctx.getBean(GameService::class.java)
            assertTrue(game.store.load(id).visibleMemories("Elia", "喝什麼").isEmpty())
            assertTrue(game.store.load(id).visibleMemories("Elia", "北方約定").any { it.pinned })
        }
    }

}

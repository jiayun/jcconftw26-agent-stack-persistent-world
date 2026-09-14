package tw.springfestival.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tw.springfestival.application.GameService
import tw.springfestival.application.SaveDeletion
import tw.springfestival.application.TurnRecord
import tw.springfestival.domain.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.datasource.url=jdbc:h2:mem:integration;DB_CLOSE_DELAY=-1", "spring.ai.mcp.server.enabled=true", "world.debug=true", "world.mode=offline"]
)
class WorldIntegrationTest {
    @Autowired
    lateinit var game: GameService
    @Autowired
    lateinit var tokens: ConnectionTokens
    @Autowired
    lateinit var json: ObjectMapper
    @Autowired
    lateinit var jdbc: JdbcTemplate
    @Value("\${local.server.port}")
    var port: Int = 0
    private fun command(w: World, action: String) =
        TurnCommand(UUID.randomUUID().toString(), w.revision, suggestionId = action)

    private fun await(id: String, turn: String): TurnRecord {
        val until = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < until) {
            val r = game.result(id, turn, true)
            if (r.status == "FAILED") fail<Unit>(r.error)
            if (r.status == "COMPLETE") return r
            Thread.sleep(20)
        }
        error("回合逾時：${game.store.turn(id, turn)}")
    }

    private fun client(token: String): McpSyncClient = McpClient.sync(
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $token")).build()
    ).requestTimeout(Duration.ofSeconds(8)).build()

    private fun call(c: McpSyncClient, name: String, args: Map<String, Any> = emptyMap()) =
        c.callTool(McpSchema.CallToolRequest(name, args, null))

    private fun content(r: McpSchema.CallToolResult) =
        json.readTree((r.content().first() as McpSchema.TextContent).text())

    private fun http(
        path: String,
        method: String = "GET",
        body: Any? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody())
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `批次刪除清除回合變更權杖而保留其他存檔及可匯入備份`() {
        val a = game.create("刪除甲", true);
        val b = game.create("刪除乙");
        val keep = game.create("保留")
        val issued = tokens.issue(a.id);
        val keepToken = tokens.issue(keep.id).getValue("token")
        val turn = game.submit(a.id, command(a, "rest"), "WEB"); await(a.id, turn.turnId)
        val backup = game.store.load(a.id)
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM world_changes WHERE save_id = ?", Int::class.java, a.id)
        )
        val response = http(
            "/api/saves/delete",
            "POST",
            DeleteSaves(listOf(SaveDeletion(a.id, backup.revision), SaveDeletion(b.id, 0)))
        )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(setOf(a.id, b.id), json.readTree(response.body())["deletedIds"].map { it.asText() }.toSet())
        for (id in listOf(a.id, b.id)) {
            assertEquals(404, http("/api/saves/$id/scene").statusCode())
            for (table in listOf("turns", "world_changes", "connection_tokens"))
                assertEquals(
                    0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE save_id = ?", Int::class.java, id)
                )
        }
        assertEquals(404, http("/api/saves/${a.id}/turns/${turn.turnId}").statusCode())
        assertNull(tokens.authorize("Bearer ${issued.getValue("token")}"))
        assertEquals(keep.id, tokens.authorize("Bearer $keepToken"))
        assertEquals(keep, game.store.load(keep.id))
        assertEquals(backup.memories, game.import(backup).memories)
    }

    @Test
    fun `批次刪除拒絕新進度與處理中回合且整批保留`() {
        val a = game.create("版本");
        val b = game.create("一起保留")
        val r = game.submit(a.id, command(a, "rest"), "WEB"); await(a.id, r.turnId)
        fun delete(revision: Long) =
            http("/api/saves/delete", "POST", DeleteSaves(listOf(SaveDeletion(a.id, revision), SaveDeletion(b.id, 0))))
        assertEquals(409, delete(0).statusCode())
        val current = game.store.load(a.id)
        val pending = game.store.accept(a.id, command(current, "rest"), "WEB")
        try {
            assertEquals(409, delete(current.revision).statusCode())
            val committed = game.store.commit(
                pending,
                game.rules.resolve(current, PlayerIntent("rest"), emptyList(), pending.id),
                emptyList()
            )
            assertEquals(409, delete(committed.world.revision).statusCode())
            assertEquals(b, game.store.load(b.id))
        } finally {
            game.recover(); await(a.id, pending.id)
        }
    }

    @Test
    fun `空批次重複及遺失存檔均不部分刪除`() {
        val w = game.create("保留整批")
        for (items in listOf(emptyList(), listOf(SaveDeletion(w.id, 0), SaveDeletion(w.id, 0))))
            assertEquals(400, http("/api/saves/delete", "POST", DeleteSaves(items)).statusCode())
        assertEquals(
            404,
            http(
                "/api/saves/delete",
                "POST",
                DeleteSaves(listOf(SaveDeletion(w.id, 0), SaveDeletion(UUID.randomUUID().toString(), 0)))
            ).statusCode()
        )
        assertEquals(w, game.store.load(w.id))
    }

    @Test
    fun `提交與刪除競爭不會留下孤兒或刪掉處理中的世界`() {
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            repeat(6) {
                val w = game.create("刪除競爭")
                val start = java.util.concurrent.CyclicBarrier(2)
                val accept = executor.submit(java.util.concurrent.Callable {
                    start.await(); runCatching { game.store.accept(w.id, command(w, "rest"), "WEB") }
                })
                val delete = executor.submit(java.util.concurrent.Callable {
                    start.await(); runCatching { game.store.delete(listOf(SaveDeletion(w.id, 0))) }
                })
                val accepted = accept.get(10, java.util.concurrent.TimeUnit.SECONDS)
                val deleted = delete.get(10, java.util.concurrent.TimeUnit.SECONDS)
                if (accepted.isSuccess) {
                    assertTrue(deleted.exceptionOrNull() is Conflict)
                    assertEquals(w, game.store.load(w.id))
                    game.store.fail(accepted.getOrThrow(), "TEST_FINISHED")
                    game.store.delete(listOf(SaveDeletion(w.id, 0)))
                } else {
                    assertTrue(deleted.isSuccess, deleted.exceptionOrNull()?.toString())
                    assertTrue(accepted.exceptionOrNull() is Missing)
                }
                assertEquals(
                    0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM turns WHERE save_id = ?", Int::class.java, w.id)
                )
            }
        }
    }

    @Test
    fun `真實 MCP 交握五工具與跨入口去重`() {
        val w = game.create("跨入口", true)
        val token = tokens.issue(w.id).getValue("token")
        val cmd = command(w, "event:closure:ferry")
        val web = http("/api/saves/${w.id}/turns", "POST", cmd)
        assertEquals(202, web.statusCode(), web.body())
        val turnId = json.readTree(web.body())["turnId"].asText()
        await(w.id, turnId)
        client(token).use { c ->
            assertNotNull(c.initialize())
            assertEquals(
                setOf(
                    "get_current_scene",
                    "get_story_journal",
                    "search_known_memories",
                    "submit_player_turn",
                    "get_turn_result"
                ), c.listTools().tools().map { it.name() }.toSet()
            )
            val before = game.store.load(w.id)
            assertEquals(before.revision, content(call(c, "get_current_scene"))["revision"].asLong())
            assertFalse(call(c, "get_story_journal").isError() == true)
            val memories = content(call(c, "search_known_memories", mapOf("query" to "約定")))
            assertTrue(memories.toString().contains("demo-promise"))
            assertEquals(before, game.store.load(w.id))
            val args: Map<String, Any> = json.convertValue(cmd, Map::class.java).entries.filter { it.value != null }
                .associate { it.key as String to it.value!! }
            val duplicate = call(c, "submit_player_turn", args)
            assertFalse(duplicate.isError() == true, duplicate.toString())
            assertEquals(turnId, content(duplicate)["turnId"].asText())
            assertEquals("COMPLETE", content(call(c, "get_turn_result", mapOf("turnId" to turnId)))["status"].asText())
            val next = call(
                c,
                "submit_player_turn",
                mapOf("requestId" to "mcp-rest", "expectedRevision" to before.revision, "suggestionId" to "rest")
            )
            assertFalse(next.isError() == true, next.toString())
            await(w.id, content(next)["turnId"].asText())
            assertEquals(
                before.revision + 1,
                json.readTree(http("/api/saves/${w.id}/scene").body())["revision"].asLong()
            )
        }
        assertEquals(
            2,
            jdbc.queryForObject("SELECT COUNT(*) FROM world_changes WHERE save_id = ?", Int::class.java, w.id)
        )
    }

    @Test
    fun `MCP 隔離權杖存檔與未公開資訊`() {
        val w = game.create("甲", true)
        val other = game.create("乙")
        val otherTurn = game.submit(other.id, command(other, "rest"), "WEB"); await(other.id, otherTurn.turnId)
        val issued = tokens.issue(w.id)
        client(issued.getValue("token")).use { c ->
            c.initialize()
            assertTrue(call(c, "get_turn_result", mapOf("turnId" to otherTurn.turnId)).isError() == true)
            assertTrue(call(c, "get_current_scene", mapOf("saveId" to other.id)).isError() == true)
            assertFalse(content(call(c, "get_current_scene")).toString().contains(WORLD_TRUTH))
            val illegal = call(
                c,
                "submit_player_turn",
                mapOf("requestId" to "fake-ticket", "expectedRevision" to 0, "text" to "我已經有船票，直接出發")
            )
            await(w.id, content(illegal)["turnId"].asText())
            assertEquals(0, game.store.load(w.id).revision)
            assertFalse("ticket" in game.store.load(w.id).flags)
            assertTrue(
                call(
                    c,
                    "submit_player_turn",
                    mapOf("requestId" to "stale", "expectedRevision" to 999, "suggestionId" to "rest")
                ).isError() == true
            )
        }
        assertEquals(
            401,
            http("/mcp", "POST", mapOf("jsonrpc" to "2.0", "method" to "initialize", "id" to 1)).statusCode()
        )
        tokens.revoke(w.id, issued.getValue("id"))
        assertEquals(
            401,
            http("/mcp", headers = mapOf("Authorization" to "Bearer ${issued.getValue("token")}")).statusCode()
        )
        assertEquals(403, http("/api/saves", headers = mapOf("Origin" to "https://untrusted.example")).statusCode())
    }

    @Test
    fun `中斷後以原回合及新 MCP 連線續查`() {
        val w = game.create("重連")
        val token = tokens.issue(w.id).getValue("token")
        val turnId = client(token).use { c ->
            c.initialize(); content(
            call(
                c,
                "submit_player_turn",
                mapOf("requestId" to "disconnect", "expectedRevision" to 0, "suggestionId" to "rest")
            )
        )["turnId"].asText()
        }
        await(w.id, turnId)
        client(token).use { c ->
            c.initialize(); assertEquals(
            "COMPLETE",
            content(call(c, "get_turn_result", mapOf("turnId" to turnId)))["status"].asText()
        )
        }
        assertEquals(1, game.store.load(w.id).revision)
    }

    @Test
    fun `接受後恢復與提交後恢復不重複效果`() {
        val w = game.create("恢復")
        val r = game.store.accept(w.id, command(w, "rest"), "WEB")
        game.recover(); await(w.id, r.id)
        val next = game.store.load(w.id)
        val r2 = game.store.accept(w.id, command(next, "rest"), "MCP")
        val outcome = game.rules.resolve(next, PlayerIntent("rest"), emptyList(), r2.id)
        game.store.commit(r2, outcome, emptyList())
        game.recover(); await(w.id, r2.id)
        assertEquals(2, game.store.load(w.id).revision)
        assertEquals(
            2,
            jdbc.queryForObject("SELECT COUNT(*) FROM world_changes WHERE save_id = ?", Int::class.java, w.id)
        )
    }

    @Test
    fun `不同內容重用 ID 與雙分頁皆衝突`() {
        val w = game.create("衝突")
        val cmd = command(w, "rest")
        val r = game.submit(w.id, cmd, "WEB"); await(w.id, r.turnId)
        assertThrows(Conflict::class.java) { game.submit(w.id, cmd.copy(suggestionId = "chat"), "MCP") }
        assertThrows(Conflict::class.java) { game.submit(w.id, cmd.copy(requestId = "second-tab"), "WEB") }
        assertEquals(r.turnId, game.submit(w.id, cmd, "MCP").turnId)
    }

    @Test
    fun `記憶更正匯入 round trip 不逆轉世界`() {
        val w = game.create("記憶", true)
        val edited = game.store.editMemory(w.id, 0, "demo-tea", "我現在喜歡熱湯。")
        assertEquals(w.flags, edited.flags)
        val imported = game.import(json.readValue(json.writeValueAsString(edited), World::class.java))
        assertNotEquals(edited.id, imported.id); assertEquals(edited.memories, imported.memories)
        assertThrows(IllegalArgumentException::class.java) { game.import(edited.copy(schemaVersion = 99)) }
        assertEquals(edited, game.store.load(w.id))
        val deleted = game.store.editMemory(w.id, 1, "demo-tea", null)
        assertFalse(deleted.memories.any { it.id == "demo-tea" }); assertTrue("promise" in deleted.flags)
    }

    @Test
    fun `四十四事件內容 schema 及兩種解法出發延期都可達`() {
        assertEquals(
            mapOf("main" to 6, "character" to 10, "life" to 16, "memory" to 6, "atmosphere" to 6),
            game.rules.events.groupingBy { it.category }.eachCount()
        )
        for (solution in listOf("repair", "cooperate")) for (ending in listOf("depart", "postpone")) {
            var w = World(UUID.randomUUID().toString(), "完整路線", 26)
            fun act(action: String) {
                val o = game.rules.resolve(
                    w,
                    PlayerIntent(action),
                    emptyList(),
                    UUID.randomUUID().toString()
                ); assertTrue(o.accepted, "$action: ${o.text}"); w = o.world
            }

            fun event(id: String, branch: String) {
                val e = game.rules.events.first { it.id == id }; w = w.copy(place = e.place)
                var guard = 0
                while (game.rules.event(w)?.id != id && guard++ < 30) {
                    val active = game.rules.event(w)
                        ?: error("找不到 $id"); act("event:${active.id}:${active.branches.first { w.flags.containsAll(it.require) }.id}")
                }
                act("event:$id:$branch")
            }
            event("arrival", "tea"); event("promise", "yes"); event("anomaly", "observe"); event("closure", "ferry")
            if (solution == "repair") event("miro_archive", "help") else {
                w = w.copy(place = Place.MARKET)
                while ("allies" !in w.flags) {
                    val e = game.rules.event(w) ?: error("沒有協作事件"); act("event:${e.id}:${e.branches.first().id}")
                }
            }
            event("festival", solution); event("miro_boat", "help"); event("departure", ending)
            assertEquals(6, w.chapter); assertNotNull(w.ending); assertTrue("festivalDone" in w.flags)
            assertTrue(w.memories.any { it.pinned && it.sourceId == "promise" })
            assertFalse(game.rules.eligible(w).any { it.category == "main" })
        }
    }

    @Test
    fun `沒有船票仍可一起延期且聊天只更新記憶不推進時段`() {
        val w =
            World("no-ticket", "延期", 26, chapter = 5, place = Place.DOCK, flags = setOf("festivalDone", "promise"))
        assertTrue(game.rules.suggestions(w).any { it.id == "event:departure:postpone" })
        val ended = game.rules.resolve(w, PlayerIntent("event:departure:postpone"), emptyList(), "delay")
        assertEquals("postponed", ended.world.ending); assertFalse("ticket" in ended.world.flags)
        val chat = game.rules.resolve(
            w,
            PlayerIntent("chat", memoryCandidate = "我喜歡熱湯"),
            emptyList(),
            "chat",
            "我喜歡熱湯"
        )
        assertEquals(w.tick, chat.world.tick); assertEquals(1, chat.world.revision)
        assertTrue(chat.world.memories.any { it.layer == MemoryLayer.SEMANTIC })
        val forged = game.rules.resolve(
            w,
            PlayerIntent("chat", memoryCandidate = "我喜歡不存在的偏好"),
            emptyList(),
            "fake",
            "你好"
        )
        assertFalse(forged.world.memories.any { it.layer == MemoryLayer.SEMANTIC })
    }

    @Test
    fun `兩個同時到達的請求只接受一個新回合`() {
        val w = game.create("同時寫入")
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                pool.submit(java.util.concurrent.Callable {
                    gate.await()
                    runCatching {
                        game.store.accept(
                            w.id,
                            TurnCommand("concurrent-$index", 0, suggestionId = "rest"),
                            "WEB"
                        )
                    }
                })
            }
            gate.countDown()
            val results = futures.map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.first { it.isFailure }.exceptionOrNull() is Conflict)
            game.recover(); await(w.id, results.first { it.isSuccess }.getOrThrow().id)
            assertEquals(1, game.store.load(w.id).revision)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `HTTP 拒絕 null revision 與非整數 revision`() {
        val w = game.create("型別契約")
        for (revision in listOf(null, 0.5)) {
            assertEquals(
                400,
                http(
                    "/api/saves/${w.id}/turns",
                    "POST",
                    mapOf("requestId" to "invalid", "expectedRevision" to revision, "suggestionId" to "rest")
                ).statusCode()
            )
        }
    }

    @Test
    fun `固定種子二百回合不刷技能不膨脹不重開主線`() {
        var w = World(
            "simulation",
            "長程",
            26,
            chapter = 6,
            ending = "postponed",
            flags = setOf("festivalDone", "journeyDeferred", "promise")
        )
        repeat(200) { i ->
            w = w.copy(place = Place.entries[i % 5])
            val action = game.rules.suggestions(w).first().id.let { if (it == "chat") "rest" else it }
            val outcome = game.rules.resolve(w, PlayerIntent(action), emptyList(), "sim-$i"); w = outcome.world
            assertEquals(
                6,
                w.chapter
            ); assertTrue(w.memories.size <= 150); assertTrue(w.relationships.values.all { it.trust in 0..10 })
            w.skills.forEach { (_, evidence) -> assertEquals(evidence.size, evidence.distinct().size) }
        }
        assertTrue(w.completed.size >= 20, "實際觸發 ${w.completed.size} 個事件，檢查事件飢餓")
    }
}

package tw.springfestival.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tw.springfestival.application.*
import tw.springfestival.domain.*
import java.sql.ResultSet
import java.util.UUID

class JdbcWorldStore(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val json: ObjectMapper) : WorldStore {
    private fun <T> decode(value: String, type: Class<T>): T = json.readValue(value, type)
    private fun encode(value: Any) = json.writeValueAsString(value)
    override fun list(): List<World> = jdbc.query("SELECT document FROM saves ORDER BY id") { rs, _ -> decode(rs.getString(1), World::class.java) }
    override fun delete(saves: List<SaveDeletion>): List<String> = tx.execute {
        require(saves.size in 1..1000 && saves.map { it.id }.distinct().size == saves.size) { "請選擇 1 至 1000 個不重複的存檔。" }
        require(saves.all { it.id.length in 1..36 && it.expectedRevision >= 0 }) { "存檔刪除資料不正確。" }
        // Lock in a stable order, shared with turn acceptance/commit and memory editing.
        val selected = saves.sortedBy { it.id }.map { it to locked(it.id) }
        selected.forEach { (request, world) ->
            if (world.revision != request.expectedRevision) throw Conflict("存檔「${world.name}」已更新，請重新確認後再刪除。")
            if (jdbc.queryForObject("SELECT COUNT(*) FROM turns WHERE save_id = ? AND status IN ('ACCEPTED','COMMITTED')", Int::class.java, world.id)!! > 0)
                throw Conflict("存檔「${world.name}」仍有回合正在處理，請等候完成後再刪除。")
        }
        selected.forEach { (_, world) ->
            jdbc.update("DELETE FROM world_changes WHERE save_id = ?", world.id)
            jdbc.update("DELETE FROM turns WHERE save_id = ?", world.id)
            jdbc.update("DELETE FROM connection_tokens WHERE save_id = ?", world.id)
            jdbc.update("DELETE FROM saves WHERE id = ?", world.id)
        }
        saves.map { it.id }
    }!!
    override fun load(id: String): World = jdbc.query("SELECT document FROM saves WHERE id = ?", { rs, _ -> decode(rs.getString(1), World::class.java) }, id).firstOrNull() ?: throw Missing("找不到這個存檔。")
    private fun locked(id: String): World = jdbc.query("SELECT document FROM saves WHERE id = ? FOR UPDATE", { rs, _ -> decode(rs.getString(1), World::class.java) }, id).firstOrNull() ?: throw Missing("找不到這個存檔。")
    override fun create(world: World): World { jdbc.update("INSERT INTO saves(id, revision, document) VALUES (?, ?, ?)", world.id, world.revision, encode(world)); return world }
    private fun row(rs: ResultSet): TurnRecord = TurnRecord(rs.getString("id"), rs.getString("save_id"), decode(rs.getString("command"), TurnCommand::class.java), rs.getString("status"), rs.getString("entry"), rs.getString("outcome")?.let { decode(it, ValidatedOutcome::class.java) }, rs.getString("result")?.let { decode(it, TurnResult::class.java) }, rs.getString("error"))
    override fun latest(saveId: String): TurnRecord? = jdbc.query("SELECT * FROM turns WHERE save_id = ? AND status = 'COMPLETE' ORDER BY created_at DESC LIMIT 1", { rs, _ -> row(rs) }, saveId).firstOrNull()
    override fun turn(saveId: String, turnId: String): TurnRecord = jdbc.query("SELECT * FROM turns WHERE id = ? AND save_id = ?", { rs, _ -> row(rs) }, turnId, saveId).firstOrNull() ?: throw Missing("找不到這個存檔的回合。")
    override fun accept(saveId: String, command: TurnCommand, entry: String): TurnRecord = tx.execute {
        val world = locked(saveId)
        val existing = jdbc.query("SELECT * FROM turns WHERE save_id = ? AND request_id = ?", { rs, _ -> row(rs) }, saveId, command.requestId).firstOrNull()
        if (existing != null) {
            if (existing.command != command) throw Conflict("同一 requestId 不能用於不同內容。")
            existing
        } else {
            if (world.revision != command.expectedRevision) throw Conflict("場景版本已更新，請重新讀取場景。")
            if (jdbc.queryForObject("SELECT COUNT(*) FROM turns WHERE save_id = ? AND status IN ('ACCEPTED', 'COMMITTED')", Int::class.java, saveId)!! > 0) throw Conflict("此存檔有回合正在處理，請先等候結果。")
            val r = TurnRecord(UUID.randomUUID().toString(), saveId, command, "ACCEPTED", entry)
            jdbc.update("INSERT INTO turns(id, save_id, request_id, command, status, entry) VALUES (?, ?, ?, ?, ?, ?)", r.id, saveId, command.requestId, encode(command), r.status, entry)
            r
        }
    }!!
    override fun commit(record: TurnRecord, outcome: ValidatedOutcome, plans: List<NpcPlan>): ValidatedOutcome = tx.execute {
        val old = locked(record.saveId)
        val existing = turn(record.saveId, record.id)
        if (existing.outcome != null) existing.outcome
        else {
            if (old.revision != record.command.expectedRevision) throw Conflict("提交前世界版本已改變。")
            require(outcome.world.id == old.id && outcome.world.revision == old.revision + if (outcome.accepted) 1 else 0)
            jdbc.update("UPDATE saves SET revision = ?, document = ? WHERE id = ?", outcome.world.revision, encode(outcome.world), old.id)
            jdbc.update("INSERT INTO world_changes(turn_id, save_id, old_revision, new_revision, before_state, after_state) VALUES (?, ?, ?, ?, ?, ?)", record.id, old.id, old.revision, outcome.world.revision, encode(old), encode(outcome.world))
            jdbc.update("UPDATE turns SET status = 'COMMITTED', outcome = ?, plans = ? WHERE id = ?", encode(outcome), encode(plans), record.id)
            outcome
        }
    }!!
    override fun finish(record: TurnRecord, result: TurnResult) { jdbc.update("UPDATE turns SET status = 'COMPLETE', result = ?, error = NULL WHERE id = ? AND status = 'COMMITTED'", encode(result), record.id) }
    override fun fail(record: TurnRecord, category: String) {
        // 提交後的失敗保持可恢復；不能將同一事件重新提交。
        jdbc.update("UPDATE turns SET status = CASE WHEN outcome IS NULL THEN 'FAILED' ELSE 'COMMITTED' END, error = ? WHERE id = ? AND status <> 'COMPLETE'", category, record.id)
    }
    override fun pending(): List<TurnRecord> = jdbc.query("SELECT * FROM turns WHERE status IN ('ACCEPTED', 'COMMITTED') ORDER BY created_at") { rs, _ -> row(rs) }
    override fun editMemory(id: String, expectedRevision: Long, memoryId: String, text: String?): World = tx.execute {
        val w = locked(id)
        if (w.revision != expectedRevision) throw Conflict("記憶已更新，請重新讀取。")
        if (jdbc.queryForObject("SELECT COUNT(*) FROM turns WHERE save_id = ? AND status IN ('ACCEPTED','COMMITTED')", Int::class.java, id)!! > 0) throw Conflict("請等候目前回合完成後再更正記憶。")
        val next = w.changeMemory(memoryId, text)
        jdbc.update("UPDATE saves SET revision = ?, document = ? WHERE id = ?", next.revision, encode(next), id)
        next
    }!!
}

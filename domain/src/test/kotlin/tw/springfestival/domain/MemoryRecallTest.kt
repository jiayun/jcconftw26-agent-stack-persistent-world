package tw.springfestival.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryRecallTest {
    private val promise = Memory("promise", MemoryLayer.RELATIONSHIP, "我們約好春祭後去北方。", "promise", importance = 5, pinned = true)
    private val tea = Memory("tea", MemoryLayer.SEMANTIC, "你喜歡無糖茶。", "arrival")
    private val world = World("memory", "記憶測試", 26, memories = listOf(promise, tea))

    @Test fun `飲料問句找偏好而旅行問句找承諾`() {
        assertEquals(listOf("tea"), world.visibleMemories("Elia", "還記得我喜歡喝什麼嗎？").map { it.id })
        assertEquals(listOf("promise"), world.visibleMemories("Elia", "我們說好去哪裡？").map { it.id })
        assertEquals(listOf("tea"), world.visibleMemories("Elia", "我的飲料偏好").map { it.id })
    }
    @Test fun `新偏好與更正優先不回收舊文字`() {
        val newer = tea.copy(id = "coffee", text = "我喜歡黑咖啡。", updatedRevision = 2)
        val changed = world.copy(revision = 2, memories = world.memories + newer).changeMemory("coffee", "我現在喜歡熱可可。")
        assertEquals(listOf("我現在喜歡熱可可。"), changed.visibleMemories("Elia", "喜歡喝什麼").map { it.effectiveText() })
        assertFalse(changed.memories.any { it.id == "tea" })
        assertEquals("我現在喜歡熱可可。", changed.visibleMemories("Elia", "喝什麼").single().text)
        assertEquals(3, changed.memories.single { it.id == "coffee" }.updatedRevision)
    }
    @Test fun `刪除偏好不從舊副本或工作對話復活`() {
        val duplicate = tea.copy(id = "duplicate", updatedRevision = 1)
        val working = Memory("question", MemoryLayer.WORKING, "我喜歡無糖茶。", "old-turn")
        val changed = world.copy(revision = 1, memories = world.memories + duplicate + working).changeMemory("duplicate", null)
        assertTrue(changed.visibleMemories("Elia", "還記得我喝什麼嗎？").isEmpty())
        assertTrue(changed.memories.any { it.id == "promise" && it.pinned })
    }
    @Test fun `不知情角色與無關問題得不到記憶`() {
        val privateTea = tea.copy(knownBy = setOf("player", "Elia"))
        val w = world.copy(memories = listOf(promise, privateTea))
        assertTrue(w.visibleMemories("Miro", "喜歡喝什麼").isEmpty())
        assertTrue(w.visibleMemories("Elia", "我的寵物叫什麼名字").isEmpty())
    }
    @Test fun `相同主題的新私人偏好不覆蓋另一角色已知版本`() {
        val privateCoffee = tea.copy(id = "private", text = "我喜歡咖啡。", knownBy = setOf("player", "Elia"), updatedRevision = 2)
        val w = world.copy(memories = world.memories + privateCoffee)
        assertEquals("private", w.visibleMemories("Elia", "飲料").single().id)
        assertEquals("tea", w.visibleMemories("Miro", "飲料").single().id)
    }
    @Test fun `多回合閒聊與別的記憶不影響回想`() {
        val rules = WorldRules(emptyList())
        var w = rules.resolve(world, PlayerIntent("chat", memoryCandidate = "我喜歡無糖茶。"), emptyList(), "preference", "我喜歡無糖茶。").world
        repeat(12) { w = rules.resolve(w, PlayerIntent("chat"), emptyList(), "chat-$it", "今天天氣不錯。").world }
        val reply = rules.resolve(w, PlayerIntent("chat"), emptyList(), "recall", "還記得我喜歡喝什麼嗎？")
        assertTrue(reply.text.contains("無糖茶"))
        assertFalse(reply.text.contains("北方"))
        assertEquals(w.tick, reply.world.tick)
        assertTrue(reply.world.memories.count { it.layer == MemoryLayer.WORKING } <= 8)
    }
    @Test fun `共同回想不把玩家的私人記憶洩漏給在場角色`() {
        val privateTea = tea.copy(knownBy = setOf("player"))
        val reply = WorldRules(emptyList()).resolve(world.copy(memories = listOf(privateTea)), PlayerIntent("chat"), emptyList(), "q", "還記得我喝什麼嗎？")
        assertFalse(reply.text.contains("無糖茶"))
        assertTrue(reply.text.contains("沒有找到"))
    }
}

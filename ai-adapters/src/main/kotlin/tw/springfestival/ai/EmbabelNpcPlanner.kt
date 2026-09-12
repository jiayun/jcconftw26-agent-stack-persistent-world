package tw.springfestival.ai

import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.plan.common.condition.*
import tw.springfestival.application.NpcPlanner
import tw.springfestival.domain.*

class EmbabelNpcPlanner : NpcPlanner {
    override fun plan(world: World): List<NpcPlan> = listOf(
        planGoal(world, "Elia", "travel"), planGoal(world, "Miro", "investigate"), planGoal(world, "Miro", "festival"))

    fun planGoal(w: World, npc: String, goal: String): NpcPlan {
        // 只把已公開的旗標變成條件；私人記憶不會進入另一個角色的規劃器。
        val facts = mapOf("bridgeOpen" to ("bridgeClosed" !in w.flags), "ferryAvailable" to (w.weather() != "細雨"),
            "archiveOpen" to (w.tick % 3 != 2), "witnessAvailable" to (w.tick % 3 == 2),
            "truthKnown" to ("truth" in w.flags), "helpersKnown" to ("allies" in w.flags))
        fun action(id: String, pre: List<String>, post: String, cost: Double) = ConditionAction(id, pre = pre, post = listOf(post), cost = { cost })
        val actions = when (goal) {
            "travel" -> listOf(action("check_bridge", listOf("bridgeOpen"), "routeKnown", .1),
                action("ask_ferry", listOf("ferryAvailable"), "ferryKnown", .2), action("propose_ferry", listOf("ferryKnown"), "routeKnown", .2),
                action("suggest_delay", emptyList(), "routeKnown", .9))
            "investigate" -> listOf(action("read_archive", listOf("archiveOpen"), "recordFound", .1),
                action("compare_diagrams", listOf("recordFound"), "informationKnown", .2),
                action("ask_witness", listOf("witnessAvailable"), "testimonyFound", .1),
                action("inspect_lamp", listOf("testimonyFound"), "informationKnown", .2))
            "festival" -> listOf(action("prepare_tools", listOf("truthKnown"), "repairReady", .1),
                action("propose_repair", listOf("repairReady"), "festivalPlan", .1),
                action("ask_helpers", emptyList(), "helpersKnown", .3),
                action("propose_cooperation", listOf("helpersKnown"), "festivalPlan", .2))
            else -> error("未知 NPC 目標")
        }
        val target = mapOf("travel" to "routeKnown", "investigate" to "informationKnown", "festival" to "festivalPlan").getValue(goal)
        val conditions = (actions.flatMap { it.knownConditions }.toSet() + facts.keys).associateWith { facts[it] ?: false }
        val planner = DefaultPlannerFactory.createPlanner(ProcessOptions(), WorldStateDeterminer.fromMap(conditions.mapValues { ConditionDetermination(it.value) }))
        val path = planner.planToGoal(actions, ConditionGoal(target))?.actions?.map { it.name }.orEmpty()
        return NpcPlan(npc, goal, w.revision, conditions, path, path.firstOrNull())
    }
}

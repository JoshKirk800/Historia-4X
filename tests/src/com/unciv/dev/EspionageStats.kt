package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.unit.EspionageScoring
import com.unciv.logic.civilization.managers.SpyMissionResolver
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import java.io.File
import java.util.Locale

/**
 * What the spies do, for [FullGameSim]'s report (only in a game with espionage enabled, `FULL_SIM_ESPIONAGE`). Read from the state at the end of each turn, nothing of the game is touched:
 * the spies alive and what they do, the missions done (a spy whose `lastMissionTurn` is this turn did the mission it was working towards the turn before, read as `targetMission` then),
 * the spies lost (a spy whose action became Dead), and the Network Points stored. Technologies and gold stolen are not told apart from what a civilization gets otherwise, so the
 * missions by name stand for them (Steal a Technology, Burgle the National Treasury).
 */
class EspionageStats(private val game: GameInfo, private val traceFile: File? = null) {
    init {
        traceFile?.writeText("turn\tevent\tciv\tspy\tcity\tpoints\ttarget\ttargetScore\tbest\tbestScore\tdetail\n")
    }

    private class Civ {
        var spyTurns = 0
        var gatheringTurns = 0
        var storedPointsTurns = 0L
        var lost = 0
        val missions = java.util.TreeMap<String, Int>()
        var firstSpyTurn = 0
        var firstMissionTurn = 0
    }

    private val civs = java.util.TreeMap<String, Civ>()
    private var targetBefore = HashMap<String, String?>()
    private var deadBefore = HashSet<String>()

    private fun key(civ: String, spyName: String) = "$civ#$spyName"

    /** Called before the turn is played: what each spy works towards, which is gone from it once the mission is done */
    fun beforeTurn() {
        targetBefore.clear()
        for (civ in game.civilizations) {
            if (civ.isBarbarian || civ.isSpectator || civ.isCityState) continue
            for (spy in civ.espionageManager.spyList) targetBefore[key(civ.civName, spy.name)] = spy.targetMission
        }
    }

    fun afterTurn(turn: Int) {
        trace(turn)
        for (civ in game.civilizations) {
            if (civ.isBarbarian || civ.isSpectator || civ.isCityState) continue
            val stats = civs.getOrPut(civ.civName) { Civ() }
            val spies = civ.espionageManager.spyList
            for (spy in spies) {
                val id = key(civ.civName, spy.name)
                if (spy.isAlive()) {
                    stats.spyTurns++
                    if (stats.firstSpyTurn == 0) stats.firstSpyTurn = turn
                    stats.storedPointsTurns += spy.networkPoints
                    if (spy.action == SpyAction.GatheringIntel) stats.gatheringTurns++
                } else if (deadBefore.add(id)) stats.lost++
                // a spy does at most one mission a turn and sets lastMissionTurn when it does one: a change since the last read is one mission, the one it worked towards before the turn
                if (spy.lastMissionTurn != (lastMissionSeen.put(id, spy.lastMissionTurn) ?: 0)) {
                    // a mission done with no target chosen is the one a slow spy does at once (CvEspionageAI::PerformSpyMissions, "slow progress")
                    stats.missions.merge(targetBefore[id] ?: "(opportunity)", 1, Int::plus)
                    if (stats.firstMissionTurn == 0) stats.firstMissionTurn = turn
                }
            }
        }
    }

    private val lastMissionSeen = HashMap<String, Int>()

    // --- the trace of FULL_SIM_ESPIONAGE_TRACE: what each spy decides, turn by turn, with the scores the AI read ---
    private val missionTraced = HashMap<String, Int>()
    private val lastTarget = HashMap<String, String?>()
    private val lastCity = HashMap<String, String?>()

    /** What the AI reads for [mission] in the city of [spy]: -1 where it cannot be worked towards (`EspionageAutomation.missionScore`) */
    private fun aiScore(spy: Spy, mission: String?): Int? {
        val city = spy.getCityOrNull() ?: return null
        val rule = mission?.let { game.ruleset.spyMissions[it] } ?: return null
        if (city.civ == spy.civInfo) return null
        return if (SpyMissionResolver.canWorkTowards(spy, rule)) EspionageScoring.missionScore(spy.civInfo, spy, city, rule) else -1
    }

    /** The best mission of the city by the AI's own rule: the highest score of those that can be worked towards, the first of equals */
    private fun best(spy: Spy): Pair<String, Int>? {
        val city = spy.getCityOrNull() ?: return null
        if (city.civ == spy.civInfo) return null
        var best: Pair<String, Int>? = null
        for (mission in game.ruleset.spyMissions.values) {
            if (mission.isFocus) continue
            val score = aiScore(spy, mission.name) ?: continue
            if (score >= 0 && (best == null || score > best.second)) best = mission.name to score
        }
        return best
    }

    private fun line(turn: Int, event: String, spy: Spy, detail: String = "") {
        val file = traceFile ?: return
        val city = spy.getCityOrNull()
        val target = aiScore(spy, spy.targetMission)
        val best = best(spy)
        file.appendText(listOf(turn, event, spy.civInfo.civName, spy.name, city?.let { "${it.civ.civName}/${it.name}" } ?: "-", spy.networkPoints,
            spy.targetMission ?: "-", target ?: "", best?.first ?: "-", best?.second ?: "", detail).joinToString("\t") + "\n")
    }

    /** Called after the turn: the events of every spy that changed what it works towards, where it is, whether it lives or did a mission, and a snapshot every 10 turns */
    private fun trace(turn: Int) {
        if (traceFile == null) return
        for (civ in game.civilizations) {
            if (civ.isBarbarian || civ.isSpectator || civ.isCityState) continue
            for (spy in civ.espionageManager.spyList) {
                val id = key(civ.civName, spy.name)
                val city = spy.getCityOrNull()?.let { "${it.civ.civName}/${it.name}" }
                if (!spy.isAlive()) { if (lastCity.put(id, "dead") != "dead") line(turn, "lost", spy); continue }
                if (lastCity.put(id, city) != city && (turn > 1 || city != null)) line(turn, "move", spy, "to ${city ?: "the hideout"}")
                val target = spy.targetMission
                if (lastTarget.put(id, target) != target) {
                    // when a target is given up, why it can no longer be worked towards (the reason the resolver gives for the mission it had), and the points against its price
                    val was = targetBefore[id]
                    val rule = was?.let { game.ruleset.spyMissions[it] }
                    val why = if (target == null && rule != null && spy.getCityOrNull() != null)
                        SpyMissionResolver.unavailableReason(spy, rule)?.let { "; now: $it" } ?: "; still workable" else ""
                    line(turn, "target", spy, "was ${was ?: "-"}${rule?.let { " (${it.networkPoints} points)" } ?: ""}$why")
                }
                // the same reading as the report: a mission is a change of lastMissionTurn since the last turn (the turn the game stamps on it is not this turn's number)
                if (spy.lastMissionTurn != (missionTraced.put(id, spy.lastMissionTurn) ?: 0)) line(turn, "mission", spy, "did ${targetBefore[id] ?: "(opportunity)"}, stamped ${spy.lastMissionTurn}")
                if (turn % 10 == 0 && spy.action == SpyAction.GatheringIntel) line(turn, "snapshot", spy)
            }
        }
    }

    fun report(): String {
        val text = StringBuilder("## Espionage\n")
        text.appendLine("Per major civilization: turns with a living spy (and gathering intel), the Network Points stored in them summed over the turns, spies lost, the turn of the first spy and of the first mission, and the missions done by name")
        if (civs.isEmpty() || civs.values.all { it.spyTurns == 0 }) text.appendLine("no spy in the whole game")
        for ((name, stats) in civs) text.appendLine(String.format(Locale.ROOT,
            "%-16s spy-turns=%4d gathering=%4d np-turns=%7d lost=%2d first-spy=%3d first-mission=%3d missions=%s",
            name, stats.spyTurns, stats.gatheringTurns, stats.storedPointsTurns, stats.lost, stats.firstSpyTurn, stats.firstMissionTurn, stats.missions))
        return text.toString()
    }
}

package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.models.SpyAction
import java.util.Locale

/**
 * What the spies do, for [FullGameSim]'s report (only in a game with espionage enabled, `FULL_SIM_ESPIONAGE`). Read from the state at the end of each turn, nothing of the game is touched:
 * the spies alive and what they do, the missions done (a spy whose `lastMissionTurn` is this turn did the mission it was working towards the turn before, read as `targetMission` then),
 * the spies lost (a spy whose action became Dead), and the Network Points stored. Technologies and gold stolen are not told apart from what a civilization gets otherwise, so the
 * missions by name stand for them (Steal a Technology, Burgle the National Treasury).
 */
class EspionageStats(private val game: GameInfo) {
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

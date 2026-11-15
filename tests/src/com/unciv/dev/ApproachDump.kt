package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Why does a civilization hold the approach it holds towards another? For a saved game (APPROACH_DUMP_SAVE, e.g. the `.checkpoint.save` of a
 * [FullGameSim] run), writes to APPROACH_DUMP_OUT, for every ordered pair of living major civilizations that have met, the weights of
 * [ApproachAi.approachWeights] (before the random part) next to the values that feed them: grand strategy and its priorities, opinion, war
 * projection and goal, warmonger threat, military strength compared to ours, proximity, and whether a war could be declared. A developer tool,
 * not a test: it does nothing unless APPROACH_DUMP_SAVE is set.
 */
@RunWith(GdxTestRunner::class)
class ApproachDump {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set APPROACH_DUMP_SAVE to run", System.getenv("APPROACH_DUMP_SAVE") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController()
            RulesetCache.loadRulesets(noMods = true)
        }
    }

    @Test
    fun dump() {
        val game = UncivFiles.gameInfoFromString(File(System.getenv("APPROACH_DUMP_SAVE")).readText())
        UncivGame.Current.gameInfo = game
        val rules = game.ruleset.ai
        val out = StringBuilder("turn ${game.turns}; weights by approach: ${MajorCivApproach.entries.joinToString(" ")}\n")
        val majors = game.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }
        for (civ in majors) {
            val ai = civ.ai
            out.append("\n${civ.civName}: grand strategy ${ai.grandStrategy}, priorities ${ai.grandStrategyPriorities}; nuclear weapons ${civ.units.getCivUnits().count { it.isNuclearWeapon() }}, military units ${civ.units.getCivUnits().count { it.isMilitary() }}\n")
            for (other in majors) {
                if (other == civ || !civ.knows(other)) continue
                val memory = ai.diplomacy.memoryOf(other)
                // What each step of the weights did: the weights after it less those before, written as the change of each approach
                val steps = ArrayList<Pair<String, IntArray>>()
                ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
                val weights = try { ApproachAi.approachWeights(civ, other, memory.approach, true, rules) } finally { ApproachAi.debugSink = null }
                val manager = civ.getDiplomacyManager(other)!!
                out.append("  -> ${other.civName}: approach ${memory.approach}, weights ${weights.joinToString(" ")}; opinion ${memory.opinion}, projection ${memory.warProjection}, " +
                    "goal ${memory.warGoal}, warmonger ${memory.warmongerThreat}, strength ${ai.diplomacy.militaryStrength(other)}, target ${ai.diplomacy.targetValue(other)}, " +
                    "proximity ${DiplomacyTurn.proximity(civ, other)}, atWar ${civ.isAtWarWith(other)}, canDeclareWar ${manager.canDeclareWar()}, " +
                    "turnsToPeaceTreaty ${manager.turnsToPeaceTreaty()}, wars ${memory.numWarsFought}, madePeace ${memory.turnMadePeace}\n")
                for (i in 0 until steps.size - 1) {
                    val before = steps[i].second; val after = steps[i + 1].second
                    val delta = before.indices.map { after[it] - before[it] }
                    // a step that scales shows as a change too; steps of the same name are one section of the original
                    if (delta.any { it != 0 }) out.append("       ${steps[i].first.take(60).padEnd(60)} " + delta.joinToString(" ") { it.toString().padStart(5) } + "\n")
                }
            }
        }
        File(System.getenv("APPROACH_DUMP_OUT") ?: "approach-dump.txt").writeText(out.toString())
    }
}

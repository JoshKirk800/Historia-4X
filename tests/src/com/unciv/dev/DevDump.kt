package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.EconomicAi
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.MilitaryTargets
import com.unciv.logic.automation.strategy.NavalOperationLogic
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.unit.BuilderAssignment
import com.unciv.logic.automation.unit.BuilderDirective
import com.unciv.logic.automation.unit.BuilderDirectives
import com.unciv.logic.automation.unit.BuilderTaskingAi
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the AI of a saved game thinks, for a developer: DEV_DUMP_SAVE (e.g. the `.checkpoint.save` of a [FullGameSim] run) is read and
 * DEV_DUMP_OUT written, by the modes in DEV_DUMP_MODE (comma-separated, default `approach`):
 * - `approach`: for every ordered pair of living major civilizations that have met, the weights of [ApproachAi.approachWeights] (before
 *   the random part), each step's change, and the values that feed them (grand strategy, domination progress, opinion, war projection
 *   and goal, warmonger threat, military strength, proximity, whether war could be declared);
 * - `operations`: every operation of every civilization: type, state, enemy, age, target and muster, its armies with their units;
 * - `nukes`: every nuclear weapon: where it stands, the enemy cities in its range with the value of a blast there
 *   ([NavalOperationLogic.nukeCityValue]);
 * - `military`: per major, its unit supply, the soft supply cap, the recommended army and navy with the units it has, the defense
 *   states and next turn's gold.
 * - `builders`: per civilization with cities, this turn's builder plan ([BuilderTaskingAi.directives], the first DEV_DUMP_BUILDER_LIMIT, default 60):
 *   type, build, tile, whether it can be built now, the score parts (base, potential, unused yield, penalty), the priority score, the weight
 *   without time; then, for each builder of the civilization, the directive it would pick with its weight, build and move turns.
 * A developer tool, not a test: it does nothing unless DEV_DUMP_SAVE is set. Run it with tools/dev_dump.py.
 */
@RunWith(GdxTestRunner::class)
class DevDump {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set DEV_DUMP_SAVE to run", System.getenv("DEV_DUMP_SAVE") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController()
            RulesetCache.loadRulesets(noMods = true)
        }
    }

    @Test
    fun dump() {
        val game = UncivFiles.gameInfoFromString(File(System.getenv("DEV_DUMP_SAVE")).readText())
        UncivGame.Current.gameInfo = game
        val out = StringBuilder()
        for (mode in (System.getenv("DEV_DUMP_MODE") ?: "approach").split(",").map { it.trim() }) {
            when (mode) {
                "approach" -> approach(game, out)
                "operations" -> operations(game, out)
                "nukes" -> nukes(game, out)
                "targets" -> targets(game, out)
                "military" -> military(game, out)
                "builders" -> builders(game, out)
                else -> out.append("unknown mode $mode (approach, operations, nukes, targets, military, builders)\n")
            }
            out.append("\n")
        }
        File(System.getenv("DEV_DUMP_OUT") ?: "dev-dump.txt").writeText(out.toString())
    }

    private fun approach(game: GameInfo, out: StringBuilder) {
        val rules = game.ruleset.ai
        out.append("turn ${game.turns}; weights by approach: ${MajorCivApproach.entries.joinToString(" ")}\n")
        val majors = game.civilizations.filter { it.isMajorCiv && it.isAlive() && it.cities.isNotEmpty() }
        for (civ in majors) {
            val ai = civ.ai
            out.append("\n${civ.civName}: grand strategy ${ai.grandStrategy}, priorities ${ai.grandStrategyPriorities}; domination progress ${VictoryProgressAi.dominationVictoryProgress(civ)}; nuclear weapons ${civ.units.getCivUnits().count { it.isNuclearWeapon() }}, military units ${civ.units.getCivUnits().count { it.isMilitary() }}\n")
            for (other in majors) {
                if (other == civ || !civ.knows(other)) continue
                val memory = ai.diplomacy.memoryOf(other)
                // What each step of the weights did: the weights after it less those before, written as the change of each approach
                val steps = ArrayList<Pair<String, IntArray>>()
                ApproachAi.debugSink = { name, w -> steps.add(name to w.copyOf()) }
                val weights = try { ApproachAi.approachWeights(civ, other, memory.approach, rules) } finally { ApproachAi.debugSink = null }
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
    }

    private fun operations(game: GameInfo, out: StringBuilder) {
        out.append("operations at turn ${game.turns}\n")
        for (civ in game.civilizations.filter { it.isAlive() && it.ai.operations.operations.isNotEmpty() }) {
            out.append("\n${civ.civName}: ${civ.ai.operations.operations.size} operations\n")
            for (op in civ.ai.operations.operations) {
                val reason = if (op.abortReason == OperationAbortReason.NONE) "" else " (${op.abortReason})"
                out.append("  #${op.id} ${op.type} ${op.state}$reason against ${op.enemyCivId ?: "-"}, ${game.turns - op.createdTurn} turns old, " +
                    "target ${op.targetTile} ${AiOperationLogic.targetTile(civ, op)?.getCity()?.name ?: ""}, muster ${op.musterTile}, " +
                    "slots to build ${op.slotsToBuild.size}\n")
                for (army in op.armies) {
                    val units = AiOperationLogic.armyUnits(civ, army).joinToString { (_, unit) -> "${unit.name} ${unit.getTile().position} ${unit.health}hp" }
                    out.append("    army ${army.id} ${army.formation} ${army.state}, ${army.numSlotsFilled()}/${army.slots.size} filled, " +
                        "at ${army.position}, goal ${army.goalTile}: $units\n")
                }
            }
        }
    }

    /** Per major: supply, soft supply cap, recommended army and navy against the units it has, defense states, gold; its attack targets and exposed cities */
    private fun military(game: GameInfo, out: StringBuilder) {
        out.append("army sizes at turn ${game.turns}\n")
        for (civ in game.civilizations.filter { it.isMajorCiv && it.isAlive() }) {
            val military = civ.ai.military
            military.updateAttackTargets(civ)
            military.updateBaseData(civ)
            military.updateDefenseState(civ)
            out.append("${civ.civName}: cities ${civ.cities.size}, units ${civ.units.getCivUnitsSize()} (${civ.units.getNumUnitsToSupply()} use supply) of supply ${civ.stats.getUnitSupply()}, " +
                "soft cap ${EconomicAi.softSupplyCap(civ)}; land ${military.numLandUnits} of ${military.recommendedLandArmySize} (${military.landDefenseState}), " +
                "navy ${military.numNavalUnits} of ${military.recommendedNavySize} (${military.navalDefenseState}), air ${military.numAirUnits}, " +
                "explorers ${military.recommendedExplorers}; gold ${civ.gold} (${"%+d".format(civ.stats.statsForNextTurn.gold.toInt())}), " +
                "unit upkeep ${civ.stats.getStatMapForNextTurn()["Unit upkeep"]?.gold?.toInt()}\n")
            for (target in military.potentialAttackTargets)
                out.append("  target ${target.targetCity.name} (${target.targetCity.civ.civName}) from ${target.musterCity.name}: ${target.armyType}, " +
                    "approach ${target.approachScore}, path ${target.pathLength}${if (target.preferred) ", preferred" else ""}\n")
            for (exposed in military.exposedCities)
                out.append("  exposed ${exposed.targetCity.name} to ${exposed.musterCity.civ.civName} from ${exposed.musterCity.name}: ${exposed.armyType}\n")
        }
    }

    /** Per civilization: this turn's builder directives, and the picks of the assignment pass of its builders in the order made (a fresh plan, nothing executed) */
    private fun builders(game: GameInfo, out: StringBuilder) {
        val limit = System.getenv("DEV_DUMP_BUILDER_LIMIT")?.toIntOrNull() ?: 60
        out.append("builder directives at turn ${game.turns}\n")
        for (civ in game.civilizations.filter { it.isAlive() && it.cities.isNotEmpty() }) {
            val builders = civ.units.getCivUnits()
                .filter { it.cache.hasUniqueToBuildImprovements || it.getMatchingUniques(UniqueType.ConstructImprovementInstantly).any() }.toList()
            val ai = BuilderTaskingAi(civ)
            out.append("\n${civ.civName}: ${ai.directives.size} directives, ${builders.size} builders, " +
                "improved tiles ${civ.cities.sumOf { city -> city.getTiles().count { it.improvement != null } }} of ${civ.cities.sumOf { it.getTiles().count() }}\n")
            val routes = ai.routes.plannedRoutes()
            out.append("  planned routes: ${routes.size} (${routes.groupingBy { it.route }.eachCount()}), ${routes.sumOf { ai.routes.plotsOf(it).size }} plots, " +
                "${ai.directives.count { it.build?.isRoute == true }} build directives, ${ai.directives.count { it.type == BuilderDirective.Type.RepairRoute }} repairs\n")
            for (route in routes.take(limit)) out.append("    ${route.route} ${route.first.position} - ${route.second.position}: ${ai.routes.purposesOf(route)}, " +
                "additive ${ai.routes.additiveValueOf(route)}, non-additive ${ai.routes.nonAdditiveValueOf(route)}, ${ai.routes.plotsOf(route).size} plots\n")
            for (directive in ai.directives.take(limit)) {
                val score = directive.plotBuildScore
                out.append("  ${directive.type} ${directive.build?.improvement?.name ?: "-"}${directive.build?.removeFirst?.let { " after ${it.name}" } ?: ""} " +
                    "at ${directive.tile.position}${if (directive.canBuild) "" else " (not yet)"}${if (directive.isGreatPerson) " (Great Person)" else ""}: " +
                    "score ${score.score} potential ${score.potentialScore} unused ${score.unusedYieldScore} penalty ${score.scorePenalty}, " +
                    "priority ${directive.priorityScore}, weight ${BuilderDirectives.directiveWeight(directive, 0, 0)}\n")
            }
            val picks = BuilderAssignment(civ, ai).assign(builders.filter { it.hasMovement() })
            for (pick in picks) out.append("  pick ${pick.unit.name} at ${pick.unit.getTile().position}: ${pick.directive.type} ${pick.build.improvement.name} at ${pick.tile.position}, " +
                "weight ${pick.weight}, build turns ${pick.buildTurns}, move turns ${pick.moveTurns}\n")
            for (unit in builders) if (picks.none { it.unit == unit }) out.append("  builder ${unit.name} at ${unit.getTile().position}: no directive\n")
        }
    }

    /** For each AI with a war approach towards a major it is not at war with: the sneak attack it would request and whether it is ready */
    private fun targets(game: GameInfo, out: StringBuilder) {
        out.append("sneak attack targets at turn ${game.turns}\n")
        for (civ in game.civilizations.filter { it.isMajorCiv && it.isAlive() && !it.isHuman() }) {
            for (other in game.civilizations.filter { it.isMajorCiv && it.isAlive() && it != civ && civ.knows(it) }) {
                if (civ.ai.diplomacy.approachTowards(other) != MajorCivApproach.War || civ.isAtWarWith(other)) continue
                val found = MilitaryTargets.findBestAttackTarget(civ, AiOperationType.SNEAK_CITY_ATTACK, other)
                val explored = other.cities.count { it.getCenterTile().isExplored(civ) }
                out.append("${civ.civName} -> ${other.civName}: their cities ${other.cities.size}, explored $explored; ")
                if (found == null) { out.append("no target\n"); continue }
                val (target, score) = found
                val formation = if (target.attackBySea) "MUFORMATION_NAVAL_INVASION" else AiOperationLogic.formationName(civ, AiOperationType.BASIC_CITY_ATTACK)
                val type = if (target.attackBySea) AiOperationType.NAVAL_SNEAK_ATTACK else AiOperationType.SNEAK_CITY_ATTACK
                out.append("target ${target.targetCity.name} from ${target.musterCity.name}, by sea ${target.attackBySea}, path ${target.pathLength}, " +
                    "score $score, ready ${MilitaryOperations.isAttackReady(civ, formation, type)}, operations ${civ.ai.operations.operations.map { it.type }}\n")
            }
        }
    }

    private fun nukes(game: GameInfo, out: StringBuilder) {
        out.append("nuclear weapons at turn ${game.turns}\n")
        for (civ in game.civilizations.filter { it.isAlive() }) {
            val nukes = civ.units.getCivUnits().filter { it.isNuclearWeapon() }.toList()
            if (nukes.isEmpty()) continue
            val enemies = game.civilizations.filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
            val enemyCities = enemies.flatMap { it.cities }.filter { it.getCenterTile().isExplored(civ) }
            out.append("\n${civ.civName}: ${nukes.size} nuclear weapons, at war with ${enemies.joinToString { it.civName }}\n")
            for (nuke in nukes) {
                val tile = nuke.getTile()
                out.append("  ${nuke.name} at ${tile.position} (${tile.getCity()?.name ?: "not in a city"}), range ${nuke.getRange()}, " +
                    "movement ${nuke.currentMovement}\n")
                for (city in enemyCities.filter { tile.aerialDistanceTo(it.getCenterTile()) <= nuke.getRange() })
                    out.append("    ${city.civ.civName} ${city.name} at ${tile.aerialDistanceTo(city.getCenterTile())}: " +
                        "blast value ${NavalOperationLogic.nukeCityValue(civ, nuke, city)}\n")
            }
        }
    }
}

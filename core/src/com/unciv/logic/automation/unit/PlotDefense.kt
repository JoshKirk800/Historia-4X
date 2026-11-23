package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.ApproachAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.roundToInt

/** CvBuilderTaskingAI.cpp: Likelyhood. How likely it is that a neighbour takes a plot of ours (by tile theft or culture bomb) */
enum class TheftLikelihood { Impossible, Unlikely, Possible, Likely, Certain }

/** A nation's combat bonus improvement (CvPlayerTraits: CombatBonusImprovement, NearbyImprovementBonusRange, NearbyImprovementCombatBonus) */
class CombatBonusImprovement(val improvement: TileImprovement, val range: Int, val bonus: Int)

/**
 *  What the original keeps on CvPlot for the builder and tactical AIs about how threatened a plot is (Vox Populi), and the builder AI's
 *  reading of an improvement's defense, culture bomb and combat bonus.
 */
object PlotDefense {
    /** CvPlot.cpp: GetDefensiveApproachMultiplierTimes100 (static) */
    private fun defensiveApproachTimes100(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.Friendly -> 0
        MajorCivApproach.Neutral -> 100
        MajorCivApproach.Afraid, MajorCivApproach.Guarded -> 200
        MajorCivApproach.Deceptive, MajorCivApproach.Hostile, MajorCivApproach.War -> 150
    }

    /** CvPlot.cpp: GetDefensiveStrengthMultiplierTimes100 (static); 0 for a strength that is not known yet */
    private fun defensiveStrengthTimes100(strength: StrengthLevel?) = when (strength) {
        StrengthLevel.Pathetic -> 10
        StrengthLevel.Weak -> 25
        StrengthLevel.Poor -> 50
        StrengthLevel.Average -> 100
        StrengthLevel.Strong -> 125
        StrengthLevel.Powerful -> 150
        StrengthLevel.Immense -> 200
        null -> 0
    }

    /** CvPlot.cpp: GetOffensiveApproachMultiplierTimes100 (static) */
    private fun offensiveApproachTimes100(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.Friendly, MajorCivApproach.Afraid -> 0
        MajorCivApproach.Neutral -> 50
        MajorCivApproach.Guarded -> 100
        MajorCivApproach.Deceptive -> 150
        MajorCivApproach.Hostile, MajorCivApproach.War -> 200
    }

    /** CvPlot.cpp: GetOffensiveStrengthMultiplierTimes100 (static); 0 for a strength that is not known yet */
    private fun offensiveStrengthTimes100(strength: StrengthLevel?) = when (strength) {
        StrengthLevel.Pathetic -> 200
        StrengthLevel.Weak -> 150
        StrengthLevel.Poor -> 125
        StrengthLevel.Average -> 100
        StrengthLevel.Strong -> 50
        StrengthLevel.Powerful -> 25
        StrengthLevel.Immense -> 10
        null -> 0
    }

    /**
     *  CvPlot::GetStrategicValue: how much [civ] has to fear or to gain from the major civilizations around [tile], 0 to 400. Of every land plot
     *  within two plots (the plot itself excluded) that [civ] has seen, that can be walked on and that another major owns, the owner's
     *  defensive multiplier (our approach towards it × its military strength compared to ours) and its offensive multiplier
     *  (the same with the inverse strength table) are taken at their maximum over those plots; the result is the larger of the two maxima.
     *
     *  Unciv mapping: the approach is [com.unciv.logic.automation.diplomacy.DiplomacyAi.approachTowards] (CvDiplomacyAI::GetCivApproach) and the
     *  strength [WarStateAi.militaryStrengthComparedToUs] (GetMilitaryStrengthComparedToUs); a plot is walkable when it is not impassable
     *  (the original's isValidMovePlot of the plot's owner, whose route-over-impassable rule is none here).
     */
    fun strategicValue(civ: Civilization, tile: Tile): Int {
        var nearbyThreat = 0
        var nearbyTarget = 0
        tile.forEachTileInDistanceRange(1..2) { nearby ->
            if (!nearby.isExplored(civ)) return@forEachTileInDistanceRange
            if (nearby.isImpassible() || nearby.isWater) return@forEachTileInDistanceRange
            val owner = nearby.getOwner() ?: return@forEachTileInDistanceRange
            if (owner == civ || !owner.isMajorCiv) return@forEachTileInDistanceRange

            val approach = civ.ai.diplomacy.approachTowards(owner)
            val strength = WarStateAi.militaryStrengthComparedToUs(civ, owner)
            nearbyThreat = maxOf(nearbyThreat, defensiveApproachTimes100(approach) * defensiveStrengthTimes100(strength) / 100)
            nearbyTarget = maxOf(nearbyTarget, offensiveApproachTimes100(approach) * offensiveStrengthTimes100(strength) / 100)
        }
        return maxOf(nearbyThreat, nearbyTarget)
    }

    //region Reading an improvement

    /** CvImprovementEntry::GetDefenseModifier: the improvement's "Gives a defensive bonus of [x]%" */
    fun defenseModifier(improvement: TileImprovement) =
        improvement.getMatchingUniques(UniqueType.DefensiveBonus).sumOf { it.params[0].toInt() }

    /** CvImprovementEntry::GetNearbyEnemyDamage: the improvement's "Adjacent enemy units ending their turn take [x] damage" */
    fun nearbyEnemyDamage(improvement: TileImprovement) =
        improvement.getMatchingUniques(UniqueType.DamagesAdjacentEnemyUnits).sumOf { it.params[0].toInt() }

    /**
     *  ENEMY_HEAL_RATE of the tactical analysis: what a unit heals in a turn in enemy territory, which the damage of a citadel must beat to count as one
     *  (TacticalAIHelpers::IsPlayerCitadel). It is the rate the units really heal at, [com.unciv.models.ModConstants.enemyUnitHeal] with `unitHealingLikeVp`
     *  (5 in Vox Populi), else the 10 of Brave New World.
     */
    fun enemyHealRate(civ: Civilization): Int =
        civ.gameInfo.ruleset.modOptions.constants.let { if (it.unitHealingLikeVp) it.enemyUnitHeal else BNW_ENEMY_HEAL_RATE }

    private const val BNW_ENEMY_HEAL_RATE = 10

    /** CvImprovementEntry::GetCultureBombRadius: the largest radius of the improvement's "Gain control over [tiles] tiles in a [r]-tile radius" */
    fun cultureBombRadius(improvement: TileImprovement) =
        improvement.getMatchingUniques(UniqueType.OneTimeTakeOverTilesInRadius).maxOfOrNull { it.params[1].toInt() } ?: 0

    /** ScorePlotBuild's bNewIsDefensive: the improvement defends its plot or hurts the enemies next to it */
    fun isDefensive(improvement: TileImprovement) = defenseModifier(improvement) > 0 || nearbyEnemyDamage(improvement) > 0

    /**
     *  CvPlayerTraits::GetCombatBonusImprovementType with its range and bonus: the nation's unique "[+x]% Strength <within [r] tiles of a
     *  [improvement]>" (Polynesia's Moai); null for a nation without one. The first such unique counts, as the original has one improvement per player.
     */
    fun combatBonusImprovement(civ: Civilization): CombatBonusImprovement? {
        val improvements = civ.gameInfo.ruleset.tileImprovements
        for (unique in civ.nation.uniqueObjects) {
            if (unique.type != UniqueType.Strength) continue
            val near = unique.modifiers.firstOrNull { it.type == UniqueType.ConditionalNearTiles } ?: continue
            val improvement = improvements[near.params[1]] ?: continue
            return CombatBonusImprovement(improvement, near.params[0].toInt(), unique.params[0].toInt())
        }
        return null
    }

    //endregion
    //region Theft

    /**
     *  CvBuilderTaskingAI.cpp: GetPlotTheftLikelyhood: how likely a neighbour is to take [tile] from [civ]. Impossible when none of the six plots
     *  around is somebody else's or nobody's (water counts); otherwise by the worst visible approach towards [civ] ([ApproachAi.VP_ORDER]
     *  from War to Friendly) of the majors that own a plot within two plots, those afraid of us left out: War or Hostile certain, Guarded likely,
     *  Neutral possible, any other unlikely.
     *
     *  Unciv mapping: CvDiplomacyAI::GetVisibleApproachTowardsUs(owner), the owner's surface approach towards us, is
     *  [com.unciv.logic.automation.diplomacy.DiplomacyAi.approachTowards] of the owner with hideTrueFeelings. Barbarians and city-states are not majors.
     */
    fun theftLikelihood(civ: Civilization, tile: Tile): TheftLikelihood {
        var anyAdjacentUnowned = false
        var worstApproach = MajorCivApproach.Friendly
        for (ring in 1..2) {
            if (ring == 2 && !anyAdjacentUnowned) return TheftLikelihood.Impossible
            for (around in tile.getTilesAtDistance(ring)) {
                val owner = around.getOwner()
                if (owner == civ) continue
                if (ring == 1) anyAdjacentUnowned = true
                if (owner == null || !owner.isMajorCiv) continue
                val approach = owner.ai.diplomacy.approachTowards(civ, hideTrueFeelings = true)
                // Civs who are afraid of us are unlikely to steal, so they count as friendly
                if (approach != MajorCivApproach.Afraid && ApproachAi.VP_ORDER.indexOf(approach) < ApproachAi.VP_ORDER.indexOf(worstApproach))
                    worstApproach = approach
            }
        }
        return when (worstApproach) {
            MajorCivApproach.War, MajorCivApproach.Hostile -> TheftLikelihood.Certain
            MajorCivApproach.Guarded -> TheftLikelihood.Likely
            MajorCivApproach.Neutral -> TheftLikelihood.Possible
            else -> TheftLikelihood.Unlikely
        }
    }

    //endregion
    //region Defense build value

    /**
     *  CvPlot::GetDefenseBuildValue: what [improvement] built by [build] (null: the improvement stands; a repair counts as standing) is worth as a
     *  defense of [tile] for [civ], in hundredths of the original's units, 0 when it adds nothing. Over the two rings around the tile (land that can
     *  be walked on; water is an easy avenue for nobody and skipped):
     *  - the largest threat of the majors that own land there ([defensiveApproachTimes100] of our approach towards them × [defensiveStrengthTimes100] of
     *    their strength compared to ours), land owned by another major counting as ours where the improvement's culture bomb takes it,
     *  - our own plots next to the tile (the exposure term: 70 for each), the damage the improvement adds next to it (30 of a Citadel per plot that no
     *    improvement hurts yet), the structures with a defensive bonus within two plots (−50 each) and a city of ours within two (the original's
     *    150 × (3 − distance): −300 next to the tile, −150 two plots away).
     *  A new improvement needs a threat and at least three plots of another major within two plots; an existing or started one needs neither, and treats
     *  land of nobody and of another major as unsafe whoever we like (our approach at most Guarded, their strength at least Strong).
     *  The value is `threat × (2 × defense + damage / 2) / 100`, less the structure and city terms, × 70 × our plots next to the tile / 100:
     *  at most 1680 for a Fort (50% defense) and 4872 for a Citadel (100% and 30 damage).
     *
     *  Unciv mapping: the plot's defense is [Tile.getDefensiveBonus] without the improvement (the original's defenseModifier ignoring the improvement,
     *  hills + feature + terrain), without the feature when the build removes it; the bonus and damage are the improvement's uniques; started =
     *  Unciv's improvement in progress on the tile; plots planned by other builders come from [state] (the original's mChangedPlotImprovements; a plot
     *  without a plan has the improvement it has, pillaged or not). None: IsStealBlockedByImprovement (BlockTileSteal is not in our rules).
     */
    fun defenseBuildValue(
        civ: Civilization,
        tile: Tile,
        build: Build?,
        improvement: TileImprovement?,
        state: SBuilderState = SBuilderState.Default
    ): Int {
        if (improvement == null) return 0
        val existingImprovement = build == null || build.isRepair
        val improvementDefense = defenseModifier(improvement)
        val improvementDamage = nearbyEnemyDamage(improvement)
        val cultureBombRadius = cultureBombRadius(improvement)
        val keep = existingImprovement || (build != null && isStarted(tile, build))
        val guardedIndex = ApproachAi.VP_ORDER.indexOf(MajorCivApproach.Guarded)

        var maxAdjacentThreat = 0
        var nearestOwnedCityDistance = 0
        var adjacentOtherPlayerLand = 0
        var adjacentOwnedLand = 0
        var nearbyDefensiveStructures = 0
        var totalAdjacentDamage = 0

        for (ring in 1..2) for (adjacent in tile.getTilesAtDistance(ring)) {
            // Impassable is uninteresting, and coasts are easy avenues of attack
            if (adjacent.isImpassible() || adjacent.isWater) continue
            val owner = adjacent.getOwner()
            if (owner == civ) {
                if (ring == 1) adjacentOwnedLand++
                // Avoid building next to cities
                if (adjacent.isCityCenter()) nearestOwnedCityDistance = ring

                if (improvementDamage > 0 && ring == 1) {
                    var maxDamageAlreadyInPlot = 0
                    for (second in adjacent.neighbors) {
                        if (second == tile || second.getOwner() != civ) continue
                        // If we are not planning on building an improvement here, use the one that exists already
                        val secondImprovement = state.changedPlotImprovements[second] ?: second.tileImprovement ?: continue
                        maxDamageAlreadyInPlot = max(maxDamageAlreadyInPlot, nearbyEnemyDamage(secondImprovement))
                    }
                    if (improvementDamage > maxDamageAlreadyInPlot) totalAdjacentDamage += improvementDamage - maxDamageAlreadyInPlot
                }

                val adjacentImprovement = state.changedPlotImprovements[adjacent] ?: adjacent.tileImprovement
                if (adjacentImprovement != null && defenseModifier(adjacentImprovement) > 0) nearbyDefensiveStructures++
            } else if (owner != null && owner.isMajorCiv) {
                if (cultureBombRadius >= ring && !existingImprovement && !adjacent.isCityCenter()) adjacentOwnedLand++
                else adjacentOtherPlayerLand++

                var approach = civ.ai.diplomacy.approachTowards(owner)
                var strength = WarStateAi.militaryStrengthComparedToUs(civ, owner)
                // For existing improvements or currently in progress, assume neighboring land not owned by us is unsafe (even if it's currently
                // owned by our friends): this mitigates possibly inconsistent neighbor approach values
                if (keep) {
                    if (ApproachAi.VP_ORDER.indexOf(approach) > guardedIndex) approach = MajorCivApproach.Guarded
                    if (strength == null || strength < StrengthLevel.Strong) strength = StrengthLevel.Strong
                }
                maxAdjacentThreat = max(maxAdjacentThreat, defensiveApproachTimes100(approach) * defensiveStrengthTimes100(strength) / 100)
            } else if (keep && owner == null) {
                maxAdjacentThreat = max(maxAdjacentThreat,
                    defensiveApproachTimes100(MajorCivApproach.Guarded) * defensiveStrengthTimes100(StrengthLevel.Strong) / 100)
            }
        }

        // No defensive utility from building here
        if (!existingImprovement && (maxAdjacentThreat == 0 || adjacentOtherPlayerLand < 3)) return 0

        val removedFeatures = if (existingImprovement) emptyList() else featuresRemoved(tile, build, improvement)
        val defenseModifier = plotDefenseModifier(tile, removedFeatures) + improvementDefense

        // range of maxAdjacentThreat is [0,400]
        var defensiveValueTimes100 = maxAdjacentThreat * (defenseModifier * 2 + totalAdjacentDamage / 2) / 100

        // Avoid fort spam
        defensiveValueTimes100 -= 50 * nearbyDefensiveStructures

        // Avoid building next to cities
        if (nearestOwnedCityDistance > 0) defensiveValueTimes100 -= 150 * (3 - nearestOwnedCityDistance)

        if (defensiveValueTimes100 <= 0) return 0

        // Bonus for plots that are not too exposed (range=[0,420])
        val defensibilityTimes100 = adjacentOwnedLand * 70
        return defensiveValueTimes100 * defensibilityTimes100 / 100
    }

    /** CvPlot::getBuildProgress(build) > 0: the tile's improvement in progress is the build's (or the removal of the feature that comes first) */
    private fun isStarted(tile: Tile, build: Build) =
        tile.turnsToImprovement > 0 && (tile.improvementInProgress == build.improvement.name || tile.improvementInProgress == build.removeFirst?.name)

    /**
     *  The features that [improvement] removes from [tile] when built by [build]: the removal that comes first and, for "Removes removable features
     *  when built", the features a worker can remove and the improvement cannot be built on.
     */
    private fun featuresRemoved(tile: Tile, build: Build?, improvement: TileImprovement): List<String> {
        val removed = ArrayList<String>()
        build?.removeFirst?.let { removed.add(it.name.removePrefix(Constants.remove)) }
        if (improvement.hasUnique(UniqueType.RemovesFeaturesIfBuilt)) for (feature in tile.terrainFeatureObjects)
            if (Constants.remove + feature.name in tile.ruleset.tileImprovements && !improvement.isAllowedOnFeature(feature)) removed.add(feature.name)
        return removed
    }

    /**
     *  CvPlot::defenseModifier(team, bIgnoreImprovement = true, bIgnoreFeature): the defense of the plot's terrain in percent, as Unciv has it
     *  ([Tile.getDefensiveBonus] without the improvement: hills and a feature are features with a defenceBonus, the best of them replacing the
     *  base terrain's), with the [removedFeatures] gone: the original ignores a feature that the build removes.
     */
    private fun plotDefenseModifier(tile: Tile, removedFeatures: List<String>): Int {
        val terrain = if (removedFeatures.isEmpty()) tile else tile.clone(addUnits = false).also {
            it.setTerrainTransients()
            for (feature in removedFeatures) it.removeTerrainFeature(feature)
        }
        return (terrain.getDefensiveBonus(includeImprovementBonus = false) * 100).roundToInt()
    }

    //endregion
}

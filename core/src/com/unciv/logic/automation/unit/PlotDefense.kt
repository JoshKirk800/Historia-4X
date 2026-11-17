package com.unciv.logic.automation.unit

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  What the original keeps on CvPlot for the builder and tactical AIs about how threatened a plot is (Vox Populi).
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
}

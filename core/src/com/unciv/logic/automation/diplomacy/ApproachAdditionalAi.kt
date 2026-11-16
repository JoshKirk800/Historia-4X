package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unit.BaseUnit

/**
 *  Part 7 of Vox Populi's SelectBestApproachTowardsMajorCiv (Release-5.4.8; D7), ADDITIONAL FACTORS: UNIQUE UNITS and EMPIRE
 *  CRAMPED, with VP's unique unit state (CvPlayer::HasUUPeriod and HasUUActive, CvPlayerTechs::HasUUTech and WillHaveUUTechSoon).
 *  On our scale: VP's personality bias is ours x 100. VP counts combat and combat support units (culture bombs, fleet repair, city
 *  attack support): BNW's unique units of the kind are all combat units. VP keeps "soon" until the unit's tech is in; here it is
 *  asked each turn.
 */
object ApproachAdditionalAi {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal

    /** The unique units VP's unique unit state counts: combat units that are not scouts, in the ruleset's order */
    private fun uniqueUnits(civ: Civilization, rules: AiRuleset): List<BaseUnit> = civ.gameInfo.ruleset.units.values.filter {
        it.uniqueTo == civ.civName && (it.strength > 0 || it.rangedStrength > 0) &&
            UnitAiTypes.defaultOf(it, rules).let { ai -> ai != UnitAiType.EXPLORE && ai != UnitAiType.EXPLORE_SEA }
    }

    private fun isObsolete(civ: Civilization, unit: BaseUnit) = unit.obsoleteTech?.let { civ.tech.isResearched(it) } == true

    /** CvPlayer::SetHasUUPeriod: our first such unique unit is not obsolete yet */
    fun hasUniqueUnitPeriod(civ: Civilization, rules: AiRuleset): Boolean =
        uniqueUnits(civ, rules).firstOrNull()?.let { !isObsolete(civ, it) } == true

    /** CvPlayerTechs::CheckHasUUTech: one of our such unique units can be built (its tech researched, not obsolete) */
    fun hasUniqueUnitTech(civ: Civilization, rules: AiRuleset): Boolean = uniqueUnits(civ, rules).any { unit ->
        (unit.requiredTech == null || civ.tech.isResearched(unit.requiredTech!!)) && !isObsolete(civ, unit)
    }

    /** CvPlayerTechs::CheckWillHaveUUTechSoon: we are researching the tech of such a unique unit, or a prerequisite of it */
    fun willHaveUniqueUnitTechSoon(civ: Civilization, rules: AiRuleset): Boolean {
        if (hasUniqueUnitTech(civ, rules)) return false
        val researching = civ.tech.currentTechnologyName() ?: return false
        return uniqueUnits(civ, rules).any { unit ->
            val tech = unit.requiredTech ?: return@any false
            if (civ.tech.isResearched(tech)) return@any false
            tech == researching || civ.gameInfo.ruleset.technologies[tech]?.prerequisites?.contains(researching) == true
        }
    }

    /** CvPlayer::HasUUActive: in the unique unit period, we own one of our unique units that is not a scout */
    fun hasUniqueUnitActive(civ: Civilization, rules: AiRuleset): Boolean {
        if (!hasUniqueUnitPeriod(civ, rules)) return false
        return civ.units.getCivUnits().any { unit ->
            unit.baseUnit.uniqueTo == civ.civName &&
                UnitAiTypes.defaultOf(unit.baseUnit, rules).let { it != UnitAiType.EXPLORE && it != UnitAiType.EXPLORE_SEA }
        }
    }

    /**
     *  UNIQUE UNITS: in our unique unit period, with the unit's tech and a unit in the field, war and hostility 3 x bias (5 x more
     *  going for or close to world conquest, war 2 x more against an easy target); with the tech but no unit yet, deception 5 x and
     *  war 2 x (hurry up); with the tech coming soon, deception 2 x (delay). EMPIRE CRAMPED: neighbours to a cramped empire draw war
     *  (3 x, when we are their neighbour) and guard (3 x, when they are ours).
     */
    fun addUniqueUnitAndCrampedWeights(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (hasUniqueUnitPeriod(civ, rules)) {
            val tech = hasUniqueUnitTech(civ, rules)
            val active = hasUniqueUnitActive(civ, rules)
            if (tech && active) {
                w[WAR] += bias(MajorCivApproach.War) * 3
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * 3
                if (civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules)) {
                    w[WAR] += bias(MajorCivApproach.War) * 5
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5
                }
                if (WarStateAi.isEasyTarget(civ, other)) w[WAR] += bias(MajorCivApproach.War) * 2
            } else if (tech) {
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * 5
                w[WAR] += bias(MajorCivApproach.War) * 2
            } else if (willHaveUniqueUnitTechSoon(civ, rules)) {
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * 2
            }
        }
        val ours = DiplomacyTurn.proximity(civ, other)
        val theirs = DiplomacyTurn.proximity(other, civ)
        if (civ.ai.isCramped && maxOf(ours, theirs) == DiplomacyTurn.PROXIMITY_NEIGHBORS) {
            if (ours == DiplomacyTurn.PROXIMITY_NEIGHBORS) w[WAR] += bias(MajorCivApproach.War) * 3
            if (theirs == DiplomacyTurn.PROXIMITY_NEIGHBORS) w[GUARDED] += bias(MajorCivApproach.Guarded) * 3
        }
    }
}

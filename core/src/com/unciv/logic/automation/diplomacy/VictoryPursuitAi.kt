package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.ai.VictoryPursuit
import kotlin.math.max

/**
 *  Vox Populi's victory pursuits (CvDiplomacyAI::SelectDefaultVictoryPursuits, DoUpdateCurrentVictoryPursuit, IsSeriousAboutVictory;
 *  Release-5.4.8, roadmap D7): the victory a leader leans towards for the whole game (primary, maybe a secondary), picked once with its
 *  personality, and the one it goes for this turn (current), which [com.unciv.logic.automation.strategy.CivAi.isGoingForWorldConquest]
 *  and the other IsGoingFor* read.
 *
 *  Unciv has no game options that switch science, policies, the World Congress or war and peace off, so all four pursuits are always
 *  possible: VP's branches for fewer valid pursuits and for the always-war options do not apply. No leader of the ruleset has VP's -12
 *  personality overrides (GetEternalVictoryPursuit), and VP's option that limits the randomization is off by default.
 */
object VictoryPursuitAi {

    private fun Civilization.define(define: AiDefine) = gameInfo.ruleset.ai.define(define)

    /** CvGame::isVictoryValid for the victory a pursuit leads to, by the milestones of the victories open to [civ] */
    fun isVictoryEnabled(civ: Civilization, pursuit: VictoryPursuit): Boolean = when (pursuit) {
        VictoryPursuit.Domination -> VictoryProgressAi.canAttemptDominationVictory(civ)
        VictoryPursuit.Diplomacy -> VictoryProgressAi.isVictoryOpen(civ, MilestoneType.WinDiplomaticVote)
        VictoryPursuit.Culture -> VictoryProgressAi.isVictoryOpen(civ, MilestoneType.InfluentialOverAllCivs)
            || VictoryProgressAi.isVictoryOpen(civ, MilestoneType.CompletePolicyBranches)
        VictoryPursuit.Science -> VictoryProgressAi.isVictoryOpen(civ, MilestoneType.AddedSSPartsInCapital)
    }

    /** The leader's PrimaryVictoryPursuit ([rank] 1) or SecondaryVictoryPursuit (2) hint, null if it has none */
    private fun hint(civ: Civilization, rank: Int): VictoryPursuit? {
        val personality = civ.nation.personality?.let { civ.gameInfo.ruleset.personalities[it] } ?: return null
        val name = personality.victoryPursuits.entries.firstOrNull { it.value == rank }?.key ?: return null
        return VictoryPursuit.entries.firstOrNull { it.name == name }
    }

    /** The trait category that goes with a pursuit: warmonger, diplomat, tourism, nerd */
    private fun traitOf(pursuit: VictoryPursuit) = when (pursuit) {
        VictoryPursuit.Domination -> TraitCategory.Warmonger
        VictoryPursuit.Diplomacy -> TraitCategory.Diplomat
        VictoryPursuit.Culture -> TraitCategory.Tourism
        VictoryPursuit.Science -> TraitCategory.Nerd
    }

    /**
     *  CvDiplomacyAI::SelectDefaultVictoryPursuits, at the personality's set-up; the current pursuit starts at the primary one (VP's Reset).
     *  A human takes the leader's hints, else the first of: the warmonger, diplomat, tourism or nerd trait's pursuit, Domination for an
     *  expansionist, Culture for a smaller civilization, Domination.
     *  An AI scores each pursuit from 10000: 2 x the difference between its approach biases (Domination: war or hostile against friendly
     *  or afraid; Diplomacy: deceptive or friendly against war or hostile; Culture: neutral or friendly against hostile or guarded; Science:
     *  neutral or guarded against war or deceptive), then personality values and flavors (Domination: 2 x boldness, 2 x meanness, offense,
     *  expansion; Diplomacy: 2 x city-state competitiveness, 2 x work-with willingness, diplomatic balance, diplomacy; Culture: 2 x DoF
     *  willingness, 2 x wonder competitiveness, wonder, culture; Science: 2 x loyalty, 2 x warmonger hate, science, growth), the flavors
     *  on the diplomacy AI's 1..10 scale. VICTORY_PURSUIT_DISABLED_VICTORY_PENALTY for a pursuit whose victory is not open; the primary
     *  hint gets VICTORY_PURSUIT_PRIMARY_ONLY_HINT_WEIGHT when it stands alone and its victory is open, else _PRIMARY_HINT_WEIGHT; the
     *  secondary hint _PRIMARY_HINT_WEIGHT when it replaces a closed primary, else _SECONDARY_HINT_WEIGHT; Diplomacy loses
     *  VICTORY_PURSUIT_FEW_CITY_STATES_PENALTY with half as many city-states as majors or fewer, half of it with fewer city-states than
     *  majors. Each score then moves by the difference of two rolls of 0..r, r = max(0, VICTORY_PURSUIT_MAX_RANDOMNESS - the lead of the
     *  best score x 100 / VICTORY_PURSUIT_DIFFERENTIAL_DIVISOR). The best is the primary pursuit; a tie goes to the primary hint, the
     *  secondary hint, the pursuit of a trait the leader has (warmonger, diplomat, tourism, nerd; then Domination or Diplomacy for an
     *  expansionist, Culture or Science for a smaller civilization), else the first. The secondary pursuit is the secondary hint when
     *  the primary hint was chosen, else the primary hint, when that victory is open; else the best other score.
     */
    fun selectDefaultVictoryPursuits(civ: Civilization, asHuman: Boolean = civ.isHuman()) {
        val ai = civ.ai
        val primaryHint = hint(civ, 1)
        val secondaryHint = hint(civ, 2)
        fun has(category: TraitCategory) = ai.hasTraitCategory(category)

        if (asHuman) {
            ai.primaryVictoryPursuit = primaryHint ?: when {
                has(TraitCategory.Warmonger) -> VictoryPursuit.Domination
                has(TraitCategory.Diplomat) -> VictoryPursuit.Diplomacy
                has(TraitCategory.Tourism) -> VictoryPursuit.Culture
                has(TraitCategory.Nerd) -> VictoryPursuit.Science
                has(TraitCategory.Expansionist) -> VictoryPursuit.Domination
                has(TraitCategory.Smaller) -> VictoryPursuit.Culture
                else -> VictoryPursuit.Domination
            }
            ai.secondaryVictoryPursuit = if (primaryHint != null) secondaryHint else null
            ai.currentVictoryPursuit = ai.primaryVictoryPursuit
            return
        }

        val scores = victoryScores(civ, primaryHint, secondaryHint)
        // StableSortItems: descending, ties in enum order
        val sorted = VictoryPursuit.entries.sortedByDescending { scores[it.ordinal] }
        val differential = scores[sorted[0].ordinal] - scores[sorted[1].ordinal]
        val randomness = max(civ.define(AiDefine.VICTORY_PURSUIT_MAX_RANDOMNESS) -
            differential * 100 / max(civ.define(AiDefine.VICTORY_PURSUIT_DIFFERENTIAL_DIVISOR), 1), 0)
        val noisy = sorted.mapIndexed { i, pursuit ->
            var score = scores[pursuit.ordinal]
            if (score != 0) {
                score += ai.random("SelectDefaultVictoryPursuits+$i", 0).nextInt(randomness + 1)
                score -= ai.random("SelectDefaultVictoryPursuits-$i", 0).nextInt(randomness + 1)
            }
            pursuit to max(score, 0)
        }.sortedByDescending { it.second }
        val highest = noisy[0].first
        val runnerUp = noisy[1].first
        val chosen = if (noisy[0].second > noisy[1].second) highest else when {
            highest == primaryHint -> highest
            runnerUp == primaryHint -> runnerUp
            highest == secondaryHint -> highest
            runnerUp == secondaryHint -> runnerUp
            has(traitOf(highest)) -> highest
            has(traitOf(runnerUp)) -> runnerUp
            (highest == VictoryPursuit.Domination || highest == VictoryPursuit.Diplomacy) && has(TraitCategory.Expansionist) -> highest
            (highest == VictoryPursuit.Culture || highest == VictoryPursuit.Science) && has(TraitCategory.Smaller) -> highest
            (runnerUp == VictoryPursuit.Domination || runnerUp == VictoryPursuit.Diplomacy) && has(TraitCategory.Expansionist) -> runnerUp
            (runnerUp == VictoryPursuit.Culture || runnerUp == VictoryPursuit.Science) && has(TraitCategory.Smaller) -> runnerUp
            else -> highest
        }
        ai.primaryVictoryPursuit = chosen
        val candidate = if (chosen == primaryHint) secondaryHint else primaryHint
        ai.secondaryVictoryPursuit = if (candidate != null && isVictoryEnabled(civ, candidate)) candidate
            else if (chosen == highest) runnerUp else highest
        ai.currentVictoryPursuit = chosen
    }

    /** The scores of [selectDefaultVictoryPursuits] before the random noise, by [VictoryPursuit.ordinal] */
    fun victoryScores(civ: Civilization, primaryHint: VictoryPursuit?, secondaryHint: VictoryPursuit?): IntArray {
        val ai = civ.ai
        fun bias(approach: MajorCivApproach) = ai.majorCivApproachBias(approach)
        fun value(value: DiplomacyPersonality) = ai.diplomacyValue(value)
        fun flavor(flavor: Flavor) = ai.personality(flavor).coerceIn(1, 10) // GetPersonalityFlavorForDiplomacy
        val scores = IntArray(VictoryPursuit.entries.size) { 10000 }
        scores[VictoryPursuit.Domination.ordinal] += 2 * (max(bias(MajorCivApproach.War), bias(MajorCivApproach.Hostile)) -
            max(bias(MajorCivApproach.Friendly), bias(MajorCivApproach.Afraid))) +
            value(DiplomacyPersonality.Boldness) * 2 + value(DiplomacyPersonality.Meanness) * 2 + flavor(Flavor.Offense) + flavor(Flavor.Expansion)
        scores[VictoryPursuit.Diplomacy.ordinal] += 2 * (max(bias(MajorCivApproach.Deceptive), bias(MajorCivApproach.Friendly)) -
            max(bias(MajorCivApproach.War), bias(MajorCivApproach.Hostile))) +
            value(DiplomacyPersonality.MinorCivCompetitiveness) * 2 + value(DiplomacyPersonality.WorkWithWillingness) * 2 +
            value(DiplomacyPersonality.DiploBalance) + flavor(Flavor.Diplomacy)
        scores[VictoryPursuit.Culture.ordinal] += 2 * (max(bias(MajorCivApproach.Neutral), bias(MajorCivApproach.Friendly)) -
            max(bias(MajorCivApproach.Hostile), bias(MajorCivApproach.Guarded))) +
            value(DiplomacyPersonality.DoFWillingness) * 2 + value(DiplomacyPersonality.WonderCompetitiveness) * 2 +
            flavor(Flavor.Wonder) + flavor(Flavor.Culture)
        scores[VictoryPursuit.Science.ordinal] += 2 * (max(bias(MajorCivApproach.Neutral), bias(MajorCivApproach.Guarded)) -
            max(bias(MajorCivApproach.War), bias(MajorCivApproach.Deceptive))) +
            value(DiplomacyPersonality.Loyalty) * 2 + value(DiplomacyPersonality.WarmongerHate) * 2 + flavor(Flavor.Science) + flavor(Flavor.Growth)

        for (pursuit in VictoryPursuit.entries)
            if (!isVictoryEnabled(civ, pursuit)) scores[pursuit.ordinal] += civ.define(AiDefine.VICTORY_PURSUIT_DISABLED_VICTORY_PENALTY)

        val primaryDisabled = primaryHint != null && !isVictoryEnabled(civ, primaryHint)
        val secondaryEnabled = secondaryHint != null && isVictoryEnabled(civ, secondaryHint)
        if (primaryHint != null) scores[primaryHint.ordinal] += civ.define(
            if (!secondaryEnabled && !primaryDisabled) AiDefine.VICTORY_PURSUIT_PRIMARY_ONLY_HINT_WEIGHT else AiDefine.VICTORY_PURSUIT_PRIMARY_HINT_WEIGHT)
        if (secondaryHint != null) scores[secondaryHint.ordinal] += civ.define(
            if (primaryDisabled && secondaryEnabled) AiDefine.VICTORY_PURSUIT_PRIMARY_HINT_WEIGHT else AiDefine.VICTORY_PURSUIT_SECONDARY_HINT_WEIGHT)

        val minors = civ.gameInfo.civilizations.count { it.isCityState }
        val majors = civ.gameInfo.civilizations.count { it.isMajorCiv }
        if (minors < majors) {
            val penalty = civ.define(AiDefine.VICTORY_PURSUIT_FEW_CITY_STATES_PENALTY)
            scores[VictoryPursuit.Diplomacy.ordinal] += if (minors <= majors / 2) penalty else penalty / 2
        }
        return scores
    }

    /**
     *  CvDiplomacyAI::DoUpdateCurrentVictoryPursuit, early in the diplomacy turn: a human keeps its primary pursuit. Otherwise the victory
     *  we are close to (science, culture, diplomacy, world conquest, in that order); else the primary pursuit unless we are serious about
     *  victory ([isSeriousAboutVictory]); else the grand strategy's (Domination without one). Picks the default pursuits first for a
     *  civilization from a save that has none.
     */
    fun updateCurrentVictoryPursuit(civ: Civilization) {
        val ai = civ.ai
        if (ai.primaryVictoryPursuit == null) selectDefaultVictoryPursuits(civ)
        if (civ.isHuman()) {
            ai.currentVictoryPursuit = ai.primaryVictoryPursuit
            return
        }
        val rules = civ.gameInfo.ruleset.ai
        ai.currentVictoryPursuit = when {
            VictoryProgressAi.scienceVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD) -> VictoryPursuit.Science
            VictoryProgressAi.cultureVictoryProgress(civ, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD) -> VictoryPursuit.Culture
            VictoryProgressAi.diplomaticVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD) -> VictoryPursuit.Diplomacy
            VictoryProgressAi.isCloseToWorldConquest(civ, rules) -> VictoryPursuit.Domination
            !isSeriousAboutVictory(civ) -> ai.primaryVictoryPursuit ?: VictoryPursuit.Domination
            else -> when (ai.grandStrategy) {
                GrandStrategyAi.UNITED_NATIONS -> VictoryPursuit.Diplomacy
                GrandStrategyAi.CULTURE -> VictoryPursuit.Culture
                GrandStrategyAi.SPACESHIP -> VictoryPursuit.Science
                else -> VictoryPursuit.Domination
            }
        }
    }

    /**
     *  CvDiplomacyAI::IsSeriousAboutVictory: with a grand strategy of priority above 500, competing for victory, and from the Renaissance
     *  (by the game's era), or for conquest from the Medieval era or once we hold another major's original capital
     */
    fun isSeriousAboutVictory(civ: Civilization): Boolean {
        val grandStrategy = civ.ai.grandStrategy ?: return false
        if (civ.ai.grandStrategyPriority(grandStrategy) <= 500 || !VictoryProgressAi.isCompetingForVictory(civ)) return false
        val era = ApproachWinningAi.gameEra(civ)
        val conquest = grandStrategy == GrandStrategyAi.CONQUEST
        if (!conquest && era < civ.define(AiDefine.RENAISSANCE_ERA)) return false
        if (conquest && era < civ.define(AiDefine.MEDIEVAL_ERA) && VictoryProgressAi.capitalsConquered(civ) <= 0) return false
        return true
    }
}

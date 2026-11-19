package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.GuessConfidence
import com.unciv.logic.automation.strategy.SpaceshipProductionAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.ai.VictoryPursuit

/**
 *  Vox Populi's levels of competition for victory (CvDiplomacyAI::DoUpdateTechBlockLevels, DoUpdatePolicyBlockLevels,
 *  DoUpdateVictoryDisputeLevels, DoUpdateVictoryBlockLevels; Release-5.4.8, roadmap D7), run each turn after the wonder spammers: how
 *  much each major we have met stands in the way of the victory we go for, in [MajorCivMemory.techBlockLevel], [policyBlockLevel],
 *  [victoryDisputeLevel] (the same victory) and [victoryBlockLevel] (another victory).
 *
 *  A human's shadow AI keeps them at none, as VP does. Unciv has no game option that turns science or policies off, and no teams or
 *  vassals (D3). Not carried over: the research benefit of tech trading (the option is off in VP by default; Unciv's BNW deals
 *  take no part in the tech block), and free policies are not told from chosen ones in the policy count.
 */
object VictoryLevelsAi {

    private fun handicap(civ: Civilization, column: String) = OpinionAi.handicapValue(civ, column, 0)

    /** The living majors that are not [civ] but valid for it: met, with a city (CvDiplomacyAI::IsPlayerValid) */
    private fun majorOthers(civ: Civilization) = civ.gameInfo.civilizations.filter { it.isMajorCiv && it != civ && !it.isSpectator }

    private fun clearAll(civ: Civilization, set: (MajorCivMemory) -> Unit) {
        for (other in majorOthers(civ)) set(civ.ai.diplomacy.memoryOf(other))
    }

    /**
     *  DoUpdateTechBlockLevels, while competing for victory: how many techs they know beyond ours, plus 1 for a scientist or the nerd
     *  trait, less 1 without the space race, plus 1 from era 3 when we go for the spaceship; less 1 for a research agreement and 1 for
     *  a defensive pact (only when they are not untrustworthy, denounced or denouncing). Fierce for one close to the spaceship that we
     *  are endgame-aggressive to; none for a tie before era 2 and when they are not ahead. Else the lead x our diplomatic balance + the
     *  difficulty's TechBlockMod: fierce from TECH_BLOCK_FIERCE_THRESHOLD, strong, weak.
     */
    fun updateTechBlockLevels(civ: Civilization, rules: AiRuleset) {
        if (civ.isHuman()) return
        if (!VictoryProgressAi.isCompetingForVictory(civ)) {
            clearAll(civ) { it.techBlockLevel = BlockLevel.None }
            return
        }
        val ai = civ.ai
        val ourTechs = civ.tech.techsResearched.size
        val diploBalance = ai.diplomacyValue(DiplomacyPersonality.DiploBalance)
        var techMod = 0
        if (ai.isScientist() || ai.hasTraitCategory(TraitCategory.Nerd)) techMod++
        val spaceshipValid = VictoryPursuitAi.isVictoryEnabled(civ, VictoryPursuit.Science)
        if (!spaceshipValid) techMod--
        else if (civ.getAiEraNumber() >= 3 && ai.isGoingForSpaceshipVictory()) techMod++

        for (other in majorOthers(civ)) {
            val memory = ai.diplomacy.memoryOf(other)
            // Don't do this until the Classical Era
            if (civ.getAiEraNumber() <= 0 && other.getAiEraNumber() <= 0) continue
            if (!WarStateAi.isPlayerValid(civ, other)) {
                memory.techBlockLevel = BlockLevel.None
                continue
            }
            if (spaceshipValid && VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) && VictoryProgressAi.isCloseToSpaceshipVictory(other, rules)) {
                memory.techBlockLevel = BlockLevel.Fierce
                continue
            }
            var difference = other.tech.techsResearched.size - ourTechs
            // Tied pre-Medieval era? Disregard
            if (difference == 0 && civ.getAiEraNumber() < 2) {
                memory.techBlockLevel = BlockLevel.None
                continue
            }
            // Anything that modifies tech competitiveness?
            difference += techMod
            if (!OpinionAi.isUntrustworthyFriend(civ, other) && !OpinionAi.isDenouncedPlayer(civ, other) && !OpinionAi.isDenouncedPlayer(other, civ)) {
                // Reduce if we have a Research Agreement
                if (civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.ResearchAgreement) == true) difference--
                // Reduce if we have a Defensive Pact - we value military protection
                if (OpinionAi.hasDefensivePact(civ, other)) difference--
            }
            memory.techBlockLevel = if (difference > 0) {
                // Multiply by DiploBalance flavor - we want to maintain a balance of power in the world... with us in the lead, of course
                val weight = difference * diploBalance + handicap(civ, "TechBlockMod")
                when {
                    weight >= rules.define(AiDefine.TECH_BLOCK_FIERCE_THRESHOLD) -> BlockLevel.Fierce
                    weight >= rules.define(AiDefine.TECH_BLOCK_STRONG_THRESHOLD) -> BlockLevel.Strong
                    weight >= rules.define(AiDefine.TECH_BLOCK_WEAK_THRESHOLD) -> BlockLevel.Weak
                    else -> BlockLevel.None
                }
            } else BlockLevel.None
        }
    }

    /**
     *  CvPlayerPolicies::GetNumPoliciesOwned(true, true, true): the policies adopted without the finishers of the branches and with the
     *  openers of the branches that are no ideology (each is a policy of ours here, a branch's own entry); the free ones are not told apart
     */
    fun numPoliciesOwned(civ: Civilization): Int {
        val policies = civ.gameInfo.ruleset.policies
        return civ.policies.getAdoptedPolicies().count { name ->
            val policy = policies[name] ?: return@count false
            policy.policyBranchType != Policy.PolicyBranchType.BranchComplete &&
                !(policy.policyBranchType == Policy.PolicyBranchType.BranchStart && policy.branch.isIdeology)
        }
    }

    /**
     *  DoUpdatePolicyBlockLevels: as the tech block with the policies owned, never for one that is not ahead of us and not for a lead of
     *  one before we have 2 policies; +1 for a cultural leader or the tourism trait, less 1 without the culture victory, +1 from era 3 when
     *  we go for it; no research agreement or pact terms; fierce for one close to the culture victory that we are endgame-aggressive to;
     *  POLICY_BLOCK_* thresholds, the difficulty's PolicyBlockMod.
     */
    fun updatePolicyBlockLevels(civ: Civilization, rules: AiRuleset) {
        if (civ.isHuman()) return
        if (!VictoryProgressAi.isCompetingForVictory(civ)) {
            clearAll(civ) { it.policyBlockLevel = BlockLevel.None }
            return
        }
        val ai = civ.ai
        val ourPolicies = numPoliciesOwned(civ)
        val diploBalance = ai.diplomacyValue(DiplomacyPersonality.DiploBalance)
        var policyMod = 0
        if (ai.isCultural() || ai.hasTraitCategory(TraitCategory.Tourism)) policyMod++
        val cultureValid = VictoryPursuitAi.isVictoryEnabled(civ, VictoryPursuit.Culture)
        if (!cultureValid) policyMod--
        else if (civ.getAiEraNumber() >= 3 && ai.isGoingForCultureVictory()) policyMod++

        for (other in majorOthers(civ)) {
            val memory = ai.diplomacy.memoryOf(other)
            if (civ.getAiEraNumber() <= 0 && other.getAiEraNumber() <= 0) continue
            if (!WarStateAi.isPlayerValid(civ, other)) {
                memory.policyBlockLevel = BlockLevel.None
                continue
            }
            if (cultureValid && VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) && VictoryProgressAi.isCloseToCultureVictory(other, rules)) {
                memory.policyBlockLevel = BlockLevel.Fierce
                continue
            }
            var difference = numPoliciesOwned(other) - ourPolicies
            // For Policies, if we're ahead, never apply penalties
            if (difference < 0) {
                memory.policyBlockLevel = BlockLevel.None
                continue
            }
            // Tied pre-Medieval era? Disregard
            if (difference == 0 && civ.getAiEraNumber() < 2) {
                memory.policyBlockLevel = BlockLevel.None
                continue
            }
            // Sanity check for early game
            if (ourPolicies <= 1 && difference <= 1) {
                memory.policyBlockLevel = BlockLevel.None
                continue
            }
            // Anything that modifies policy competitiveness?
            difference += policyMod
            memory.policyBlockLevel = if (difference > 0) {
                val weight = difference * diploBalance + handicap(civ, "PolicyBlockMod")
                when {
                    weight >= rules.define(AiDefine.POLICY_BLOCK_FIERCE_THRESHOLD) -> BlockLevel.Fierce
                    weight >= rules.define(AiDefine.POLICY_BLOCK_STRONG_THRESHOLD) -> BlockLevel.Strong
                    weight >= rules.define(AiDefine.POLICY_BLOCK_WEAK_THRESHOLD) -> BlockLevel.Weak
                    else -> BlockLevel.None
                }
            } else BlockLevel.None
        }
    }

    /**
     *  DoUpdateVictoryDisputeLevels, after turn 150 and while we are serious about victory: for each valid major whose guessed grand
     *  strategy is ours (not when we are not at war and think them an ally): VICTORY_DISPUTE_GRAND_STRATEGY_MATCH_* by the confidence of
     *  the guess less the game's era under 6, x our victory competitiveness + the difficulty's VictoryDisputeMod; fierce from
     *  VICTORY_DISPUTE_FIERCE_THRESHOLD, strong, weak.
     */
    fun updateVictoryDisputeLevels(civ: Civilization, rules: AiRuleset) {
        if (civ.isHuman() || civ.gameInfo.turns <= 150) return
        if (!VictoryPursuitAi.isSeriousAboutVictory(civ)) {
            clearAll(civ) { it.victoryDisputeLevel = DisputeLevel.None }
            return
        }
        val ai = civ.ai
        val ourStrategy = ai.grandStrategy
        val era = ApproachWinningAi.gameEra(civ)
        for (other in majorOthers(civ)) {
            val memory = ai.diplomacy.memoryOf(other)
            if (!WarStateAi.isPlayerValid(civ, other)) {
                memory.victoryDisputeLevel = DisputeLevel.None
                continue
            }
            val (theirStrategy, confidence) = ai.guessGrandStrategy(other)
            if (theirStrategy == null || theirStrategy != ourStrategy || (!civ.isAtWarWith(other) && memory.opinion == MajorCivOpinion.Ally)) {
                memory.victoryDisputeLevel = DisputeLevel.None
                continue
            }
            var weight = when (confidence) {
                GuessConfidence.Positive -> rules.define(AiDefine.VICTORY_DISPUTE_GRAND_STRATEGY_MATCH_POSITIVE)
                GuessConfidence.Likely -> rules.define(AiDefine.VICTORY_DISPUTE_GRAND_STRATEGY_MATCH_LIKELY)
                GuessConfidence.Unsure -> rules.define(AiDefine.VICTORY_DISPUTE_GRAND_STRATEGY_MATCH_UNSURE)
                null -> 0
            }
            // Reduce competitiveness in earlier eras
            weight -= 6 - era
            var level = DisputeLevel.None
            if (weight > 0) {
                // Add weight for the player's competitiveness (1 - 10)
                weight = weight * ai.diplomacyValue(DiplomacyPersonality.VictoryCompetitiveness) + handicap(civ, "VictoryDisputeMod")
                level = when {
                    weight >= rules.define(AiDefine.VICTORY_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                    weight >= rules.define(AiDefine.VICTORY_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                    weight >= rules.define(AiDefine.VICTORY_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                    else -> DisputeLevel.None
                }
            }
            memory.victoryDisputeLevel = level
        }
    }

    /**
     *  DoUpdateVictoryBlockLevels, after turn 150 and while we are serious about victory: for each valid major whose guessed grand
     *  strategy is another one (not when we are not at war and they are a friend or better), unless it is no real threat to that
     *  victory: a conqueror below a severe warmonger threat who took a third of the majors; a diplomatic one with a third of the votes
     *  missing or no World Congress; a cultural one that is no wonder spammer, has the policy block below strong and is influential on
     *  more than one civilization; a spaceship one with no spaceship project of ours, no strong tech block and as many players ahead in
     *  tech as under it. Then VICTORY_BLOCK_GRAND_STRATEGY_DIFFERENCE_* by the confidence of the guess (+10 per spaceship project),
     *  plus our victory competitiveness, meanness, diplomatic balance and the game's era and the difficulty's VictoryBlockMod; fierce
     *  from VICTORY_BLOCK_FIERCE_THRESHOLD, strong, weak.
     */
    fun updateVictoryBlockLevels(civ: Civilization, rules: AiRuleset) {
        if (civ.isHuman() || civ.gameInfo.turns <= 150) return
        if (!VictoryPursuitAi.isSeriousAboutVictory(civ)) {
            clearAll(civ) { it.victoryBlockLevel = BlockLevel.None }
            return
        }
        val ai = civ.ai
        val ourStrategy = ai.grandStrategy
        val era = ApproachWinningAi.gameEra(civ)
        val majorsEver = UntrustworthyAi.numMajorCivsEver(civ)
        for (other in majorOthers(civ)) {
            val memory = ai.diplomacy.memoryOf(other)
            if (!WarStateAi.isPlayerValid(civ, other)) {
                memory.victoryBlockLevel = BlockLevel.None
                continue
            }
            val (theirStrategy, confidence) = ai.guessGrandStrategy(other)
            if (theirStrategy == null || theirStrategy == ourStrategy || (!civ.isAtWarWith(other) && memory.opinion >= MajorCivOpinion.Friend)) {
                memory.victoryBlockLevel = BlockLevel.None
                continue
            }

            // Exclusions: exclude anyone who isn't a competitor in this realm...
            var weight = 0
            var excluded = false
            when (theirStrategy) {
                GrandStrategyAi.CONQUEST ->
                    excluded = ai.diplomacy.warmongerThreat(other) < AiThreat.Severe && VictoryProgressAi.numMajorsConquered(other) >= majorsEver / 3
                GrandStrategyAi.UNITED_NATIONS -> {
                    val league = civ.gameInfo.league
                    // 33% there? Close!
                    excluded = league == null || league.getDelegates(other) < league.getVotesNeededForWorldLeader() / 3
                }
                GrandStrategyAi.CULTURE ->
                    excluded = !memory.wonderSpammer && memory.policyBlockLevel < BlockLevel.Strong && other.culture.getNumCivsInfluentialOn() > 1
                GrandStrategyAi.SPACESHIP -> {
                    val projects = SpaceshipProductionAi.projectCount(other)
                    val spaceRace = if (projects > 0) {
                        weight += projects * 10
                        true
                    } else if (memory.techBlockLevel >= BlockLevel.Strong) true
                    else {
                        val theirTechs = other.tech.techsResearched.size
                        var numOthers = 0
                        var numAhead = 0
                        for (third in civ.gameInfo.civilizations) {
                            if (!third.isMajorCiv || third == other || third.isSpectator) continue
                            // IsPlayerValid(third, true): ourselves count as well
                            if (third != civ && !WarStateAi.isPlayerValid(civ, third)) continue
                            if (!third.isAlive() || third.cities.isEmpty()) continue
                            numOthers++
                            if (theirTechs > third.tech.techsResearched.size) numAhead++
                        }
                        numAhead >= numOthers / 2
                    }
                    excluded = !spaceRace
                }
            }
            if (excluded) {
                memory.victoryBlockLevel = BlockLevel.None
                continue
            }

            weight += when (confidence) {
                GuessConfidence.Positive -> rules.define(AiDefine.VICTORY_BLOCK_GRAND_STRATEGY_DIFFERENCE_POSITIVE)
                GuessConfidence.Likely -> rules.define(AiDefine.VICTORY_BLOCK_GRAND_STRATEGY_DIFFERENCE_LIKELY)
                GuessConfidence.Unsure -> rules.define(AiDefine.VICTORY_BLOCK_GRAND_STRATEGY_DIFFERENCE_UNSURE)
                null -> 0
            }
            var level = BlockLevel.None
            if (weight > 0) {
                // Add weight for the player's victory competitiveness, meanness and diplobalance desires (1 - 10); the average of each is 5
                // and the era goes up by one throughout the game
                weight += ai.diplomacyValue(DiplomacyPersonality.VictoryCompetitiveness) + ai.diplomacyValue(DiplomacyPersonality.Meanness) +
                    ai.diplomacyValue(DiplomacyPersonality.DiploBalance) + era
                weight += handicap(civ, "VictoryBlockMod")
                level = when {
                    weight >= rules.define(AiDefine.VICTORY_BLOCK_FIERCE_THRESHOLD) -> BlockLevel.Fierce
                    weight >= rules.define(AiDefine.VICTORY_BLOCK_STRONG_THRESHOLD) -> BlockLevel.Strong
                    weight >= rules.define(AiDefine.VICTORY_BLOCK_WEAK_THRESHOLD) -> BlockLevel.Weak
                    else -> BlockLevel.None
                }
            }
            memory.victoryBlockLevel = level
        }
    }
}

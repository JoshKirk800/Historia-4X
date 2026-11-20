package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory

/**
 *  The competition for victory in part 5 of Vox Populi's SelectBestApproachTowardsMajorCiv (VICTORY ISSUES, TECH BLOCK, POLICY BLOCK;
 *  Release-5.4.8, D7): how the victory dispute and block levels ([VictoryLevelsAi]) and the tech and policy blocks move our approach
 *  towards a player. On our scale: VP's personality bias is ours x 100, so the formulas keep VP's form with our bias.
 *  Not ported with it: the territorial, wonder and city-state disputes that follow VICTORY ISSUES in part 5 (their levels exist, no
 *  approach reads them yet), the strategic trade partner.
 */
object ApproachCompetitionAi {
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal
    private val NEUTRAL = MajorCivApproach.Neutral.ordinal

    /** The larger of the victory dispute and block levels: VP's iMaxVictoryDispute, 0 to 3 */
    fun maxVictoryDispute(memory: MajorCivMemory): Int = maxOf(memory.victoryDisputeLevel.ordinal, memory.victoryBlockLevel.ordinal)

    /**
     *  VICTORY ISSUES, while competing for victory after turn 150: by the larger of the victory dispute and block levels, x their era (at
     *  least 1) x our victory competitiveness x the larger of the difficulty's VictoryDisputePercent and VictoryBlockPercent: none
     *  adds friendly and neutral (nothing when they provoked us), / 500; weak deceptive and guarded, / 1000; strong those and war, / 750;
     *  fierce guarded, war and hostile, / 500.
     */
    fun addVictoryIssueWeights(civ: Civilization, other: Civilization, provokedUs: Boolean, w: IntArray) {
        if (!VictoryProgressAi.isCompetingForVictory(civ) || civ.gameInfo.turns <= 150) return
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val difficulty = maxOf(OpinionAi.handicapValue(civ, "VictoryDisputePercent"), OpinionAi.handicapValue(civ, "VictoryBlockPercent"))
        val scale = maxOf(other.getAiEraNumber(), 1) * civ.ai.diplomacyValue(DiplomacyPersonality.VictoryCompetitiveness) * difficulty
        when (maxVictoryDispute(civ.ai.diplomacy.memoryOf(other))) {
            0 -> if (!provokedUs) {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * scale / 500
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * scale / 500
            }
            1 -> {
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * scale / 1000
                w[GUARDED] += bias(MajorCivApproach.Guarded) * scale / 1000
            }
            2 -> {
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * scale / 750
                w[GUARDED] += bias(MajorCivApproach.Guarded) * scale / 750
                w[WAR] += bias(MajorCivApproach.War) * scale / 750
            }
            3 -> {
                w[GUARDED] += bias(MajorCivApproach.Guarded) * scale / 500
                w[WAR] += bias(MajorCivApproach.War) * scale / 500
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * scale / 500
            }
        }
    }

    /**
     *  TECH BLOCK and POLICY BLOCK, while competing for victory and when either of us is past the Ancient era: the multiplier starts at 1,
     *  +1 for a scientist (primary or secondary victory pursuit; cultural for the policy block) or the nerd (tourism) trait, +1 for going
     *  for the spaceship (culture) victory from the Renaissance, +1 when we are close to it, +1 when they are close to it and we are
     *  endgame-aggressive to them, +1 for having robbed us or broken or ignored a spy promise (tech block) or for their influence over us
     *  being influential, or popular and rising (policy block), +1 for a wonder spammer (policy block); doubled at a fierce block with a
     *  victory competitor (+1 for a scientist or the nerd trait / a cultural leader or the tourism trait instead). A level above none
     *  adds diplomatic balance x multiplier x the difficulty's TechBlockPercent (PolicyBlockPercent) x 100 / (1000 less 125 per era of the
     *  later of us and them past the first, at least 500), none with a bonus (the competition for the victory is no concern of theirs: not
     *  when they provoked us, are a spammer, or are close to it themselves) (diplomatic balance + neediness) x multiplier x the percent / 10;
     *  weighed into friendly and neutral (none), war (when a victory competitor or leaning that way), guarded and deceptive (weak), war,
     *  guarded, hostile and deceptive when it applies (strong), war and hostile (fierce).
     */
    fun addBlockWeights(civ: Civilization, other: Civilization, provokedUs: Boolean, applyDeception: Boolean, w: IntArray, rules: AiRuleset) {
        if (!VictoryProgressAi.isCompetingForVictory(civ)) return
        val myEra = civ.getAiEraNumber()
        val theirEra = other.getAiEraNumber()
        if (myEra <= rules.define(AiDefine.ANCIENT_ERA) && theirEra <= rules.define(AiDefine.ANCIENT_ERA)) return
        val ai = civ.ai
        val memory = ai.diplomacy.memoryOf(other)
        val blockEra = maxOf(0, maxOf(myEra, theirEra) - 1)
        val endgameAggressive = VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
        val renaissance = rules.define(AiDefine.RENAISSANCE_ERA)

        // TECH BLOCK
        var multiplier = 1
        var bonus = false
        var victoryCompetitor = false
        val scientistTraits = ai.hasTraitCategory(TraitCategory.Nerd)
        if (ai.isScientist() || ai.isSecondaryScientist()) { multiplier++; bonus = true }
        if (scientistTraits) { multiplier++; bonus = true }
        if (myEra >= renaissance && ai.isGoingForSpaceshipVictory()) { multiplier++; victoryCompetitor = true }
        if (VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)) { multiplier++; victoryCompetitor = true }
        if (VictoryProgressAi.isCloseToSpaceshipVictory(other, rules) && endgameAggressive) { multiplier++; bonus = false; victoryCompetitor = true }
        if (memory.numTimesRobbed > 0 || memory.brokenSpyPromise || memory.ignoredSpyPromise) { multiplier++; bonus = false }
        val scientist = ai.isScientist() || scientistTraits
        applyBlock(civ, memory.techBlockLevel, OpinionAi.handicapValue(civ, "TechBlockPercent"), multiplier, bonus, victoryCompetitor, scientist,
            provokedUs, applyDeception, blockEra, w)

        // POLICY BLOCK
        multiplier = 1
        bonus = false
        victoryCompetitor = false
        val culturalTraits = ai.hasTraitCategory(TraitCategory.Tourism)
        if (ai.isCultural() || ai.isSecondaryCultural()) { multiplier++; bonus = true }
        if (culturalTraits) { multiplier++; bonus = true }
        if (myEra >= renaissance && ai.isGoingForCultureVictory()) { multiplier++; victoryCompetitor = true }
        if (VictoryProgressAi.isCloseToCultureVictory(civ, rules)) { multiplier++; victoryCompetitor = true }
        if (VictoryProgressAi.isCloseToCultureVictory(other, rules) && endgameAggressive) { multiplier++; bonus = false; victoryCompetitor = true }
        val influence = other.culture.getInfluenceLevel(civ)
        if (influence != null && influence >= InfluenceLevel.Influential) {
            multiplier++
            bonus = !victoryCompetitor
        } else if (influence == InfluenceLevel.Popular && other.culture.getInfluenceTrend(civ) == InfluenceTrend.Rising) {
            multiplier++
            bonus = !victoryCompetitor
        }
        if (memory.wonderSpammer) { multiplier++; bonus = false }
        applyBlock(civ, memory.policyBlockLevel, OpinionAi.handicapValue(civ, "PolicyBlockPercent"), multiplier, bonus, victoryCompetitor,
            ai.isCultural() || culturalTraits, provokedUs, applyDeception, blockEra, w)
    }

    /** The scaling and the weights of one block (the same for tech and policy; [ownField]: a scientist or nerd / a cultural leader or tourism) */
    private fun applyBlock(civ: Civilization, level: BlockLevel, percent: Int, baseMultiplier: Int, baseBonus: Boolean, victoryCompetitor: Boolean,
                           ownField: Boolean, provokedUs: Boolean, applyDeception: Boolean, blockEra: Int, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        var multiplier = baseMultiplier
        // Additional multiplier increase if the block level is fierce
        if (level == BlockLevel.Fierce) {
            if (victoryCompetitor) multiplier *= 2
            else if (ownField) multiplier++
        }
        // No non-competition bonuses if they've provoked us
        val bonus = baseBonus && !provokedUs
        // Scale based on flavors and difficulty level
        var modifier = 0
        val diploBalance = civ.ai.diplomacyValue(DiplomacyPersonality.DiploBalance)
        if (level > BlockLevel.None) modifier = diploBalance * multiplier * percent * 100 / maxOf(500, 1000 - blockEra * 125)
        else if (bonus) modifier = (diploBalance + civ.ai.diplomacyValue(DiplomacyPersonality.Neediness)) * multiplier * percent / 10
        when (level) {
            BlockLevel.None -> if (bonus) {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * modifier / 100
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * modifier / 100
            }
            BlockLevel.Weak -> {
                if (victoryCompetitor || ownField) w[WAR] += bias(MajorCivApproach.War) * modifier / 100
                w[GUARDED] += bias(MajorCivApproach.Guarded) * modifier / 100
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * modifier / 100
            }
            BlockLevel.Strong -> {
                w[WAR] += bias(MajorCivApproach.War) * modifier / 100
                w[GUARDED] += bias(MajorCivApproach.Guarded) * modifier / 100
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * modifier / 100
                if (applyDeception) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * modifier / 100
            }
            BlockLevel.Fierce -> {
                w[WAR] += bias(MajorCivApproach.War) * modifier / 100
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * modifier / 100
            }
        }
    }
}

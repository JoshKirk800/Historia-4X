package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import kotlin.random.Random

/**
 *  CvDiplomacyAI::DoUpdateMajorCivApproaches and SelectBestApproachTowardsMajorCiv of Vox Populi (Release-5.4.8, CvDiplomacyAI.cpp; D7, in
 *  place of BNW's GetBestApproachTowardsMajorCiv and its worst-opinion-first order). Each turn two passes go over the valid majors: a
 *  strategic one that records every player's scores, then the final one, in which an approach weighs less towards a player the more other
 *  players rank above them for it (part 9). The scores of [approachWeights] (on our scale: VP's personality bias x 100 is ours x 1, so
 *  they are multiplied by 100 first) go through VP's frame: prioritization, randomness (part 11), opinion as a percentage (part 13), the
 *  approach curve that carries 70% of last turn's scores into this turn's (part 14), and the selection (part 15).
 *  The terms of parts 2 to 8 are still BNW's where `phaseE/vp-approach-plan.md` has not ported them yet; parts 10 and 12 too (BNW's
 *  multipliers in [approachWeights], [applyFinalRules]). Not taken in from part 1 yet: leader traits and the default victory pursuit (VP's
 *  trait and victory classifications), quests of city-states, and the bonus when an army is in place for the attack (VP's war targets).
 */
object ApproachAi {

    private val APPROACHES = MajorCivApproach.entries
    private val WAR = MajorCivApproach.War.ordinal
    private val HOSTILE = MajorCivApproach.Hostile.ordinal
    private val DECEPTIVE = MajorCivApproach.Deceptive.ordinal
    private val GUARDED = MajorCivApproach.Guarded.ordinal
    private val AFRAID = MajorCivApproach.Afraid.ordinal
    private val FRIENDLY = MajorCivApproach.Friendly.ordinal
    private val NEUTRAL = MajorCivApproach.Neutral.ordinal
    /** VP's CivApproachTypes order (NEUTRAL before FRIENDLY), which decides ties and what is worse than neutral */
    internal val VP_ORDER = listOf(MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Deceptive, MajorCivApproach.Guarded,
        MajorCivApproach.Afraid, MajorCivApproach.Neutral, MajorCivApproach.Friendly)
    private val BAD_APPROACHES = intArrayOf(WAR, HOSTILE, DECEPTIVE, GUARDED, AFRAID)

    /** A developer's view into [approachWeights]: called with the name of the step about to run and the weights so far (the live array: copy it). Null in the game. */
    @JvmField var debugSink: ((String, IntArray) -> Unit)? = null

    /** CvDiplomacyAI::DoUpdateMajorCivApproaches: the strategic pass, then (as VP's DoTurn) the major competitors and the biggest
     *  competitor (DoRelationshipPairing), then the final pass, over every valid major */
    fun updateApproaches(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val majors = others.filter { it.isMajorCiv }
        val random = civ.ai.random("DiplomacyAI.MajorCivApproach")
        for (other in majors) selectApproach(civ, other, strategic = true, majors, rules, random)
        CompetitorAi.updateMajorCompetitors(civ, others, rules)
        CompetitorAi.updateBiggestCompetitor(civ, others, rules)
        for (other in majors) selectApproach(civ, other, strategic = false, majors, rules, random)
        WarTargetAi.updateWarTargets(civ, majors, rules)  // Vox Populi's DoUpdateWarTargets, at the end of the final pass
    }

    /** DoFirstContactInitRelationship: an approach right when we meet (the others keep theirs), as the first update of that player */
    fun initApproach(civ: Civilization, other: Civilization, rules: AiRuleset) {
        selectApproach(civ, other, strategic = false, listOf(other), rules, civ.ai.random("DiplomacyAI.FirstContact" + other.civID))
    }

    /**
     *  SelectBestApproachTowardsMajorCiv for [other] among [validPlayers]. The strategic pass records the scores and the choice as the
     *  strategic ones; the final pass sets the approach (and the war face when it is war). The first update (met this turn: VP's
     *  GetTurnsSinceMeetingTeam == 0) has no last-turn biases and no curve, and is never worse than neutral unless they provoked us.
     *  @return the approach chosen
     */
    fun selectApproach(civ: Civilization, other: Civilization, strategic: Boolean, validPlayers: List<Civilization>, rules: AiRuleset,
                       random: Random): MajorCivApproach {
        val memory = civ.ai.diplomacy.memoryOf(other)
        val firstUpdate = memory.turnMet == civ.gameInfo.turns
        val w = approachWeights(civ, other, if (firstUpdate) null else memory.approach, rules, strategic)
        for (i in w.indices) w[i] *= 100
        val mods = approachMods(civ)
        if (!strategic && validPlayers.size > 1) prioritize(civ, other, validPlayers, mods, w)
        capAtZero(w)
        applyMultipliers(civ, other, w, rules)
        if (!strategic) randomize(w, rules, random)
        capAtZero(w)
        applyHumanDifficulty(civ, other, w, rules)
        // Part 12: zero-out conditions; an approach zeroed here also counts as zero last turn (VP's vScratchValueOverrides)
        val lastTurn = memory.approachValues.takeIf { it.size == APPROACHES.size }?.toIntArray()
        val overrides = IntArray(APPROACHES.size) { -1 }
        val noFriendly = zeroOut(civ, other, strategic, w, overrides, rules)
        val last = IntArray(APPROACHES.size) { if (overrides[it] >= 0) overrides[it] else lastTurn?.get(it) ?: 0 }
        applyOpinion(civ, other, strategic, validPlayers, mods, w, last, rules)
        // Part 14, the approach curve: no jumping from value to value. A player without values yet (first update, or a game saved before
        // the curve) starts from this turn's.
        val curve = !strategic && !firstUpdate && lastTurn != null
        if (curve) for (i in w.indices) w[i] = curved(w[i], last[i], rules)
        if (!strategic) memory.approachValues = ArrayList(w.toList())

        // Part 15, the selection: the highest score in VP's order; all at zero is neutral
        var approach = if (w.all { it <= 0 }) MajorCivApproach.Neutral else VP_ORDER.maxBy { w[it.ordinal] }
        // No friendly (or deceptive) towards those we obviously cannot be friends with; no friendly (deceptive instead) towards an easy
        // target that provoked us, when we want an opportunity attack
        if (noFriendly == NoFriendly.Obvious && (approach == MajorCivApproach.Friendly || approach == MajorCivApproach.Deceptive))
            approach = if (memory.opinion <= MajorCivOpinion.Enemy) MajorCivApproach.Guarded else MajorCivApproach.Neutral
        else if (noFriendly == NoFriendly.Opportunity && approach == MajorCivApproach.Friendly) approach = MajorCivApproach.Deceptive
        if (firstUpdate && !provokedUs(civ, other) && VP_ORDER.indexOf(approach) < VP_ORDER.indexOf(MajorCivApproach.Neutral))
            approach = MajorCivApproach.Neutral
        if (strategic) {
            memory.strategicApproachValues = ArrayList(w.toList())
            memory.strategicApproach = approach
        } else {
            if (approach == MajorCivApproach.War && memory.warFace == null) memory.warFace = warFace(civ, other, w)
            memory.approach = approach
        }
        return approach
    }

    /**
     *  Part 9's modifiers, one per approach, also used by part 13: the approach bias (VP's bias / 100, ours as it is) plus the personality
     *  value behind the approach: boldness, denounce willingness, 11 - loyalty, 11 - work-with willingness, 11 - meanness, DoF willingness,
     *  diplo balance.
     */
    fun approachMods(civ: Civilization): IntArray {
        val ai = civ.ai
        val mods = IntArray(APPROACHES.size)
        mods[WAR] = ai.majorCivApproachBias(MajorCivApproach.War) + ai.diplomacyValue(DiplomacyPersonality.Boldness)
        mods[HOSTILE] = ai.majorCivApproachBias(MajorCivApproach.Hostile) + ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness)
        mods[DECEPTIVE] = ai.majorCivApproachBias(MajorCivApproach.Deceptive) + (11 - ai.diplomacyValue(DiplomacyPersonality.Loyalty))
        mods[GUARDED] = ai.majorCivApproachBias(MajorCivApproach.Guarded) + (11 - ai.diplomacyValue(DiplomacyPersonality.WorkWithWillingness))
        mods[AFRAID] = ai.majorCivApproachBias(MajorCivApproach.Afraid) + (11 - ai.diplomacyValue(DiplomacyPersonality.Meanness))
        mods[FRIENDLY] = ai.majorCivApproachBias(MajorCivApproach.Friendly) + ai.diplomacyValue(DiplomacyPersonality.DoFWillingness)
        mods[NEUTRAL] = ai.majorCivApproachBias(MajorCivApproach.Neutral) + ai.diplomacyValue(DiplomacyPersonality.DiploBalance)
        return mods
    }

    /**
     *  Part 9, prioritization (final pass only): for each approach the valid players are ranked by their strategic scores, highest first;
     *  [other] at rank r loses r x the approach's bias (x 100), less when the approach's modifier is above 10 and more when below:
     *  so we do not adopt the same approach towards too many players.
     */
    private fun prioritize(civ: Civilization, other: Civilization, validPlayers: List<Civilization>, mods: IntArray, w: IntArray) {
        val memories = civ.ai.diplomacy.memories
        for (approach in APPROACHES) {
            val i = approach.ordinal
            val rank = validPlayers.sortedByDescending { memories[it.civID]?.strategicApproachValues?.getOrNull(i) ?: 0 }.indexOf(other)
            if (rank <= 0) continue
            var reduction = civ.ai.majorCivApproachBias(approach) * 100 * rank
            val flavorMod = (mods[i] - 10) * 10
            if (flavorMod > 0) reduction = reduction * 100 / (100 + flavorMod)
            else if (flavorMod < 0) reduction = reduction * (100 + flavorMod) / 100
            if (reduction > 0) w[i] -= reduction
        }
    }

    /** Part 11, randomness (final pass only): each approach x a random 100 +/- APPROACH_RANDOM_PERCENT % (in VP's order of draws) */
    private fun randomize(w: IntArray, rules: AiRuleset, random: Random) {
        val factor = rules.define(AiDefine.APPROACH_RANDOM_PERCENT).coerceIn(0, 100)
        if (factor == 0) return
        for (i in intArrayOf(FRIENDLY, NEUTRAL, AFRAID, GUARDED, DECEPTIVE, HOSTILE, WAR))
            w[i] = percent(w[i], random.nextInt(100 - factor, 100 + factor + 1))
    }

    /**
     *  Part 13, opinion: the cached opinion weight as a +/- % on every approach, after the other modifiers. Above the competitor threshold
     *  (measured against the average of the others we dislike), the bad approaches rise and friendly and neutral fall; below the favorable
     *  threshold (against the average of the others we like) friendly rises, the bad approaches fall, and neutral is aimed one below
     *  friendly after the curve as far as the opinion allows (raised as far as it can when friendly does not lead); in between, neutral
     *  rises, the more the nearer the opinion is to 0. The thresholds are the ones of our opinion weights (BNW's until VP's opinion is
     *  ported: `phaseE/vp-approach-plan.md`, section 2).
     */
    private fun applyOpinion(civ: Civilization, other: Civilization, strategic: Boolean, validPlayers: List<Civilization>, mods: IntArray,
                             w: IntArray, lastTurn: IntArray, rules: AiRuleset) {
        val memories = civ.ai.diplomacy.memories
        val memory = civ.ai.diplomacy.memoryOf(other)
        var opinionWeight = memory.opinionWeight
        val competitorDefine = rules.define(AiDefine.OPINION_THRESHOLD_COMPETITOR)
        val favorableDefine = rules.define(AiDefine.OPINION_THRESHOLD_FAVORABLE)
        val competitor = maxOf(competitorDefine, 1)
        val favorable = minOf(favorableDefine, -1)
        val othersWeights = validPlayers.filter { it != other }.map { memories[it.civID]?.opinionWeight ?: 0 }
        if (opinionWeight > competitor) {
            val bad = othersWeights.filter { it > competitorDefine }
            if (validPlayers.size > 1 && bad.isNotEmpty()) {
                val average = bad.sum() / bad.size
                val difference = (opinionWeight * 100 - average * 100) / maxOf(average, 1)
                opinionWeight = percent(opinionWeight, range(100 + difference, mods[HOSTILE] + 40, maxOf(100, mods[FRIENDLY] * 10)))
            }
            for (i in BAD_APPROACHES) w[i] = percent(w[i], 100 + mods[i] + opinionWeight)
            w[FRIENDLY] = percentDecrease(w[FRIENDLY], maxOf(100, 100 - mods[FRIENDLY] + opinionWeight))
            w[NEUTRAL] = percentDecrease(w[NEUTRAL], maxOf(100, 100 - mods[NEUTRAL] + opinionWeight))
        } else if (opinionWeight < favorable) {
            opinionWeight = -opinionWeight
            val good = othersWeights.filter { it < favorableDefine }
            if (validPlayers.size > 1 && good.isNotEmpty()) {
                val average = -good.sum() / good.size
                val difference = (opinionWeight * 100 - average * 100) / maxOf(average, 1)
                opinionWeight = percent(opinionWeight, range(100 + difference, mods[FRIENDLY] + 40, maxOf(100, mods[HOSTILE] * 10)))
            }
            w[FRIENDLY] = percent(w[FRIENDLY], 100 + mods[FRIENDLY] + opinionWeight)
            for (i in BAD_APPROACHES) w[i] = percentDecrease(w[i], maxOf(100, 100 - mods[i] + opinionWeight))
            // Neutral: as far up or down as the opinion permits, aimed at one below friendly after this turn's curve
            val neutralUp = percent(w[NEUTRAL], 100 + mods[NEUTRAL] + opinionWeight)
            val neutralDown = percentDecrease(w[NEUTRAL], maxOf(100, 100 - mods[NEUTRAL] + opinionWeight))
            fun last(i: Int) = lastTurn[i]
            val friendlyPost = if (strategic) w[FRIENDLY] else curved(w[FRIENDLY], last(FRIENDLY), rules)
            val highestBadPost = if (strategic) BAD_APPROACHES.maxOf { w[it] }
                else BAD_APPROACHES.maxOf { curved(w[it], last(it), rules) }.coerceAtLeast(0)
            if (friendlyPost > highestBadPost) {
                var target = friendlyPost - 1
                if (!strategic) {
                    // the value that, curved with last turn's neutral, comes out at the target
                    val alpha = rules.defineFloat(AiDefine.APPROACH_SHIFT_PERCENT)
                    target = ((target - (1 - alpha) * last(NEUTRAL)) / alpha).toInt()
                }
                if (target > w[NEUTRAL]) w[NEUTRAL] = minOf(target, neutralUp)
                else if (target < w[NEUTRAL]) w[NEUTRAL] = maxOf(neutralDown, target)
            } else w[NEUTRAL] = neutralUp
        } else {
            var neutralMod = mods[NEUTRAL]
            neutralMod = if (opinionWeight >= 0) neutralMod * (100 + 100 * (competitor - opinionWeight) / competitor) / 100
            else neutralMod * (100 + 100 * (favorable - opinionWeight) / -favorable) / 100
            w[NEUTRAL] = percent(w[NEUTRAL], 100 + neutralMod)
        }
    }

    /** Part 14: this turn's [value] weighed APPROACH_SHIFT_PERCENT against [last] turn's, moving at least one step when the value changed */
    private fun curved(value: Int, last: Int, rules: AiRuleset): Int {
        val alpha = rules.defineFloat(AiDefine.APPROACH_SHIFT_PERCENT)
        var average = (0.5f + value * alpha + last * (1 - alpha)).toInt()
        if (average == last && value != last) average += if (value > last) 1 else -1
        return average
    }

    /**
     *  bProvokedUs of part 3 (Vox Populi) as far as we have it: a demand of theirs while at war (VP also: a demand we accepted, a second
     *  demand), they denounced us, they are untrustworthy, they took our capital or holy city, they culture-bombed us, they killed a city-state
     *  we protect or attacked one (VP: without a promise not to; we keep no record of such a promise being made), or they hold more cities
     *  of ours than we of theirs while either is close.
     */
    private fun provokedUs(civ: Civilization, other: Civilization): Boolean {
        val memory = civ.ai.diplomacy.memories[other.civID]
        val rules = civ.gameInfo.ruleset.ai
        if (memory != null) {
            if (memory.demandCounter != -1 && civ.isAtWarWith(other)) return true
            if (memory.numTimesCultureBombed > 0 || OpinionAi.isAngryAboutProtectedMinorKilled(memory, rules)) return true
            if (OpinionAi.isAngryAboutProtectedMinorAttacked(memory, rules)) return true
        }
        if (OpinionAi.isDenouncedPlayer(other, civ) || OpinionAi.isUntrustworthyFriend(other)) return true
        if (OpinionAi.isCapitalCapturedBy(civ, other) || holyCityOf(civ)?.civ == other) return true
        val close = maxOf(DiplomacyTurn.proximity(civ, other), DiplomacyTurn.proximity(other, civ)) >= DiplomacyTurn.PROXIMITY_CLOSE
        return close && ourCitiesOwnedBy(civ, other) > ourCitiesOwnedBy(other, civ)
    }

    /** CvDiplomacyAI::ApplyPercentageModifier: [value] x [modifier] / 100 */
    private fun percent(value: Int, modifier: Int): Int = (value.toLong() * modifier / 100).toInt()
    /** CvDiplomacyAI::ApplyPercentageModifier with bDecrease: [value] x 100 / [modifier] */
    private fun percentDecrease(value: Int, modifier: Int): Int = (value.toLong() * 100 / modifier).toInt()
    /** VP's range(): [value] kept within [low] and [high] (the high bound wins when they cross) */
    private fun range(value: Int, low: Int, high: Int): Int = minOf(maxOf(value, low), high)
    private fun capAtZero(w: IntArray) { for (i in w.indices) if (w[i] < 0) w[i] = 0 }

    /**
     *  The weights of SelectBestApproachTowardsMajorCiv before part 9, by [MajorCivApproach.ordinal], on our scale (VP's / 100).
     *  [oldApproach] is last turn's approach, null on the first update (no last-turn biases). [strategic]: the strategic pass, which
     *  leaves out the terms that read the competitors chosen after it (part 4).
     */
    fun approachWeights(civ: Civilization, other: Civilization, oldApproach: MajorCivApproach?, rules: AiRuleset,
                        strategic: Boolean = false): IntArray {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val w = IntArray(APPROACHES.size)
        fun stage(name: String) { debugSink?.invoke(name, w) }
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)

        stage("Personality (part 1)")
        for (approach in APPROACHES) w[approach.ordinal] += bias(approach)

        stage("Neutral default (part 1)")
        w[NEUTRAL] += bias(MajorCivApproach.Neutral) * rules.define(AiDefine.APPROACH_NEUTRAL_DEFAULT)

        stage("Last turn's approach (part 1): less likely to flip, and war is not given up easily")
        if (oldApproach != null) {
            w[oldApproach.ordinal] += bias(oldApproach) * rules.define(AiDefine.APPROACH_BIAS_FOR_CURRENT)
            if (oldApproach == MajorCivApproach.War) w[WAR] += bias(MajorCivApproach.War) * rules.define(AiDefine.APPROACH_WAR_CURRENTLY_WAR)
        }
        val warState = WarStateAi.warState(civ, other)
        // VP's easy target ([WarStateAi.isEasyTarget]; its set-up check for a target we are not at war with is moot: only enemies are easy)
        val easy = WarStateAi.isEasyTarget(civ, other)
        stage("Conquest bias (part 1): not at war yet, or the war going better than defensive")
        // Going for world conquest or close to it (VP: better than troubled, which we lack: better than defensive); more on an easy target
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        if ((warState == null || warState > WarState.Defensive) && (civ.ai.isGoingForWorldConquest() || closeToConquest)) {
            w[WAR] += bias(MajorCivApproach.War) * (if (closeToConquest) 2 else 1)
            if (easy) w[WAR] += bias(MajorCivApproach.War) * (if (closeToConquest) 4 else 2)
        }

        // Part 2, friendship (Vox Populi). Exchange desires, the coop war agreement score, liberation of the capital or holy city and returned
        // cities need inputs we do not have yet (`phaseE/vp-approach-plan.md`, branch B2).
        val diplomacy = civ.getDiplomacyManager(other)
        val loyalty = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
        stage("Declaration of friendship (part 2)")
        if (OpinionAi.isDoFAccepted(civ, other)) {
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * maxOf(loyalty, 5)
            zeroBadApproaches(w)
        }
        stage("Defensive pact (part 2)")
        if (diplomacy?.diplomaticStatus == DiplomaticStatus.DefensivePact) {
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * maxOf(loyalty, 5)
            zeroBadApproaches(w)
        }
        stage("Research agreement (part 2)")
        if (diplomacy?.hasFlag(DiplomacyFlags.ResearchAgreement) == true) {
            // VP: x 5 for a scientist (its victory pursuit, not ported) or one going for a spaceship victory
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * (if (civ.ai.grandStrategy == GrandStrategyAi.SPACESHIP) 5 else 3)
            zeroBadApproaches(w)
        }
        stage("Coop war with them against another (part 2)")
        var coopWar = false
        for (third in civ.gameInfo.civilizations) {
            if (third == civ || third == other || !third.isMajorCiv || !WarStateAi.isPlayerValid(civ, third)) continue
            // GetCoopWarState >= COOP_WAR_STATE_PREPARING: BNW's states, agreed for soon or now
            if ((memory.coopWars[third.civID]?.state ?: continue) < CoopWarState.Soon) continue
            coopWar = true
            val strengthMod = (WarStateAi.militaryStrengthComparedToUs(civ, third)?.ordinal ?: StrengthLevel.Average.ordinal) - 2
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * (if (strengthMod > 0) strengthMod else 1)
        }
        if (coopWar) zeroBadApproaches(w)
        stage("Liberated cities (part 2)")
        val capturedKeyCity = OpinionAi.isCapitalCapturedBy(civ, other) || holyCityOf(civ)?.civ == other
        val untrustworthy = OpinionAi.isUntrustworthyFriend(other)
        if (!capturedKeyCity && !untrustworthy && !civ.isAtWarWith(other)) {
            // VP: liberated less the more of the cities of ours they own and the cities they captured from us
            val liberationMod = memory.numCitiesLiberated - maxOf(ourCitiesOwnedBy(civ, other), memory.numCitiesCapturedBy)
            if (liberationMod > 0) {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * liberationMod * 2
                zeroBadApproaches(w)
                w[NEUTRAL] = 0
            }
        }
        stage("Resurrection (part 2)")
        val resurrectedThem = other.ai.diplomacy.memories[civ.civID]?.resurrectedUs == true
        if (memory.resurrectedUs || resurrectedThem) {
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) *
                (if (memory.resurrectedUs && resurrectedThem) maxOf(20, 10 + loyalty * 2) else maxOf(10, loyalty + 5))
            for (i in BAD_APPROACHES) w[i] = -APPROACHES[i].let { bias(it) } * 2
            w[NEUTRAL] = -bias(MajorCivApproach.Neutral) * 2
        }

        // Part 3, provocations (Vox Populi). Sanctions, the traitor's personal betrayal (and its easy-target weight) and demand counts wait
        // for their inputs (branch B2).
        stage("Demands (part 3)")
        if (memory.demandCounter != -1) { // BNW keeps no count: one demand
            w[HOSTILE] += bias(MajorCivApproach.Hostile)
            w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) / 2
            if (easy || (WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average) < StrengthLevel.Average) w[WAR] += bias(MajorCivApproach.War)
            else w[GUARDED] += bias(MajorCivApproach.Guarded)
        }
        stage("Denouncements (part 3)")
        if (OpinionAi.isDenouncedPlayer(civ, other)) {
            w[WAR] += bias(MajorCivApproach.War) * 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 2
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
            if (easy) {
                w[WAR] += bias(MajorCivApproach.War) * 2
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * 2
            }
        }
        if (OpinionAi.isDenouncedPlayer(other, civ)) {
            w[WAR] += bias(MajorCivApproach.War) * 2
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 2
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
        }
        stage("Traitor (part 3)")
        if (untrustworthy) {
            w[WAR] += bias(MajorCivApproach.War) * 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 2
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 2
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
        }
        stage("Vengeance: our capital, our holy city, our territory (part 3)")
        if (OpinionAi.isCapitalCapturedBy(civ, other)) {
            for (i in intArrayOf(WAR, HOSTILE, GUARDED)) w[i] += bias(APPROACHES[i]) * 10
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
            if (easy) w[WAR] += 200 // VP's flat 20000: get our capital back
        }
        if (holyCityOf(civ)?.civ == other) {
            val religionFlavor = civ.ai.personality(Flavor.Religion).coerceIn(1, 10) // GetPersonalityFlavorForDiplomacy
            for (i in intArrayOf(WAR, HOSTILE, GUARDED)) w[i] += bias(APPROACHES[i]) * religionFlavor
            w[FRIENDLY] = 0
            if (easy) w[WAR] += bias(MajorCivApproach.War) * religionFlavor
            w[DECEPTIVE] = 0
        }
        if (memory.numTimesCultureBombed > 0) {
            w[WAR] += bias(MajorCivApproach.War) * memory.numTimesCultureBombed / 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * memory.numTimesCultureBombed / 2
            w[GUARDED] += bias(MajorCivApproach.Guarded) * memory.numTimesCultureBombed / 4
            w[DECEPTIVE] = 0
            if (easy) w[WAR] += bias(MajorCivApproach.War) * memory.numTimesCultureBombed / 2
        }
        stage("Our protected city-states (part 3)")
        val diploVictory = civ.ai.grandStrategy == GrandStrategyAi.UNITED_NATIONS
        if (OpinionAi.isAngryAboutProtectedMinorKilled(memory, rules)) {
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
            val penalty = if (diploVictory) maxOf(memory.numProtectedMinorsKilled * 2, memory.numProtectedMinorsAttacked)
                else maxOf(memory.numProtectedMinorsKilled, memory.numProtectedMinorsAttacked / 2)
            w[WAR] += bias(MajorCivApproach.War) * penalty
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * penalty
        } else if (OpinionAi.isAngryAboutProtectedMinorAttacked(memory, rules)) {
            val penalty = memory.numProtectedMinorsKilled + memory.numProtectedMinorsAttacked
            if (diploVictory) {
                w[WAR] += bias(MajorCivApproach.War) * penalty
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * penalty
                w[GUARDED] += bias(MajorCivApproach.Guarded) * penalty / 2
                w[FRIENDLY] = 0
                w[DECEPTIVE] = 0
            } else {
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * penalty
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * penalty / 2
                w[GUARDED] += bias(MajorCivApproach.Guarded) * penalty / 2
            }
        } else if (OpinionAi.isAngryAboutProtectedMinorBullied(memory, rules)) {
            val pact = OpinionAi.isDoFAccepted(civ, other) || diplomacy?.diplomaticStatus == DiplomaticStatus.DefensivePact ||
                diplomacy?.hasFlag(DiplomacyFlags.ResearchAgreement) == true
            if (pact) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * memory.numProtectedMinorsBullied / 2
            else w[HOSTILE] += bias(MajorCivApproach.Hostile) * memory.numProtectedMinorsBullied / 2
        }
        if (OpinionAi.isAngryAboutSidedWithProtectedMinor(memory, rules) && !OpinionAi.isDoFAccepted(civ, other)
            && diplomacy?.diplomaticStatus != DiplomaticStatus.DefensivePact && diplomacy?.hasFlag(DiplomacyFlags.ResearchAgreement) != true
            // VP also counts leaders whose traits bully city-states (its trait classification, not ported)
            && (civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 7 || civ.ai.diplomacyValue(DiplomacyPersonality.Meanness) > 7)) {
            w[WAR] += bias(MajorCivApproach.War)
            w[HOSTILE] += bias(MajorCivApproach.Hostile)
        }
        stage("Captured cities (part 3)")
        addCapturedCitiesWeights(civ, other, easy, w)

        stage("Key players (part 4)")
        addKeyPlayerWeights(civ, other, strategic, w, rules)

        stage("promises")
        addPromiseWeights(civ, other, memory, w, rules)
        stage("Warmonger threat and strength compared to us (part 5)")
        addStrengthWeights(civ, other, w, rules)
        stage("DoF history (part 5)")
        addDoFHistoryWeights(civ, other, w, rules)
        stage("nukes (Vox Populi)")
        addNukeWeights(civ, other, w, rules)
        stage("Bankruptcy and our lost capital (part 6)")
        ApproachPart6Ai.addBankruptcyWeights(civ, other, w)
        stage("At war right now, war states (part 6)")
        ApproachPart6Ai.addAtWarWeights(civ, other, w)
        stage("Dangerous neighbours (part 6)")
        ApproachPart6Ai.addDangerousNeighbourWeights(civ, other, w)
        stage("They are close to winning (part 6)")
        ApproachWinningAi.addCloseToWinningWeights(civ, other, w, rules)
        stage("Dogpile: already at war with someone else (part 6)")
        ApproachWinningAi.addDogpileWeights(civ, other, strategic, w, rules)
        val deception = appliesDeception(civ, other, rules)
        stage("Early game strategy (part 6)")
        ApproachGrandStrategyAi.addEarlyGameWeights(civ, other, deception, w, rules)
        stage("Grand strategy: domination (part 6)")
        ApproachGrandStrategyAi.addDominationWeights(civ, other, deception, w, rules)

        stage("Duel: no friendly or deceptive; war decisively (Vox Populi)")
        // Duel: no friendly or deceptive
        if (civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } == 2) {
            w[DECEPTIVE] = 0
            w[FRIENDLY] = 0
            // Vox Populi (DUEL in SelectBestApproachTowardsMajorCiv, on our scale: VP's personality bias is ours x 100): one who competes for
            // victory wants war, one who goes for or is close to world conquest overwhelmingly so
            if (VictoryProgressAi.isCompetingForVictory(civ)) {
                w[WAR] += civ.ai.majorCivApproachBias(MajorCivApproach.War) * 5
                // VP's flat 100000 against an easy target close to winning
                if (easy && VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) w[WAR] += 1000
            }
            if (civ.ai.isGoingForWorldConquest() || closeToConquest) w[WAR] += if (closeToConquest) 1000 else 100
        }

        stage("Unique units and a cramped empire (part 7)")
        ApproachAdditionalAi.addUniqueUnitAndCrampedWeights(civ, other, w, rules)
        stage("Reckless expanders and wonder spammers (part 7)")
        ExpanderAi.addExpanderWeights(civ, other, deception, w, rules)
        stage("Difficulty and war bonuses (part 7)")
        addWarBonuses(civ, other, closeToConquest, w, rules)

        stage("Agreed to a coop war against them")
        // Agreed to a coop war against them
        if (CoopWarAi.isLockedIntoCoopWar(civ, other, rules)) w[WAR] += rules.define(AiDefine.COOP_WAR_LOCKED_TURNS_WAR_WEIGHT)
        stage("end")
        return w
    }

    /** BNW's war probability against humans by difficulty (AIDeclareWarProb; VP's difficulty terms are part 7, not yet ported) */
    private fun applyHumanDifficulty(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        if (!other.isHuman()) return
        val warMod = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get("AIDeclareWarProb") ?: 100
        scale(w, WAR, warMod)
        scale(w, DECEPTIVE, warMod)
    }

    /**
     *  Part 10, MULTIPLIERS (Vox Populi), after part 9 and its cap at zero, on VP's scale. War and hostility by our target value of
     *  them (MAJOR_WAR_MULTIPLIER_TARGET_*: impossible 25% to soft 150%; none, nothing). By the closer of the two proximities: afraid,
     *  friendly, deceptive and guarded by APPROACH_MULTIPLIER_PROXIMITY_* (neighbours 150% to distant 50%), war and hostility too when
     *  neighbours or close, or able to cross the ocean; unable to, war and hostility are 0 towards one we are not close to. With
     *  Domination off, war and hostility halve (towards an AI, unless only war can end the game); in the AI's aggressive mode war,
     *  hostility and (when it applies) deception double towards an AI. VP's conquest variants, for an opportunity attack on a good
     *  attack target (CvMilitaryAI), have no input; nor the vassal and sanction multipliers.
     */
    private fun applyMultipliers(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun warAndHostile(modifier: Int) {
            w[WAR] = percent(w[WAR], modifier)
            w[HOSTILE] = percent(w[HOSTILE], modifier)
        }
        when (WarStateAi.targetValue(civ, other)) {
            TargetValue.Impossible -> rules.define(AiDefine.MAJOR_WAR_MULTIPLIER_TARGET_IMPOSSIBLE)
            TargetValue.Bad -> rules.define(AiDefine.MAJOR_WAR_MULTIPLIER_TARGET_BAD)
            TargetValue.Average -> rules.define(AiDefine.MAJOR_WAR_MULTIPLIER_TARGET_AVERAGE)
            TargetValue.Favorable -> rules.define(AiDefine.MAJOR_WAR_MULTIPLIER_TARGET_FAVORABLE)
            TargetValue.Soft -> rules.define(AiDefine.MAJOR_WAR_MULTIPLIER_TARGET_SOFT)
            null -> null
        }?.let { warAndHostile(it) }
        val ours = DiplomacyTurn.proximity(civ, other)
        val closest = maxOf(ours, DiplomacyTurn.proximity(other, civ))
        val canCrossOcean = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean
        val proximityModifier = rules.define(when (closest) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> AiDefine.APPROACH_MULTIPLIER_PROXIMITY_NEIGHBORS
            DiplomacyTurn.PROXIMITY_CLOSE -> AiDefine.APPROACH_MULTIPLIER_PROXIMITY_CLOSE
            DiplomacyTurn.PROXIMITY_FAR -> AiDefine.APPROACH_MULTIPLIER_PROXIMITY_FAR
            else -> AiDefine.APPROACH_MULTIPLIER_PROXIMITY_DISTANT
        })
        for (i in intArrayOf(AFRAID, FRIENDLY, DECEPTIVE, GUARDED)) w[i] = percent(w[i], proximityModifier)
        if (canCrossOcean || closest >= DiplomacyTurn.PROXIMITY_CLOSE) warAndHostile(proximityModifier)
        if (!canCrossOcean && ours < DiplomacyTurn.PROXIMITY_CLOSE) {
            w[WAR] = 0
            w[HOSTILE] = 0
        }
        val aggressiveMode = VictoryProgressAi.isAiAggressiveMode(civ)
        val dominationOff = civ.victoryManager.getAvailableVictories().none { it.name == "Domination" }
        if (dominationOff && (other.isHuman() || !aggressiveMode)) {
            w[WAR] /= 2
            w[HOSTILE] /= 2
        }
        if (!other.isHuman() && aggressiveMode) {
            w[WAR] *= 2
            w[HOSTILE] *= 2
            if (appliesDeception(civ, other, rules)) w[DECEPTIVE] *= 2
        }
    }

    /**
     *  Part 12, zero-out conditions (Vox Populi), each also setting the approach's last-turn value to 0 in [overrides]:
     *  a recent peace treaty (after a war, within TURNS_SINCE_PEACE_WEIGHT_DAMPENER turns) zeroes war and hostile in the final pass; a war
     *  we cannot declare does too (not in the strategic pass when only the treaty stops it); afraid goes when they cannot declare war on
     *  us; hostile (and war, if they resurrected us) towards a liberator. Not yet: no possible attack target, war bankruptcy, liberated
     *  capitals and recent liberations (inputs of branch B2).
     *  @return VP's bNoFriendlyObvious as far as we have it (we or they denounced, or they are untrustworthy), else its bNoFriendly (they
     *  provoked us, are an easy target and we want an opportunity attack; a good attack target has no input); either sets friendly to 0
     *  in the strategic pass
     */
    private fun zeroOut(civ: Civilization, other: Civilization, strategic: Boolean, w: IntArray, overrides: IntArray, rules: AiRuleset): NoFriendly {
        fun zero(i: Int) { w[i] = 0; overrides[i] = 0 }
        val memory = civ.ai.diplomacy.memoryOf(other)
        val atWar = civ.isAtWarWith(other)
        val recentPeace = memory.numWarsFought > 0 && memory.turnMadePeace > -1 &&
            civ.gameInfo.turns - memory.turnMadePeace < rules.define(AiDefine.TURNS_SINCE_PEACE_WEIGHT_DAMPENER)
        if (recentPeace && !strategic) { zero(WAR); zero(HOSTILE) }
        if (!atWar && civ.getDiplomacyManager(other)?.canDeclareWar() != true && (!strategic || !recentPeace)) { zero(WAR); zero(HOSTILE) }
        if (!atWar && other.getDiplomacyManager(civ)?.canDeclareWar() != true) zero(AFRAID)
        val untrustworthy = OpinionAi.isUntrustworthyFriend(other)
        val capturedKeyCity = OpinionAi.isCapitalCapturedBy(civ, other) || holyCityOf(civ)?.civ == other
        // IsLiberator(ePlayer, false, true) as far as we have it: they resurrected us, captured no more of our cities than they liberated,
        // and took no key city of ours
        val liberator = memory.resurrectedUs && memory.numCitiesCapturedBy <= memory.numCitiesLiberated
        if (liberator && !capturedKeyCity && !atWar && !untrustworthy) { zero(HOSTILE); zero(WAR) }
        val obvious = OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ) || untrustworthy
        val noFriendly = when {
            obvious -> NoFriendly.Obvious
            provokedUs(civ, other) && WarStateAi.isEasyTarget(civ, other) && wantsOpportunityAttack(civ, other, rules) -> NoFriendly.Opportunity
            else -> NoFriendly.None
        }
        if (strategic && noFriendly != NoFriendly.None) w[FRIENDLY] = 0
        return noFriendly
    }

    /** Part 12's no-friendly cases: none, VP's bNoFriendlyObvious, and its bNoFriendly beyond them */
    private enum class NoFriendly { None, Obvious, Opportunity }

    /** All approaches but friendly and neutral to 0 (the friendship sections of part 2) */
    private fun zeroBadApproaches(w: IntArray) { for (i in BAD_APPROACHES) w[i] = 0 }

    /** The holy city of [civ]'s religion, wherever it is (null without a religion) */
    private fun holyCityOf(civ: Civilization) =
        civ.religionManager.religion?.name?.let { religion -> civ.gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion) } }

    /** CvPlayer::GetNumOurCitiesOwnedBy: cities founded by [civ] that [owner] holds */
    private fun ourCitiesOwnedBy(civ: Civilization, owner: Civilization) = owner.cities.count { it.foundingCivObject == civ }

    /**
     *  Part 3, CAPTURED CITIES (Vox Populi), when either of us is at least close to the other: twice the difference between the cities of
     *  theirs we hold and the cities of ours they hold. We took more: finish the job if they are no stronger than average (war, hostile),
     *  else they will want revenge (guarded, deceptive x 2). They took more: revenge on the weaker (war, hostile, no friendly), else
     *  caution (less war and hostile, more guarded and deceptive); against an [easy] target, war and hostile twice the difference instead.
     */
    private fun addCapturedCitiesWeights(civ: Civilization, other: Civilization, easy: Boolean, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val ourProximity = DiplomacyTurn.proximity(civ, other)
        val theirProximity = DiplomacyTurn.proximity(other, civ)
        if (maxOf(ourProximity, theirProximity) < DiplomacyTurn.PROXIMITY_CLOSE) return
        var difference = (ourCitiesOwnedBy(other, civ) - ourCitiesOwnedBy(civ, other)) * 2
        val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        if (difference > 0) {
            if (easy) {
                if (ourProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                    w[WAR] += bias(MajorCivApproach.War) * difference * 2
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difference * 2
                }
            } else if (strength <= StrengthLevel.Average && !WarStateAi.isNoNewWars(civ)) {
                if (ourProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                    w[WAR] += bias(MajorCivApproach.War) * difference
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difference
                }
            } else if (theirProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                w[GUARDED] += bias(MajorCivApproach.Guarded) * difference
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * difference * 2
            }
        } else if (difference < 0) {
            difference = -difference
            if (easy) {
                if (ourProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                    w[WAR] += bias(MajorCivApproach.War) * difference * 2
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difference * 2
                    w[FRIENDLY] = 0
                }
            } else if (strength < StrengthLevel.Average && !WarStateAi.isNoNewWars(civ)) {
                if (ourProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                    w[WAR] += bias(MajorCivApproach.War) * difference
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difference
                    w[FRIENDLY] = 0
                }
            } else if (theirProximity >= DiplomacyTurn.PROXIMITY_CLOSE) {
                w[WAR] -= bias(MajorCivApproach.War) * difference
                w[HOSTILE] -= bias(MajorCivApproach.Hostile) * difference
                w[GUARDED] += bias(MajorCivApproach.Guarded) * difference
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * difference
            }
        }
    }

    /** BNW's war face for a new war approach (VP's cached surface approach is not ported): from the runner-up approach */
    internal fun warFace(civ: Civilization, other: Civilization, w: IntArray): WarFace {
        var second = -1
        for (i in w.indices) if (i != WAR && (second < 0 || w[i] > w[second])) second = i
        return when (APPROACHES[second]) {
            MajorCivApproach.Hostile -> WarFace.Hostile
            MajorCivApproach.Deceptive, MajorCivApproach.Afraid, MajorCivApproach.Friendly ->
                if (OpinionAi.isDenouncedPlayer(civ, other)) WarFace.Neutral else WarFace.Friendly
            else -> WarFace.Neutral
        }
    }

    private fun scale(w: IntArray, index: Int, percent: Int) {
        w[index] = w[index] * percent / 100
    }

    private fun addPromiseWeights(civ: Civilization, other: Civilization, memory: MajorCivMemory, w: IntArray, rules: AiRuleset) {
        if (memory.brokenMilitaryPromise) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_MILITARY_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_MILITARY_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_MILITARY_PROMISE)
        } else if (civ.gameInfo.civilizations.any { it.isMajorCiv && it.ai.diplomacy.memories[other.civID]?.brokenMilitaryPromise == true }) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_MILITARY_PROMISE_WORLD)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_MILITARY_PROMISE_WORLD)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_MILITARY_PROMISE_WORLD)
        } else if (memory.ignoredMilitaryPromise) {
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_MILITARY_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_MILITARY_PROMISE)
        }
        if (memory.brokenExpansionPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_EXPANSION_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_EXPANSION_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_EXPANSION_PROMISE)
        } else if (memory.ignoredExpansionPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_IGNORED_EXPANSION_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_EXPANSION_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_EXPANSION_PROMISE)
        }
        if (memory.brokenBorderPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_BROKEN_BORDER_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_BROKEN_BORDER_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_BROKEN_BORDER_PROMISE)
        } else if (memory.ignoredBorderPromiseValue > 0) {
            w[WAR] += rules.define(AiDefine.APPROACH_WAR_IGNORED_BORDER_PROMISE)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_IGNORED_BORDER_PROMISE)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_IGNORED_BORDER_PROMISE)
        }
    }

    /**
     *  Part 5, DOF HISTORY (Vox Populi): long friendships are kept and betrayals remembered, more as the eras go by. By our DoF level
     *  with them ([MajorCivMemory.doFLevel]) times the era (at least 1) and a diplomacy multiplier (2 going for a diplomatic victory,
     *  else 1; +1 with DiploBalance 8 or more, -1 with 3 or less; -1 when, competing for victory, a past friend is close to a diplomatic
     *  victory; at least 0): betrayed, war, guarded and hostile; never friends, neutral and friendly; friends, friendly up and deceptive
     *  down; allies twice, war down; battle brothers three times, war down twice. VP's league competitor term has no input.
     */
    private fun addDoFHistoryWeights(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val level = civ.ai.diplomacy.memoryOf(other).doFLevel
        val competing = VictoryProgressAi.isCompetingForVictory(civ)
        val diploBalance = civ.ai.diplomacyValue(DiplomacyPersonality.DiploBalance)
        var multiplier = if (competing && civ.ai.isGoingForDiploVictory()) 2 else 1
        if (diploBalance >= 8) multiplier++ else if (diploBalance <= 3) multiplier--
        if (competing && level > DoFLevel.Untrustworthy &&
            VictoryProgressAi.diplomaticVictoryProgress(other) >= rules.define(AiDefine.CLOSE_TO_DIPLOMATIC_VICTORY_THRESHOLD)) multiplier--
        multiplier = maxOf(multiplier, 0)
        val era = maxOf(civ.getEra().eraNumber, 1)
        val step = era * multiplier
        when (level) {
            DoFLevel.Untrustworthy -> for (i in intArrayOf(WAR, GUARDED, HOSTILE)) w[i] += bias(APPROACHES[i]) * step
            DoFLevel.New -> for (i in intArrayOf(NEUTRAL, FRIENDLY)) w[i] += bias(APPROACHES[i]) * step
            DoFLevel.Friends -> {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * step
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * step
            }
            DoFLevel.Allies -> {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * step * 2
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * step * 2
                w[WAR] -= bias(MajorCivApproach.War) * era
            }
            DoFLevel.BattleBrothers -> {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * step * 3
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * step * 3
                w[WAR] -= bias(MajorCivApproach.War) * step * 2
            }
        }
    }

    /**
     *  Part 5 of Vox Populi's SelectBestApproachTowardsMajorCiv, WARMONGER THREAT and STRENGTH COMPARED TO US (in place of BNW's military
     *  threat term): a severe or critical warmonger is met with war, hostility and caution by our warmonger hate, a minor one with
     *  friendliness; a weaker player invites war (more when we want an opportunity attack, deceptive only when deception applies), a
     *  stronger one friendliness, caution or fear. Economic strength counts the same way, but a rich player is feared only when also
     *  strong. The attack multipliers: 2 against an easy target of ours, 2 for them if we are theirs (VP's good attack targets and
     *  exposure, CvMilitaryAI, have no input).
     */
    private fun addStrengthWeights(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val hate = civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate)
        val attack = if (WarStateAi.isEasyTarget(civ, other)) 2 else 1 // iAttackMultiplier
        val theirAttack = if (WarStateAi.isEasyTarget(other, civ)) 2 else 1 // iTheirAttackMultiplier
        when (civ.ai.diplomacy.warmongerThreat(other)) {
            AiThreat.Critical -> {
                w[WAR] += bias(MajorCivApproach.War) * hate * 2
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * hate * 2
                w[GUARDED] += bias(MajorCivApproach.Guarded) * hate * theirAttack / 2
                w[AFRAID] += bias(MajorCivApproach.Afraid) * (11 - hate) * theirAttack
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * hate / theirAttack
            }
            AiThreat.Severe -> {
                w[WAR] += bias(MajorCivApproach.War) * hate
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * hate
                w[GUARDED] += bias(MajorCivApproach.Guarded) * hate * theirAttack
                w[AFRAID] += bias(MajorCivApproach.Afraid) * (11 - hate) * theirAttack / 2
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * hate / theirAttack / 2
            }
            AiThreat.Major -> {
                w[GUARDED] += bias(MajorCivApproach.Guarded) * hate * theirAttack / 2
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * hate * theirAttack / 2
                w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * hate / theirAttack / 3
            }
            AiThreat.Minor, AiThreat.None -> if (!provokedUs(civ, other)) {
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * hate * theirAttack / 2
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * hate * theirAttack / 2
            }
        }
        val opportunity = wantsOpportunityAttack(civ, other, rules)
        val deception = appliesDeception(civ, other, rules)
        val military = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        /** The weaker-than-us cases, the same for military and economic strength: war and hostile (deceptive if deception applies) up,
         *  afraid and guarded down, by [factor] (or half of it, without an opportunity attack); [deceptiveFactor] for deceptive */
        fun weaker(factor: Int, deceptiveFactor: Int) {
            w[WAR] += if (opportunity) bias(MajorCivApproach.War) * factor * attack else bias(MajorCivApproach.War) * factor / 2
            w[HOSTILE] += if (opportunity) bias(MajorCivApproach.Hostile) * factor * attack else bias(MajorCivApproach.Hostile) * factor / 2
            w[DECEPTIVE] += if (opportunity && deception) bias(MajorCivApproach.Deceptive) * deceptiveFactor * attack
                else bias(MajorCivApproach.Deceptive) * factor / 2
            w[AFRAID] -= bias(MajorCivApproach.Afraid) * factor * attack
            w[GUARDED] -= bias(MajorCivApproach.Guarded) * factor * attack
        }
        /** POOR: a little war or deception, some neutrality */
        fun poor() {
            w[WAR] += if (opportunity) bias(MajorCivApproach.War) * 2 * attack else bias(MajorCivApproach.War)
            w[DECEPTIVE] += if (opportunity) bias(MajorCivApproach.Deceptive) * 2 * attack else bias(MajorCivApproach.Deceptive)
            w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2
            w[AFRAID] -= bias(MajorCivApproach.Afraid) * 2 * attack
            w[GUARDED] -= bias(MajorCivApproach.Guarded) * 2 * attack
        }
        when (military) {
            StrengthLevel.Pathetic -> weaker(6, 6)
            StrengthLevel.Weak -> weaker(4, 4)
            StrengthLevel.Poor -> poor()
            StrengthLevel.Average -> {
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
            }
            StrengthLevel.Strong -> {
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * 2 * theirAttack
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2 * theirAttack
                w[WAR] -= bias(MajorCivApproach.War) * 2 * theirAttack
                w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 2 * theirAttack
            }
            StrengthLevel.Powerful -> {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 3 * theirAttack
                w[GUARDED] += bias(MajorCivApproach.Guarded) * 3 * theirAttack
                w[AFRAID] += bias(MajorCivApproach.Afraid) * 4 * theirAttack
                w[WAR] -= bias(MajorCivApproach.War) * 4 * theirAttack
                w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 4 * theirAttack
            }
            StrengthLevel.Immense -> {
                w[AFRAID] += bias(MajorCivApproach.Afraid) * 8 * theirAttack
                w[WAR] -= bias(MajorCivApproach.War) * 6 * theirAttack
                w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 6 * theirAttack
            }
        }
        when (civ.ai.diplomacy.memories[other.civID]?.economicStrength ?: StrengthLevel.Average) {
            StrengthLevel.Pathetic -> weaker(6, 6)
            StrengthLevel.Weak -> weaker(4, 2)
            StrengthLevel.Poor -> poor()
            StrengthLevel.Average -> {
                w[NEUTRAL] += bias(MajorCivApproach.Neutral)
                w[FRIENDLY] += bias(MajorCivApproach.Friendly)
            }
            StrengthLevel.Strong -> {
                w[NEUTRAL] += bias(MajorCivApproach.Neutral) * theirAttack
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * theirAttack
                if (military >= StrengthLevel.Strong) {
                    w[WAR] -= bias(MajorCivApproach.War) * 2 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 2 * theirAttack
                }
            }
            StrengthLevel.Powerful -> {
                w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2 * theirAttack
                w[GUARDED] += bias(MajorCivApproach.Guarded) * 2 * theirAttack
                if (military > StrengthLevel.Strong) {
                    w[AFRAID] += bias(MajorCivApproach.Afraid) * 4 * theirAttack
                    w[WAR] -= bias(MajorCivApproach.War) * 4 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 4 * theirAttack
                } else if (military == StrengthLevel.Strong) {
                    w[WAR] -= bias(MajorCivApproach.War) * 2 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 2 * theirAttack
                }
            }
            StrengthLevel.Immense -> when (military) {
                StrengthLevel.Strong -> {
                    w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2 * theirAttack
                    w[GUARDED] += bias(MajorCivApproach.Guarded) * 2 * theirAttack
                    w[WAR] -= bias(MajorCivApproach.War) * 2 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 2 * theirAttack
                }
                StrengthLevel.Powerful -> {
                    w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2 * theirAttack
                    w[GUARDED] += bias(MajorCivApproach.Guarded) * 2 * theirAttack
                    w[AFRAID] += bias(MajorCivApproach.Afraid) * 4 * theirAttack
                    w[WAR] -= bias(MajorCivApproach.War) * 4 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 4 * theirAttack
                }
                StrengthLevel.Immense -> {
                    w[AFRAID] += bias(MajorCivApproach.Afraid) * 8 * theirAttack
                    w[WAR] -= bias(MajorCivApproach.War) * 6 * theirAttack
                    w[HOSTILE] -= bias(MajorCivApproach.Hostile) * 6 * theirAttack
                }
                else -> {
                    w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2 * theirAttack
                    w[GUARDED] += bias(MajorCivApproach.Guarded) * 2 * theirAttack
                }
            }
        }
    }

    /**
     *  Part 7 of Vox Populi's SelectBestApproachTowardsMajorCiv, the VOX POPULI CHANGES: the difficulty bonus and the war bonus.
     *  Difficulty: towards a player we view worse than neutral, AggressionIncrease of the game's difficulty less 2 per opinion step above
     *  unforgivable adds bias x that / 2 to war, hostile (and deceptive, when deception applies), and x that more to war and hostile with
     *  an opportunity attack. War bonus: going for world conquest (VP: a conqueror pursuit or traits, not ported), more with our unique
     *  unit in the field (and more again going for or close to world conquest), Honor opened and
     *  finished, Exploration opened and finished (when we can cross the ocean from the Medieval era, have 2 coastal cities and they 1: the
     *  non-VP-balance rule, our Exploration being BNW's), Autocracy, each more with an opportunity attack; scaled by boldness (war),
     *  meanness (hostile), denounce willingness (deceptive, only when deception applies) x the difficulty's victory dispute or block
     *  percent / 500; twice for one going for or close to world conquest; deceptive left out when close to it.
     *  Left out until their inputs exist: temporary attack bonuses, victory dispute levels, easy and good targets.
     */
    private fun addWarBonuses(civ: Civilization, other: Civilization, closeToConquest: Boolean, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val handicap = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values
        val opportunity = wantsOpportunityAttack(civ, other, rules)
        val deception = appliesDeception(civ, other, rules)
        val attack = if (WarStateAi.isEasyTarget(civ, other)) 2 else 1 // iAttackMultiplier
        val opinion = civ.ai.diplomacy.memoryOf(other).opinion
        if (opinion < MajorCivOpinion.Neutral) {
            // VP's opinion order is the same up to neutral: Unforgivable 0, Enemy 1, Competitor 2
            val difficultyBonus = (handicap?.get("AggressionIncrease") ?: 0) - opinion.ordinal * 2
            if (difficultyBonus > 0) {
                w[WAR] += bias(MajorCivApproach.War) * difficultyBonus / 2
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * difficultyBonus / 2
                if (deception) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * difficultyBonus / 2
                if (opportunity) {
                    w[WAR] += bias(MajorCivApproach.War) * difficultyBonus
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difficultyBonus
                }
                if (attack > 1) { // an easy target
                    w[WAR] += bias(MajorCivApproach.War) * difficultyBonus / 2
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * difficultyBonus / 2
                }
            }
        }
        var war = 0
        var hostile = 0
        var deceptive = 0
        fun add(withOpportunity: Int, without: Int) {
            val factor = if (opportunity) withOpportunity else without
            war += bias(MajorCivApproach.War) * factor * attack
            hostile += bias(MajorCivApproach.Hostile) * factor * attack
            deceptive += bias(MajorCivApproach.Deceptive) * factor * attack
        }
        val conqueror = civ.ai.isGoingForWorldConquest()
        if (conqueror) {
            add(2, 1)
            // Unique unit active? Slay them all!
            if (ApproachAdditionalAi.hasUniqueUnitPeriod(civ, rules) && ApproachAdditionalAi.hasUniqueUnitTech(civ, rules) &&
                ApproachAdditionalAi.hasUniqueUnitActive(civ, rules)) {
                add(2, 1)
                // VP: and going for or close to world conquest, which our conqueror (going for it) always is
                add(4, 2)
            }
        }
        val policies = civ.policies
        if (policies.isAdopted("Honor")) add(3, 2)
        if (policies.isAdopted("Honor Complete")) add(3, 2)
        val canCrossOcean = (civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean) &&
            civ.getEra().eraNumber >= rules.define(AiDefine.MEDIEVAL_ERA)
        if (policies.isAdopted("Exploration") && canCrossOcean && civ.cities.count { it.isCoastal() } > 1 && other.cities.any { it.isCoastal() }) {
            add(3, 2)
            if (policies.isAdopted("Exploration Complete")) add(3, 2)
        }
        if (policies.isAdopted("Autocracy")) add(6, 3)
        val difficulty = maxOf(handicap?.get("VictoryDisputePercent") ?: 100, handicap?.get("VictoryBlockPercent") ?: 100)
        war = war * civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) * difficulty / 500
        hostile = hostile * civ.ai.diplomacyValue(DiplomacyPersonality.Meanness) * difficulty / 500
        deceptive = if (deception) deceptive * civ.ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness) * difficulty / 500 else 0
        if (war <= 0 && hostile <= 0 && deceptive <= 0) return
        val times = if (conqueror || closeToConquest) 2 else 1
        w[WAR] += war * times
        w[HOSTILE] += hostile * times
        if (!closeToConquest) w[DECEPTIVE] += deceptive * times
    }

    /**
     *  bApplyDeception of part 1: deceptive bonuses count only when deception could lead to war (DoUpdateWarTargets): we are close to
     *  world conquest or endgame-aggressive towards them, or they are neighbours (or close, when we can cross the ocean) and we are a
     *  backstabber, eager to denounce (above 8), lean to deception over friendship, or go for world conquest; never when either of us
     *  denounced the other, they are untrustworthy or took a key city of ours (VP also: sanctions).
     */
    private fun appliesDeception(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ) || OpinionAi.isUntrustworthyFriend(other)) return false
        if (OpinionAi.isCapitalCapturedBy(civ, other) || holyCityOf(civ)?.civ == other) return false
        if (VictoryProgressAi.isCloseToWorldConquest(civ, rules) || VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) return true
        val proximity = DiplomacyTurn.proximity(civ, other)
        val canCrossOcean = civ.tech.embarkedUnitsCanEnterOcean || civ.tech.allUnitsCanEnterOcean
        if (proximity != DiplomacyTurn.PROXIMITY_NEIGHBORS && !(canCrossOcean && proximity >= DiplomacyTurn.PROXIMITY_CLOSE)) return false
        return OpinionAi.isUntrustworthyFriend(civ) || civ.ai.diplomacyValue(DiplomacyPersonality.DenounceWillingness) > 8 ||
            civ.ai.majorCivApproachBias(MajorCivApproach.Deceptive) > civ.ai.majorCivApproachBias(MajorCivApproach.Friendly) + 1 ||
            civ.ai.isGoingForWorldConquest()
    }

    /**
     *  Nukes after Vox Populi (NUKES in CvDiplomacyAI::SelectBestApproachTowardsMajorCiv, Release-5.4.8; D7, in place of the original's
     *  untuned constants, which froze every pair where both sides held weapons). On our scale: VP's personality bias is ours × 100.
     *  How likely they are to use them: 100 for a human, after any nuclear attack between us, or when they see us as Immense or are nearly
     *  defeated by us; else their UseNuke personality flavor × 10.
     *  We have weapons and they do not: War and Hostile + bias × 8, only when we want an opportunity attack ([wantsOpportunityAttack]).
     *  They have them and we do not, likely 60 and up: Afraid + bias × 5. Both, likely 30 and up: Guarded + bias × 2 - caution only.
     *  They nuked us (at most 10 times counted): War and Hostile + bias × times / 2, Guarded and Afraid + bias × times.
     *  VP's attack multipliers for an easy or exposed target are 1: we have neither notion (step 0 of the plan).
     */
    private fun addNukeWeights(civ: Civilization, other: Civilization, w: IntArray, rules: AiRuleset) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val theirNukes = other.units.getCivUnits().any { it.isNuclearWeapon() }
        val ourNukes = civ.units.getCivUnits().any { it.isNuclearWeapon() }
        val timesNukedByThem = civ.ai.diplomacy.memories[other.civID]?.numTimesNuked ?: 0
        if (theirNukes || ourNukes) {
            val likelihood = when {
                other.isHuman() -> 100
                timesNukedByThem > 0 || (other.ai.diplomacy.memories[civ.civID]?.numTimesNuked ?: 0) > 0 -> 100
                WarStateAi.militaryStrengthComparedToUs(other, civ) == StrengthLevel.Immense
                    || WarStateAi.warState(other, civ) == WarState.NearlyDefeated -> 100
                else -> other.ai.personalityFlavorVector()[Flavor.UseNuke.ordinal] * 10
            }
            if (ourNukes && !theirNukes) {
                if (wantsOpportunityAttack(civ, other, rules)) {
                    w[WAR] += bias(MajorCivApproach.War) * 8
                    w[HOSTILE] += bias(MajorCivApproach.Hostile) * 8
                }
            } else if (theirNukes && !ourNukes && likelihood >= 60) {
                w[AFRAID] += bias(MajorCivApproach.Afraid) * 5
            } else if (theirNukes && ourNukes && likelihood >= 30) {
                w[GUARDED] += bias(MajorCivApproach.Guarded) * 2
            }
        }
        if (timesNukedByThem > 0) {
            val times = minOf(timesNukedByThem, 10)
            w[WAR] += bias(MajorCivApproach.War) * times / 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * times / 2
            w[GUARDED] += bias(MajorCivApproach.Guarded) * times
            w[AFRAID] += bias(MajorCivApproach.Afraid) * times
        }
    }

    /**
     *  Part 4, KEY PLAYERS (Vox Populi), the final pass only (the competitors are chosen after the strategic pass). Their enemies are
     *  our friends: a player that denounced our biggest competitor adds friendly (WorkAgainstWillingness / 4), one at war with it more
     *  (/ 2). Our biggest competitor: war and hostile up 5 times their bias after the Classical era (3 before), guarded and deceptive 5
     *  times after it. Else a major or early-game competitor: deceptive up 3 times, guarded 2, and when we go for world conquest, they
     *  are neighbours, [victoryConcern] or they provoked us, war and hostile 3 times (deceptive 2 more if deception applies). Else, from
     *  the Renaissance, competing for victory and with no victory concern: friendly and neutral up by our VictoryCompetitiveness (and its
     *  complement), deceptive down by it. With a World Congress, our diplomacy is glad of any player that is not our league competitor:
     *  friendly 2 times its bias unless they are untrustworthy, or provoked us while we do not go for a diplomatic victory.
     *  An easy target ([WarStateAi.isEasyTarget]) after the Ancient era adds war and hostile 5 times to either competitor when the war
     *  reasons above hold (for the biggest competitor, also when we want an opportunity attack). As none (no input yet): the most
     *  valuable friend and ally and the league's prime competitor and ally (`phaseE/vp-approach-plan.md`, C3b), sanctions.
     */
    private fun addKeyPlayerWeights(civ: Civilization, other: Civilization, strategic: Boolean, w: IntArray, rules: AiRuleset) {
        if (strategic) return
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val biggest = CompetitorAi.biggestCompetitor(civ)
        if (biggest != null && biggest != other.civID) {
            val against = civ.ai.diplomacyValue(DiplomacyPersonality.WorkAgainstWillingness)
            val third = civ.gameInfo.getCivilization(biggest)
            if (OpinionAi.isDenouncedPlayer(other, third)) w[FRIENDLY] += bias(MajorCivApproach.Friendly) * against / 4
            if (other.isAtWarWith(third)) w[FRIENDLY] += bias(MajorCivApproach.Friendly) * against / 2
        }
        val era = civ.getEra().eraNumber
        val concern = victoryConcern(civ, other, rules)
        val provoked = provokedUs(civ, other)
        val neighbours = DiplomacyTurn.proximity(civ, other) == DiplomacyTurn.PROXIMITY_NEIGHBORS
        val easyAfterAncient = WarStateAi.isEasyTarget(civ, other) && era > 0
        fun getThem() {
            w[WAR] += bias(MajorCivApproach.War) * 5
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5
        }
        if (biggest == other.civID) {
            val afterClassical = era > rules.define(AiDefine.CLASSICAL_ERA)
            w[WAR] += bias(MajorCivApproach.War) * if (afterClassical) 5 else 3
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * if (afterClassical) 5 else 3
            if (afterClassical) {
                w[GUARDED] += bias(MajorCivApproach.Guarded) * 5
                w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * 5
            }
            if (easyAfterAncient && (civ.ai.isGoingForWorldConquest() || neighbours || concern || provoked ||
                    wantsOpportunityAttack(civ, other, rules))) getThem()
        } else if (CompetitorAi.isMajorCompetitor(civ, other) || CompetitorAi.isEarlyGameCompetitor(civ, other, rules)) {
            w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * 3
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 2
            if (civ.ai.isGoingForWorldConquest() || neighbours || concern || provoked) {
                w[WAR] += bias(MajorCivApproach.War) * 3
                w[HOSTILE] += bias(MajorCivApproach.Hostile) * 3
                if (appliesDeception(civ, other, rules)) w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) * 2
                if (easyAfterAncient) getThem()
            }
        } else if (era >= rules.define(AiDefine.RENAISSANCE_ERA) && !concern && VictoryProgressAi.isCompetingForVictory(civ)) {
            val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.VictoryCompetitiveness)
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * competitiveness / 2
            w[NEUTRAL] += bias(MajorCivApproach.Neutral) * (11 - competitiveness) / 2
            w[DECEPTIVE] -= bias(MajorCivApproach.Deceptive) * competitiveness
        }
        if (civ.gameInfo.league != null && !OpinionAi.isUntrustworthyFriend(other) && (civ.ai.isGoingForDiploVictory() || !provoked))
            w[FRIENDLY] += bias(MajorCivApproach.Friendly) * 2
    }

    /**
     *  Part 4, bVictoryConcern (Vox Populi): they are close to world conquest or conquered 3 or more capitals; or, when we compete for
     *  victory, we are endgame aggressive towards them, or they stand in the way of our victory: a strong land dispute when we go for
     *  world conquest, a strong city-state dispute for a diplomatic victory, a strong wonder dispute for a culture victory. VP's victory
     *  dispute and block levels and its tech and policy blocks have no input here.
     */
    fun victoryConcern(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (VictoryProgressAi.isCloseToWorldConquest(other, rules) || VictoryProgressAi.capitalsConquered(other) >= 3) return true
        if (!VictoryProgressAi.isCompetingForVictory(civ)) return false
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
        return VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
            || (civ.ai.isGoingForWorldConquest() && memory.landDispute >= DisputeLevel.Strong)
            || (civ.ai.isGoingForDiploVictory() && memory.minorCivDispute >= DisputeLevel.Strong)
            || (civ.ai.isGoingForCultureVictory() && memory.wonderDispute >= DisputeLevel.Strong)
    }

    /**
     *  CvDiplomacyAI::SelectBestApproachTowardsMajorCiv, OPPORTUNITY ATTACK DESIRE (Vox Populi, part 4): whether we want to exploit a
     *  weakness of [other]'s, over the inputs we have. Always with a coop war against them; else, unless we take no new wars
     *  ([WarStateAi.isNoNewWars]: then only when endgame-aggressive towards them, they took a key city of ours or they are close to world
     *  conquest), with a reason that depends on whether we hold back: we hold back from distant players and, unless they are
     *  untrustworthy, took our capital or holy city, or the game is conquest-only, from those with a declaration of friendship or a
     *  defensive pact with us. Holding back, a reason is: we or they are close to world conquest, they provoked us, they took a key city
     *  of ours, their warmonger threat is severe, they are an early-game competitor or our biggest competitor, [victoryConcern], or the
     *  game is conquest-only. Not holding back: we go for world conquest from the Medieval era, they took a key city, [victoryConcern],
     *  a severe warmonger threat, either of us close to world conquest, a major competitor, or a conquest-only game; and when they are
     *  close or neighbours, also: we go for world conquest, they provoked us, our opinion of them is Enemy or worse, an early-game
     *  competitor, or a reckless expander.
     *  Left out (no counterpart): easy and good targets, the league competitor, wonder spammers, our own backstabber flag, the conqueror
     *  traits, earlier friendship that ended. "Provoked" is a demand of theirs while we are at war (VP also counts a demand we accepted or
     *  a second demand, which we do not track).
     */
    fun wantsOpportunityAttack(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (CoopWarAi.isLockedIntoCoopWar(civ, other, rules)) return true
        val memory = civ.ai.diplomacy.memories[other.civID]
        val proximity = DiplomacyTurn.proximity(civ, other)
        val aggressiveMode = VictoryProgressAi.isAiAggressiveMode(civ)
        val holyCity = civ.religionManager.religion?.name?.let { religion -> civ.gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion) } }
        val tookKeyCity = OpinionAi.isCapitalCapturedBy(civ, other) || holyCity?.civ == other
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        val theyAreClose = VictoryProgressAi.isCloseToWorldConquest(other, rules)
        val provokedUs = memory != null && memory.demandCounter != -1 && civ.isAtWarWith(other)
        if (WarStateAi.isNoNewWars(civ) && !VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules) && !tookKeyCity && !theyAreClose)
            return false
        val severeThreat = civ.ai.diplomacy.warmongerThreat(other) >= AiThreat.Severe
        val concern = victoryConcern(civ, other, rules)
        val earlyGameCompetitor = CompetitorAi.isEarlyGameCompetitor(civ, other, rules)
        val friendOrAlly = OpinionAi.isDoFAccepted(civ, other) || civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact
        val bold = civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 7 ||
            (VictoryProgressAi.isCompetingForVictory(civ) && civ.ai.isGoingForWorldConquest())
        // Friends before (a DoF level of friends or more), not competitors, still liked: held back too, unless bold against an easy target
        val pastFriends = !friendOrAlly && (memory?.doFLevel ?: DoFLevel.New) >= DoFLevel.Friends &&
            !CompetitorAi.isMajorCompetitor(civ, other) && !earlyGameCompetitor &&
            (memory?.opinion ?: MajorCivOpinion.Neutral) >= MajorCivOpinion.Competitor && (!bold || !WarStateAi.isEasyTarget(civ, other))
        val holdBack = proximity <= DiplomacyTurn.PROXIMITY_FAR || (!OpinionAi.isUntrustworthyFriend(other) && !tookKeyCity && !aggressiveMode
            && (friendOrAlly || pastFriends))
        if (holdBack)
            return closeToConquest || provokedUs || tookKeyCity || severeThreat || earlyGameCompetitor
                || CompetitorAi.biggestCompetitor(civ) == other.civID || concern || theyAreClose || aggressiveMode
        val medievalOrLater = civ.getEra().eraNumber >= rules.define(AiDefine.MEDIEVAL_ERA)
        if ((civ.ai.isGoingForWorldConquest() && medievalOrLater) || tookKeyCity || concern || severeThreat || closeToConquest
            || theyAreClose || CompetitorAi.isMajorCompetitor(civ, other) || aggressiveMode) return true
        return proximity >= DiplomacyTurn.PROXIMITY_CLOSE && (civ.ai.isGoingForWorldConquest() || provokedUs
            || (memory?.opinion ?: MajorCivOpinion.Neutral) <= MajorCivOpinion.Enemy || earlyGameCompetitor
            || (WarStateAi.isEasyTarget(civ, other) && (memory?.opinion ?: MajorCivOpinion.Neutral) <= MajorCivOpinion.Competitor)
            || WarStateAi.isPlayerRecklessExpander(civ, other))
    }

    /** CvPlayerTrade::GetAllTradeValueFromPlayerTimes100(YIELD_GOLD) / 100: gold we earn from routes between us and them */
    fun tradeGoldFrom(civ: Civilization, other: Civilization): Int {
        var gold = 0f
        for (route in civ.tradeRoutes.routes) if (route.getDestinationCiv() == other) gold += route.originYields.gold
        for (route in other.tradeRoutes.routes) if (route.getDestinationCiv() == civ) gold += route.destinationYields.gold
        return gold.toInt()
    }
}

package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.StrengthLevel
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
    private val VP_ORDER = listOf(MajorCivApproach.War, MajorCivApproach.Hostile, MajorCivApproach.Deceptive, MajorCivApproach.Guarded,
        MajorCivApproach.Afraid, MajorCivApproach.Neutral, MajorCivApproach.Friendly)
    private val BAD_APPROACHES = intArrayOf(WAR, HOSTILE, DECEPTIVE, GUARDED, AFRAID)

    /** A developer's view into [approachWeights]: called with the name of the step about to run and the weights so far (the live array: copy it). Null in the game. */
    @JvmField var debugSink: ((String, IntArray) -> Unit)? = null

    /** CvDiplomacyAI::DoUpdateMajorCivApproaches: the strategic pass, then the final pass, over every valid major */
    fun updateApproaches(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val majors = others.filter { it.isMajorCiv }
        val random = civ.ai.random("DiplomacyAI.MajorCivApproach")
        for (strategic in listOf(true, false))
            for (other in majors) selectApproach(civ, other, strategic, majors, rules, random)
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
        val w = approachWeights(civ, other, if (firstUpdate) null else memory.approach, rules)
        for (i in w.indices) w[i] *= 100
        val mods = approachMods(civ)
        if (!strategic && validPlayers.size > 1) prioritize(civ, other, validPlayers, mods, w)
        capAtZero(w)
        if (!strategic) randomize(w, rules, random)
        capAtZero(w)
        applyHumanDifficulty(civ, other, w, rules)
        // Part 12: zero-out conditions; an approach zeroed here also counts as zero last turn (VP's vScratchValueOverrides)
        val lastTurn = memory.approachValues.takeIf { it.size == APPROACHES.size }?.toIntArray()
        val overrides = IntArray(APPROACHES.size) { -1 }
        val noFriendlyObvious = zeroOut(civ, other, strategic, w, overrides, rules)
        val last = IntArray(APPROACHES.size) { if (overrides[it] >= 0) overrides[it] else lastTurn?.get(it) ?: 0 }
        applyOpinion(civ, other, strategic, validPlayers, mods, w, last, rules)
        // Part 14, the approach curve: no jumping from value to value. A player without values yet (first update, or a game saved before
        // the curve) starts from this turn's.
        val curve = !strategic && !firstUpdate && lastTurn != null
        if (curve) for (i in w.indices) w[i] = curved(w[i], last[i], rules)
        if (!strategic) memory.approachValues = ArrayList(w.toList())

        // Part 15, the selection: the highest score in VP's order; all at zero is neutral
        var approach = if (w.all { it <= 0 }) MajorCivApproach.Neutral else VP_ORDER.maxBy { w[it.ordinal] }
        // No friendly (or deceptive) towards those we obviously cannot be friends with (VP's bNoFriendly beyond the obvious cases needs easy
        // and good attack targets: branch C)
        if (noFriendlyObvious && (approach == MajorCivApproach.Friendly || approach == MajorCivApproach.Deceptive))
            approach = if (memory.opinion <= MajorCivOpinion.Enemy) MajorCivApproach.Guarded else MajorCivApproach.Neutral
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
     *  [oldApproach] is last turn's approach, null on the first update (no last-turn biases).
     */
    fun approachWeights(civ: Civilization, other: Civilization, oldApproach: MajorCivApproach?, rules: AiRuleset): IntArray {
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
        stage("Conquest bias (part 1): not at war yet, or the war going better than defensive")
        // Going for world conquest or close to it (VP: better than troubled, which we lack: better than defensive)
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        if ((warState == null || warState > WarState.Defensive) && (civ.ai.isGoingForWorldConquest() || closeToConquest))
            w[WAR] += bias(MajorCivApproach.War) * (if (closeToConquest) 2 else 1)

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
            // VP: liberated less the cities of ours they own or captured (we do not count captures: ours they own)
            val liberationMod = memory.numCitiesLiberated - ourCitiesOwnedBy(civ, other)
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

        // Part 3, provocations (Vox Populi). Without VP's easy targets (branch C) their extra war weight is left out; sanctions, the
        // traitor's personal betrayal and demand counts wait for their inputs (branch B2).
        stage("Demands (part 3)")
        if (memory.demandCounter != -1) { // BNW keeps no count: one demand
            w[HOSTILE] += bias(MajorCivApproach.Hostile)
            w[DECEPTIVE] += bias(MajorCivApproach.Deceptive) / 2
            if ((WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average) < StrengthLevel.Average) w[WAR] += bias(MajorCivApproach.War)
            else w[GUARDED] += bias(MajorCivApproach.Guarded)
        }
        stage("Denouncements (part 3)")
        if (OpinionAi.isDenouncedPlayer(civ, other)) {
            w[WAR] += bias(MajorCivApproach.War) * 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 2
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
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
        }
        if (holyCityOf(civ)?.civ == other) {
            val religionFlavor = civ.ai.personality(Flavor.Religion).coerceIn(1, 10) // GetPersonalityFlavorForDiplomacy
            for (i in intArrayOf(WAR, HOSTILE, GUARDED)) w[i] += bias(APPROACHES[i]) * religionFlavor
            w[FRIENDLY] = 0
            w[DECEPTIVE] = 0
        }
        if (memory.numTimesCultureBombed > 0) {
            w[WAR] += bias(MajorCivApproach.War) * memory.numTimesCultureBombed / 2
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * memory.numTimesCultureBombed / 2
            w[GUARDED] += bias(MajorCivApproach.Guarded) * memory.numTimesCultureBombed / 4
            w[DECEPTIVE] = 0
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
        addCapturedCitiesWeights(civ, other, w)

        stage("promises")
        addPromiseWeights(civ, other, memory, w, rules)
        stage("military threat")
        addMilitaryThreatWeights(memory.militaryThreat, w, rules)
        stage("nukes (Vox Populi)")
        addNukeWeights(civ, other, w, rules)
        stage("Close to world conquest, theirs or ours (Vox Populi)")
        addWorldConquestWeights(civ, other, closeToConquest, w)

        stage("At war right now with someone else")
        // At war right now with someone else
        val stateAllWars = WarStateAi.stateAllWars(civ)
        for (third in civ.gameInfo.civilizations) {
            if (third == other || third == civ || !WarStateAi.isPlayerValid(civ, third) || !civ.isAtWarWith(third)) continue
            w[WAR] += when (stateAllWars) {
                StateAllWars.Neutral -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_NEUTRAL)
                StateAllWars.Winning -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_WINNING)
                StateAllWars.Losing -> rules.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_LOSING)
            }
            w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_AT_WAR_WITH_PLAYER)
            w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_AT_WAR_WITH_PLAYER)
            w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_AT_WAR_WITH_PLAYER)
            w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_AT_WAR_WITH_PLAYER)
        }

        stage("Gold we get from trade routes with them (the original's untuned constants)")
        // Gold we get from trade routes with them (the original's untuned constants)
        val tradeGold = tradeGoldFrom(civ, other)
        if (tradeGold > 0) {
            w[WAR] -= 5
            w[HOSTILE] -= 5
            w[DECEPTIVE] += 5
            w[GUARDED] -= 1
            w[AFRAID] -= 1
            w[FRIENDLY] += 5
            w[NEUTRAL] -= 2
            // A war would leave us without income
            val goldRate = civ.stats.statsForNextTurn.gold.toInt()
            val delta = goldRate - tradeGold
            if (goldRate >= 0 && delta < 0) w[WAR] += delta
        }

        stage("War projection")
        // War projection
        when (memory.warProjection) {
            WarProjection.Destruction -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DESTRUCTION_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DESTRUCTION_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_DESTRUCTION_PERCENT))
            }
            WarProjection.Defeat -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DEFEAT_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_DEFEAT_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_DEFEAT_PERCENT))
            }
            WarProjection.Stalemate -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_STALEMATE_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_STALEMATE_PERCENT))
            }
            WarProjection.Unknown -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_UNKNOWN_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_UNKNOWN_PERCENT))
            }
            WarProjection.Good -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_GOOD_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_GOOD_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_GOOD_PERCENT))
            }
            WarProjection.VeryGood -> {
                scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_PROJECTION_VERY_GOOD_PERCENT))
                scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_PROJECTION_VERY_GOOD_PERCENT))
                scale(w, GUARDED, rules.define(AiDefine.APPROACH_GUARDED_PROJECTION_VERY_GOOD_PERCENT))
            }
            null -> {}
        }

        stage("Reckless expander")
        // Reckless expander
        if (WarStateAi.isPlayerRecklessExpander(civ, other)) {
            scale(w, WAR, rules.define(AiDefine.APPROACH_WAR_RECKLESS_EXPANDER))
            scale(w, DECEPTIVE, rules.define(AiDefine.APPROACH_WAR_RECKLESS_EXPANDER))
        }

        stage("Already in a war with someone else (their war states): dogpile")
        // Already in a war with someone else (their war states): dogpile
        val dogpiling = civ.gameInfo.civilizations.any {
            it.isMajorCiv && it != other && it != civ && WarStateAi.isPlayerValid(other, it) && WarStateAi.warState(other, it) != null
        }
        if (dogpiling) {
            scale(w, WAR, 200)
            scale(w, DECEPTIVE, 150)
        }

        stage("Distance")
        // Distance
        val proximityPercent = when (DiplomacyTurn.proximity(civ, other)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_NEIGHBORS)
            DiplomacyTurn.PROXIMITY_CLOSE -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_CLOSE)
            DiplomacyTurn.PROXIMITY_FAR -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_FAR)
            DiplomacyTurn.PROXIMITY_DISTANT -> rules.define(AiDefine.APPROACH_WAR_PROXIMITY_DISTANT)
            else -> 100
        }
        scale(w, WAR, proximityPercent)
        scale(w, DECEPTIVE, proximityPercent)

        stage("Duel: no friendly or deceptive; war decisively (Vox Populi)")
        // Duel: no friendly or deceptive
        if (civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } == 2) {
            w[DECEPTIVE] = 0
            w[FRIENDLY] = 0
            // Vox Populi (DUEL in SelectBestApproachTowardsMajorCiv, on our scale: VP's personality bias is ours x 100): one who competes for
            // victory wants war, one who goes for or is close to world conquest overwhelmingly so
            if (VictoryProgressAi.isCompetingForVictory(civ)) w[WAR] += civ.ai.majorCivApproachBias(MajorCivApproach.War) * 5
            if (civ.ai.isGoingForWorldConquest() || closeToConquest) w[WAR] += if (closeToConquest) 1000 else 100
        }

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
     *  Part 12, zero-out conditions (Vox Populi), each also setting the approach's last-turn value to 0 in [overrides]:
     *  a recent peace treaty (after a war, within TURNS_SINCE_PEACE_WEIGHT_DAMPENER turns) zeroes war and hostile in the final pass; a war
     *  we cannot declare does too (not in the strategic pass when only the treaty stops it); afraid goes when they cannot declare war on
     *  us; hostile (and war, if they resurrected us) towards a liberator. Not yet: no possible attack target, war bankruptcy, liberated
     *  capitals and recent liberations (inputs of branch B2).
     *  @return VP's bNoFriendlyObvious as far as we have it: we or they denounced, or they are untrustworthy (strategic pass: friendly 0)
     */
    private fun zeroOut(civ: Civilization, other: Civilization, strategic: Boolean, w: IntArray, overrides: IntArray, rules: AiRuleset): Boolean {
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
        // IsLiberator(ePlayer, false, true) as far as we have it: they resurrected us, and took no key city of ours
        if (memory.resurrectedUs && !capturedKeyCity && !atWar && !untrustworthy) { zero(HOSTILE); zero(WAR) }
        val noFriendlyObvious = OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ) || untrustworthy
        if (strategic && noFriendlyObvious) w[FRIENDLY] = 0
        return noFriendlyObvious
    }

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
     *  caution (less war and hostile, more guarded and deceptive). VP's easy-target terms wait for branch C.
     */
    private fun addCapturedCitiesWeights(civ: Civilization, other: Civilization, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        val ourProximity = DiplomacyTurn.proximity(civ, other)
        val theirProximity = DiplomacyTurn.proximity(other, civ)
        if (maxOf(ourProximity, theirProximity) < DiplomacyTurn.PROXIMITY_CLOSE) return
        var difference = (ourCitiesOwnedBy(other, civ) - ourCitiesOwnedBy(civ, other)) * 2
        val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        if (difference > 0) {
            if (strength <= StrengthLevel.Average) {
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
            if (strength < StrengthLevel.Average) {
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

    /** BNW's war face for a new war approach (kept until VP's war targets are ported): from the runner-up approach */
    private fun warFace(civ: Civilization, other: Civilization, w: IntArray): WarFace {
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

    private fun addMilitaryThreatWeights(threat: AiThreat, w: IntArray, rules: AiRuleset) {
        when (threat) {
            AiThreat.Critical -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_CRITICAL)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_CRITICAL)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_CRITICAL)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_CRITICAL)
            }
            AiThreat.Severe -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_SEVERE)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_SEVERE)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_SEVERE)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_SEVERE)
            }
            AiThreat.Major -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_MAJOR)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_MAJOR)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_MAJOR)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_MAJOR)
            }
            AiThreat.Minor -> {
                w[DECEPTIVE] += rules.define(AiDefine.APPROACH_DECEPTIVE_MILITARY_THREAT_MINOR)
                w[GUARDED] += rules.define(AiDefine.APPROACH_GUARDED_MILITARY_THREAT_MINOR)
                w[AFRAID] += rules.define(AiDefine.APPROACH_AFRAID_MILITARY_THREAT_MINOR)
                w[FRIENDLY] += rules.define(AiDefine.APPROACH_FRIENDLY_MILITARY_THREAT_MINOR)
            }
            AiThreat.None -> w[HOSTILE] += rules.define(AiDefine.APPROACH_HOSTILE_MILITARY_THREAT_NONE)
        }
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
     *  World conquest after Vox Populi (SelectBestApproachTowardsMajorCiv: THEY'RE CLOSE TO WINNING, and the Domination part of GRAND
     *  STRATEGY WEIGHT), on our scale (VP's bias is ours × 100).
     *  They are close to world conquest: War, Hostile and Guarded + bias × 5.
     *  We compete for victory, are close to world conquest, and go for it or hold another major's capital: War and Hostile + bias × 5,
     *  and + bias × 5 more for each capital of another major they hold (we must take all of them). VP's other Domination terms (flavor
     *  weights, friendship with the strong while not yet close) are not taken in: the plan's step 4 covers the endgame only.
     */
    private fun addWorldConquestWeights(civ: Civilization, other: Civilization, closeToConquest: Boolean, w: IntArray) {
        fun bias(approach: MajorCivApproach) = civ.ai.majorCivApproachBias(approach)
        if (VictoryProgressAi.isCloseToWorldConquest(other, civ.gameInfo.ruleset.ai)) {
            w[WAR] += bias(MajorCivApproach.War) * 5
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5
            w[GUARDED] += bias(MajorCivApproach.Guarded) * 5
        }
        if (closeToConquest && VictoryProgressAi.isCompetingForVictory(civ)
            && (civ.ai.isGoingForWorldConquest() || VictoryProgressAi.capitalsConquered(civ) > 0)) {
            val theirCapitals = VictoryProgressAi.capitalsConquered(other)
            w[WAR] += bias(MajorCivApproach.War) * 5 * (1 + theirCapitals)
            w[HOSTILE] += bias(MajorCivApproach.Hostile) * 5 * (1 + theirCapitals)
        }
    }

    /**
     *  CvDiplomacyAI::SelectBestApproachTowardsMajorCiv, OPPORTUNITY ATTACK DESIRE (Vox Populi): whether we want to exploit a weakness of
     *  [other]'s, over the inputs we have (`phaseE/vp-conquest-endgame-plan.md`, step 0 table). Always with a coop war against them; else,
     *  unless the war state forbids new wars (VP's IsNoNewWars has no counterpart here, so never), with a reason that depends on whether we
     *  hold back: we hold back from distant players and, unless they are untrustworthy, took our capital or holy city, or the game is
     *  conquest-only, from friends and from those with a declaration of friendship. Holding back, a reason is: we or they are close to world
     *  conquest, they provoked us, they took a key city of ours, their warmonger threat is severe, they conquered 3 or more capitals, or the
     *  game is conquest-only. Not holding back, also: we go for world conquest from the Medieval era; and when they are close or neighbours,
     *  we go for world conquest, they provoked us, our opinion of them is Enemy or worse, or they expand recklessly.
     *  Left out (no counterpart): easy and good targets, major, early-game, biggest and league competitors, wonder spammers, our own
     *  backstabber flag, the conqueror traits, earlier friendship that ended. "Provoked" is a demand of theirs while we are at war (VP also
     *  counts a demand we accepted or a second demand, which we do not track).
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
        val severeThreat = civ.ai.diplomacy.warmongerThreat(other) >= AiThreat.Severe
        val victoryConcern = theyAreClose || VictoryProgressAi.capitalsConquered(other) >= 3
        val holdBack = proximity <= DiplomacyTurn.PROXIMITY_FAR || (!OpinionAi.isUntrustworthyFriend(other) && !tookKeyCity && !aggressiveMode
            && OpinionAi.isDoFAccepted(civ, other))
        if (holdBack)
            return closeToConquest || provokedUs || tookKeyCity || severeThreat || victoryConcern || aggressiveMode
        val medievalOrLater = civ.getEra().eraNumber >= rules.define(AiDefine.MEDIEVAL_ERA)
        if ((civ.ai.isGoingForWorldConquest() && medievalOrLater) || tookKeyCity || victoryConcern || severeThreat || closeToConquest
            || theyAreClose || aggressiveMode) return true
        return proximity >= DiplomacyTurn.PROXIMITY_CLOSE && (civ.ai.isGoingForWorldConquest() || provokedUs
            || (memory?.opinion ?: MajorCivOpinion.Neutral) <= MajorCivOpinion.Enemy || WarStateAi.isPlayerRecklessExpander(civ, other))
    }

    /** CvPlayerTrade::GetAllTradeValueFromPlayerTimes100(YIELD_GOLD) / 100: gold we earn from routes between us and them */
    fun tradeGoldFrom(civ: Civilization, other: Civilization): Int {
        var gold = 0f
        for (route in civ.tradeRoutes.routes) if (route.getDestinationCiv() == other) gold += route.originYields.gold
        for (route in other.tradeRoutes.routes) if (route.getDestinationCiv() == civ) gold += route.destinationYields.gold
        return gold.toInt()
    }
}

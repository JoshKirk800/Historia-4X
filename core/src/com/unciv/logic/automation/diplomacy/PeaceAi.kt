package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.tile.ResourceType

/**
 *  Vox Populi's peace willingness towards majors (CvDiplomacyAI::DoUpdatePeaceTreatyWillingness with GetPeaceBlockReason and
 *  DoUpdatePeaceTreatyOffers, Release-5.4.8; D7): whether we want peace with each major we fight and on what terms
 *  ([MajorCivMemory.treatyWillingToOffer], [MajorCivMemory.treatyWillingToAccept], [MajorCivMemory.wantsPeace]). Decided apart
 *  from the approach, after it, as VP's DoTurn does. Without teams, vassals (D3), war weariness (none in BNW), VP's leader traits and
 *  policies, easy and preferred targets and exposure (CvMilitaryAI): those terms count as none. Peace with city-states stays BNW's
 *  (DiplomacyAutomation). VP 5.4.8 never sets its "all wars are phony" flag (bAllWarsArePhony starts false and is only ever set to
 *  false), so its two phony-war clauses never apply; left out here (`phaseE/vp-approach-plan.md`).
 */
object PeaceAi {

    /** The cut-offs of the treaty levels, from the most to the least given up */
    private val OFFER_LEVELS = listOf(
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_UN_SURRENDER to PeaceTreatyType.UnconditionalSurrender,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_CAPITULATION to PeaceTreatyType.Capitulation,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_CESSION to PeaceTreatyType.Cession,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SURRENDER to PeaceTreatyType.Surrender,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SUBMISSION to PeaceTreatyType.Submission,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_BACKDOWN to PeaceTreatyType.Backdown,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_SETTLEMENT to PeaceTreatyType.Settlement,
        AiDefine.PEACE_WILLINGNESS_OFFER_THRESHOLD_ARMISTICE to PeaceTreatyType.Armistice,
    )
    private val ACCEPT_LEVELS = listOf(
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_UN_SURRENDER to PeaceTreatyType.UnconditionalSurrender,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_CAPITULATION to PeaceTreatyType.Capitulation,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_CESSION to PeaceTreatyType.Cession,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SURRENDER to PeaceTreatyType.Surrender,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SUBMISSION to PeaceTreatyType.Submission,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_BACKDOWN to PeaceTreatyType.Backdown,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_SETTLEMENT to PeaceTreatyType.Settlement,
        AiDefine.PEACE_WILLINGNESS_ACCEPT_THRESHOLD_ARMISTICE to PeaceTreatyType.Armistice,
    )

    /**
     *  CvDiplomacyAI::GetPeaceBlockReason for a major: no peace while locked into the war by a deal, before WAR_MAJOR_MINIMUM_TURNS turns
     *  of war, while preparing a coop war against a defensive pact partner of theirs, when they have no cities left and are poor or
     *  weaker, or right after they took a city of ours (within a turn) while more than one of our units is near their cities, unless our
     *  capital may fall.
     */
    fun isPeaceBlocked(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (!civ.isAtWarWith(other) || !other.isMajorCiv) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (memory.turnsLockedIntoWar > 0) return true
        if (WarStateAi.turnsAtWar(civ, other) < rules.define(AiDefine.WAR_MAJOR_MINIMUM_TURNS)) return true
        for (third in civ.gameInfo.civilizations) {
            if (third == other || !third.isMajorCiv || !WarStateAi.isPlayerValid(civ, third)) continue
            if (third.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact &&
                CoopWarAi.globalState(civ, third) == CoopWarState.Soon) return true
        }
        if (civ.cities.isNotEmpty() && other.cities.isEmpty() &&
            (WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average) < StrengthLevel.Poor) return true
        val theirCapture = other.ai.diplomacy.memories[civ.civID]?.lastCityCaptureTurn ?: -1
        if (WarDamageAi.warScore(civ, other) > -100 && theirCapture >= 0 && civ.gameInfo.turns - theirCapture <= 1 &&
            unitsAroundEnemyCities(civ, other, 3) > 1) {
            val capital = civ.getCapital()
            if (capital == null || !CityDangerAi.isInDangerOfFalling(capital, extraCareful = true)) return true
        }
        return false
    }

    /** CvDiplomacyAI::CountUnitsAroundEnemyCities: our units able to attack within [range] tiles of [other]'s cities (VP counts path length) */
    private fun unitsAroundEnemyCities(civ: Civilization, other: Civilization, range: Int): Int =
        civ.units.getCivUnits().count { unit ->
            unit.isMilitary() && other.cities.any { it.getCenterTile().aerialDistanceTo(unit.getTile()) < range }
        }

    /** CvDiplomacyAI::AdjustConditionalModifier: scale [value] by a 1-10 flavor (5 neutral), the other way round if [invert] */
    private fun adjustConditionalModifier(value: Int, flavor: Int, invert: Boolean = false): Int {
        if (value == 0 || flavor == 0) return value
        val percentTimes10 = when (flavor) {
            1 -> if (invert) 1500 else 500
            2 -> if (invert) 1375 else 625
            3 -> if (invert) 1250 else 750
            4 -> if (invert) 1125 else 875
            6 -> if (invert) 900 else 1100
            7 -> if (invert) 800 else 1200
            8 -> if (invert) 700 else 1300
            9 -> if (invert) 600 else 1400
            10 -> if (invert) 500 else 1500
            else -> 1000
        }
        return value * percentTimes10 / 1000
    }

    /**
     *  CvDiplomacyAI::DoUpdatePeaceTreatyWillingness for the majors in [others] (AI players only; humans keep none): every major we fight
     *  is refused peace unless chosen below. Blocked ([isPeaceBlocked]): refused. In a critical state (super unhappy, no capital, the
     *  capital may fall or is at half health, or a war nearly lost) peace with all the others at once. Otherwise each war is judged
     *  ([wantsPeace]) and the chosen ones get terms ([updateOffers]).
     */
    fun update(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val atWar = others.filter { it.isMajorCiv && civ.isAtWarWith(it) }
        // Whether we wanted peace at the last update, before the refusals reset it
        val wantedBefore = atWar.associateWith {
            ai.treatyWillingToOffer(it) >= PeaceTreatyType.WhitePeace || ai.treatyWillingToAccept(it) >= PeaceTreatyType.WhitePeace
        }
        for (other in others) if (other.isMajorCiv) refuse(civ, other)
        if (civ.isHuman()) return
        val capital = civ.getCapital()
        val critical = civ.getHappiness() <= rules.define(AiDefine.SUPER_UNHAPPY_THRESHOLD) || capital == null ||
            CityDangerAi.isInDangerOfFalling(capital, extraCareful = true) || capital.health <= capital.getMaxHealth() / 2 ||
            atWar.any { WarStateAi.warState(civ, it) == WarState.NearlyDefeated }
        val candidates = atWar.filter { !isPeaceBlocked(civ, it, rules) }
        if (critical) {
            updateOffers(civ, candidates, critical = true)
            return
        }
        val dangerousWars = atWar.count { !WarIntentAi.isPhonyWar(civ, it) }
        val makePeace = candidates.filter { wantsPeace(civ, it, wantedBefore[it] == true, maxOf(dangerousWars - 1, 0), rules) }
        updateOffers(civ, makePeace, critical = false)
    }

    private fun refuse(civ: Civilization, other: Civilization) {
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.treatyWillingToOffer = PeaceTreatyType.None
        memory.treatyWillingToAccept = PeaceTreatyType.None
        memory.wantsPeace = false
    }

    /** VP's war progress penalty for unhappiness (without VP's happiness: 4 per point of unhappiness) and for strategic resource shortages
     *  (5 per missing unit, at most 50) */
    private fun warProgressPenalties(civ: Civilization): Int {
        val happiness = civ.getHappiness()
        val unhappy = if (happiness < 0) happiness * 4 else 0
        var shortage = 0
        for ((name, amount) in civ.getCivResourcesByName()) {
            if (amount >= 0 || civ.gameInfo.ruleset.tileResources[name]?.resourceType != ResourceType.Strategic) continue
            shortage += amount * 5
            if (shortage <= -50) { shortage = -50; break }
        }
        return unhappy + shortage
    }

    /**
     *  Parts 6 to 9 for one enemy (no teams: each enemy is its own team), with the war's duration counted from our last capture of one
     *  of its cities. Refused: while only they are in serious danger (for 40 turns at most); while we have nearly won against a close
     *  player, within 30 turns. Peace: badly behind in the war score and in danger, or only we are in serious danger; or, when none of
     *  their cities is endangered (or we are in serious danger, terrible shape, or the war lasts 40 turns), the war progress is gone.
     *  Otherwise, if peace is worth considering (serious danger, terrible shape, an approach better than deceptive, the war going
     *  defensive or worse, or 30 turns), the peace score of part 8 against the threshold of part 9.
     */
    private fun wantsPeace(civ: Civilization, other: Civilization, wantedBefore: Boolean, otherWars: Int, rules: AiRuleset): Boolean {
        val memory = civ.ai.diplomacy.memoryOf(other)
        val terrible = WarStateAi.isInTerribleShapeForWar(civ)
        val ourAllies = WarAlliesAi.offensiveWarAllies(civ, other, includeMinors = true, reverse = false) + civ
        val theirAllies = WarAlliesAi.offensiveWarAllies(civ, other, includeMinors = true, reverse = true) + other
        val holyCity = civ.religionManager.religion?.takeIf { it.foundingCivName == civ.civName }
        val capturedKeyCity = OpinionAi.isCapitalCapturedBy(civ, other) ||
            (holyCity != null && other.cities.any { it.isHolyCityOf(holyCity.name) })
        val endgameAggressive = VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)
        val approach = memory.approach ?: MajorCivApproach.Neutral
        val nonHostileApproach = approach == MajorCivApproach.Guarded || approach == MajorCivApproach.Afraid ||
            approach == MajorCivApproach.Neutral || approach == MajorCivApproach.Friendly
        val sinceCapture = if (memory.lastCityCaptureTurn < 0) Int.MAX_VALUE else civ.gameInfo.turns - memory.lastCityCaptureTurn
        val warScore = WarDamageAi.warScore(civ, other)
        val state = WarStateAi.warState(civ, other) ?: WarState.Calm
        val badWarState = state <= WarState.Defensive
        val nearlyWonNeighbour = !terrible && state == WarState.NearlyWon && DiplomacyTurn.proximity(civ, other) >= DiplomacyTurn.PROXIMITY_CLOSE
        val danger = CityDangerAi.comparativeDanger(civ, other, ourAllies, theirAllies)
        val duration = minOf(WarStateAi.turnsAtWar(civ, other), sinceCapture)

        // Part 6
        if (danger.seriousThem && !danger.seriousUs && duration < 40) return false
        if ((warScore <= -90 && danger.ours > 0) || (warScore <= -50 && (terrible || danger.seriousUs))) return true
        if (danger.seriousUs && !danger.seriousThem) return true
        if (danger.theirCitiesEndangered.isEmpty() || danger.seriousUs || terrible || duration >= 40) {
            if (memory.warProgress + warProgressPenalties(civ) <= 0) return true
        }
        // Their cities taken from us, near us or endangered (VP's "trying to liberate" has no counterpart here)
        val capturedAnyFromUs = capturedKeyCity || (!terrible && other.cities.any { city ->
            city.foundingCivObject == civ && (civ.cities.any { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) <= 12 } ||
                city in danger.theirCitiesEndangered)
        })
        // The vassalage prerequisites always fail here (no vassals, D3)
        if (nearlyWonNeighbour && duration < 30) return false

        // Part 7
        if (!(danger.seriousUs || terrible || nonHostileApproach || badWarState || duration >= 30)) return false

        // Part 8
        if (civ.cities.isNotEmpty() && other.cities.isEmpty() &&
            (WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average) < StrengthLevel.Poor) return false
        val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
        val theyAreTerrible = WarStateAi.isInTerribleShapeForWar(other)
        var score = when {
            warScore <= 0 -> warScore / -2
            terrible || danger.seriousUs -> warScore / 3
            else -> warScore / 5
        }
        val ourProximity = DiplomacyTurn.proximity(civ, other)
        if (duration > 15) {
            val penalty = duration - 10
            score += when (ourProximity) {
                DiplomacyTurn.PROXIMITY_NEIGHBORS -> penalty
                DiplomacyTurn.PROXIMITY_CLOSE -> penalty * 150 / 100
                1 -> penalty * 2
                else -> penalty * 3
            }
        }
        var ourMultiplier = 1
        var theirMultiplier = 1
        if (!terrible || state == WarState.NearlyWon) {
            if (strength < StrengthLevel.Average) ourMultiplier++
            if (WarStateAi.isEasyTarget(civ, other)) ourMultiplier++
            if (state == WarState.NearlyWon || theyAreTerrible) ourMultiplier++
        }
        if (!theyAreTerrible) {
            if (strength > StrengthLevel.Average) theirMultiplier++
            if (terrible) theirMultiplier++
            if (WarStateAi.isEasyTarget(other, civ)) theirMultiplier++
        }
        val negativeThreshold = WarIntentAi.warScoreThresholdNegative(civ)
        when {
            danger.ours == 0 && danger.theirs > 0 ->
                score -= (if (duration < 20 || ourMultiplier > 2 || danger.seriousThem) 5 else 2) * danger.theirs * ourMultiplier
            danger.ours > 0 && danger.theirs == 0 ->
                score += (if (warScore <= negativeThreshold / 2 || theirMultiplier > 2 || danger.seriousUs) 5 else 2) * danger.ours * theirMultiplier
            else -> score += 2 * danger.ours * theirMultiplier - 2 * danger.theirs * ourMultiplier
        }
        val losingOverall = WarStateAi.stateAllWars(civ) == StateAllWars.Losing
        score += when (state) {
            WarState.NearlyWon -> -10 * ourMultiplier
            WarState.Offensive -> -5 * ourMultiplier
            WarState.Calm -> if (losingOverall) 5 * theirMultiplier else -2 * ourMultiplier
            WarState.Stalemate -> if (losingOverall) 5 * theirMultiplier else 2 * theirMultiplier
            WarState.Defensive, WarState.NearlyDefeated -> if (losingOverall) 10 * theirMultiplier else 5 * theirMultiplier
        }
        var alliesMod = 0
        if (!terrible) {
            for (ally in ourAllies) {
                if (ally == civ || !ally.isMajorCiv || WarStateAi.isInTerribleShapeForWar(ally)) continue
                if (DiplomacyTurn.proximity(ally, civ) < DiplomacyTurn.PROXIMITY_CLOSE) continue
                if (DiplomacyTurn.proximity(ally, other) < DiplomacyTurn.PROXIMITY_CLOSE) continue
                if ((WarStateAi.warState(ally, other) ?: WarState.Calm) <= WarState.Defensive) continue
                alliesMod -= when (WarStateAi.militaryStrengthComparedToUs(ally, other) ?: StrengthLevel.Average) {
                    StrengthLevel.Average, StrengthLevel.Poor -> 5
                    StrengthLevel.Weak -> 10
                    StrengthLevel.Pathetic -> 15
                    else -> 0
                }
            }
        }
        if (alliesMod > -10 && CoopWarAi.globalState(civ, other) == CoopWarState.Accepted) alliesMod = -10
        if (warScore <= negativeThreshold && state < WarState.Offensive) alliesMod /= 2
        score += alliesMod
        if (WarIntentAi.wantsToConquer(civ, other)) score -= 10
        if (capturedKeyCity) score -= 15
        if (endgameAggressive) score -= 20
        if (score > 0) {
            if (!terrible) {
                val target = WarStateAi.targetValue(civ, other) ?: TargetValue.Average
                if (warScore >= 0 || (warScore > negativeThreshold / 2 && target >= TargetValue.Average && state > WarState.Defensive)) {
                    score = when {
                        capturedAnyFromUs -> if (warScore > duration / 2) score / 2 else score * 75 / 100
                        civ.ai.isGoingForWorldConquest() || VictoryProgressAi.isCloseToWorldConquest(civ, rules) -> {
                            val capitalTaken = other.cities.none { it.isOriginalCapital && it.foundingCivObject == other } &&
                                VictoryProgressAi.capitalsConquered(other) <= 0
                            when {
                                !capitalTaken -> score * 75 / 100
                                (civ.ai.diplomacy.memoryOf(other).economicStrength ?: StrengthLevel.Average) <= StrengthLevel.Weak ||
                                    target <= TargetValue.Bad -> score * 2
                                else -> score
                            }
                        }
                        else -> score
                    }
                }
            } else if (!capturedAnyFromUs || state == WarState.NearlyWon) {
                score *= 2
                if (!capturedAnyFromUs && state == WarState.NearlyWon) score *= 2
            }
        }
        if (warScore > 0) score = adjustConditionalModifier(score, civ.ai.diplomacyValue(DiplomacyPersonality.DiploBalance))
        else if (warScore < 0) score = adjustConditionalModifier(score, civ.ai.diplomacyValue(DiplomacyPersonality.Meanness), invert = true)

        // Part 9 (one enemy: the proximity weighting of the aggregate cancels out)
        val threshold = maxOf(
            rules.define(if (wantedBefore) AiDefine.REQUEST_PEACE_LEEWAY_THRESHOLD else AiDefine.REQUEST_PEACE_TURN_THRESHOLD) -
                maxOf(rules.define(if (wantedBefore) AiDefine.REQUEST_PEACE_LEEWAY_THRESHOLD_REDUCTION_PER_WAR
                    else AiDefine.REQUEST_PEACE_THRESHOLD_REDUCTION_PER_WAR) * otherWars, 0), 1)
        return score >= threshold
    }

    /**
     *  CvDiplomacyAI::DoUpdatePeaceTreatyOffers for [makePeace] (one enemy each: VP's proximity weighting cancels out): a negative war
     *  score is what we would give up, a positive one what we ask, as treaty levels by the PEACE_WILLINGNESS thresholds, white peace
     *  below them; in a [critical] state we accept white peace. We then want peace with them.
     */
    private fun updateOffers(civ: Civilization, makePeace: List<Civilization>, critical: Boolean) {
        val rules = civ.gameInfo.ruleset.ai
        for (other in makePeace) {
            val warScore = WarDamageAi.warScore(civ, other)
            val offerScore = if (warScore < 0) -warScore else 0
            val acceptScore = if (warScore >= 0) warScore else 0
            val memory = civ.ai.diplomacy.memoryOf(other)
            memory.treatyWillingToOffer = OFFER_LEVELS.firstOrNull { offerScore >= rules.define(it.first) }?.second ?: PeaceTreatyType.WhitePeace
            memory.treatyWillingToAccept = if (critical) PeaceTreatyType.WhitePeace
                else ACCEPT_LEVELS.firstOrNull { acceptScore >= rules.define(it.first) }?.second ?: PeaceTreatyType.WhitePeace
            memory.wantsPeace = true
        }
    }
}

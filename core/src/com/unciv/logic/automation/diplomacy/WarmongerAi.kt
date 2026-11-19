package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.MinorCivHelpers
import com.unciv.logic.automation.strategy.AttackTargetsAi
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min

/**
 *  How the other civilizations take a conquest, a war, a nuke or a liberation (Vox Populi, Release-5.4.8, CvDiplomacyAI.cpp: the
 *  CvDiplomacyAIHelpers functions GetWarmongerTriggerPenalty, GetCityWarmongerValue, GetCityLiberationValue, ApplyWarmongerPenalties
 *  and ApplyLiberationBonuses, and CvDiplomacyAI::DoUpdateWarmongerThreats, GetOtherPlayerWarmongerScore and the
 *  ChangeOtherPlayerNum*Attacked / NumPlayersNuked that apply the trigger penalties). They replace BNW's flat amounts.
 *
 *  Each observing major holds a warmonger amount against each other player ([MajorCivMemory.warmongerAmountTimes100], in hundredths).
 *  A city taken adds its worth to the observer: the city's economic value against the median city, scaled by what the observer owes the
 *  victim (defensive pact, coop war, being at war with the victim too), the strength of conqueror and victim as the observer sees them,
 *  the politics between the three (friendships, denouncements, religion, ideology, proximity), the victim's war score against the
 *  observer against the conqueror's, a resurrection, the World Congress and the era. A liberated city subtracts the same way. The score
 *  that sets the threat is the amount x the observer's WarmongerHate / 20.
 *
 *  Not ported, as they have no input here: vassals and masters (no vassalage, D3), a World War resolution, the sanction discounts
 *  (VP only, W8.7), the culture bomb and territory purchase triggers (no culture bombs, and VP raises them only for its buildings that
 *  claim tiles), a city's own count of times culture bombed, the warmonger and liberation preview texts of the UI, IsIgnoreWarmonger (set
 *  only by VP's third-party war purchase and independence) and the scenario hooks.
 */
object WarmongerAi {

    /** The WarmongerTriggerTypes this port raises: war declared on a city-state or a major, a nuke on a player */
    enum class Trigger { MinorAttacked, MajorAttacked, Nuked }

    //region Trigger penalties (war declarations, nukes)

    /**
     *  CvDiplomacyAIHelpers::GetWarmongerTriggerPenalty: how much [observer] holds [warmonger]'s [trigger] against it, in hundredths:
     *  the trigger's weight plus the earlier ones of the kind that the observer has seen, x (100 + the strength of the warmonger by
     *  the observer's view (+100 immense ... -50 pathetic) + for a trigger against a major that is not the observer or its pact partner,
     *  the strength of the strongest defender (-75 immense ... 0 average)) %, doubled under Global Peace Accords, x the era's
     *  warmonger percent. [defender] is the player attacked.
     */
    fun triggerPenalty(warmonger: Civilization, defender: Civilization, observer: Civilization, trigger: Trigger): Int {
        val rules = observer.gameInfo.ruleset.ai
        val memory = observer.ai.diplomacy.memoryOf(warmonger)
        var value = when (trigger) {
            Trigger.MinorAttacked -> rules.define(AiDefine.WARMONGER_THREAT_MINOR_ATTACKED_WEIGHT) +
                memory.numMinorsAttacked * rules.define(AiDefine.WARMONGER_THREAT_MINOR_PREVIOUSLY_ATTACKED_WEIGHT)
            Trigger.MajorAttacked -> rules.define(AiDefine.WARMONGER_THREAT_MAJOR_ATTACKED_WEIGHT) +
                memory.numMajorsAttacked * rules.define(AiDefine.WARMONGER_THREAT_MAJOR_PREVIOUSLY_ATTACKED_WEIGHT)
            Trigger.Nuked -> rules.define(AiDefine.WARMONGER_THREAT_PLAYER_NUKED_WEIGHT) +
                memory.numPlayersNuked * rules.define(AiDefine.WARMONGER_THREAT_PLAYER_PREVIOUSLY_NUKED_WEIGHT)
        }
        if (value <= 0) return 0

        var strengthModifier = attackerStrengthModifier(rules, strengthOf(observer, warmonger))
        if (trigger != Trigger.MinorAttacked && observer != defender && !hasDefensivePact(observer, defender) &&
            defender.isMajorCiv && defender.isAlive() && defender.cities.isNotEmpty())
            strengthModifier += defenderStrengthModifier(rules, strengthOf(observer, defender))
        value = value * max(0, 100 + strengthModifier) / 100
        if (peaceAccords(observer)) value = value * rules.define(AiDefine.WARMONGER_THREAT_ATTACKED_WEIGHT_WORLD_PEACE) / 100
        return value * eraPercent(observer) / 100
    }

    /**
     *  CvDiplomacyAI::ChangeOtherPlayerNumMinorsAttacked: [attacker] declared war on the city-state [victim]. Not for an observer that is
     *  dead, at war with the city-state too, or that has not met both; the penalty is added before the count rises. Call for each major
     *  that is not the attacker, once the war is declared.
     */
    fun onMinorAttacked(observer: Civilization, attacker: Civilization, victim: Civilization) {
        if (!observer.isAlive() || observer == attacker || observer.isAtWarWith(victim)) return
        if (!observer.knows(attacker) || !observer.knows(victim)) return
        val memory = observer.ai.diplomacy.memoryOf(attacker)
        memory.warmongerAmountTimes100 += triggerPenalty(attacker, victim, observer, Trigger.MinorAttacked)
        memory.numMinorsAttacked++
        updateThreats(observer, updateOnly = true)
    }

    /**
     *  CvDiplomacyAI::ChangeOtherPlayerNumMajorsAttacked: [attacker] declared war on the major [victim]. As [onMinorAttacked], and not
     *  between two humans unless the observer is the victim or its pact partner. Nor when the observer plans war on the victim or on one
     *  of its pact partners (an AI whose approach towards one is War, or - for the victim - that thinks them untrustworthy), or is locked
     *  into a coop war against one of them.
     */
    fun onMajorAttacked(observer: Civilization, attacker: Civilization, victim: Civilization) {
        if (!observer.isAlive() || observer == attacker || observer.isAtWarWith(victim)) return
        if (!observer.knows(attacker) || !(observer == victim || observer.knows(victim))) return
        if (observer != victim && !hasDefensivePact(observer, victim) && attacker.isHuman() && victim.isHuman()) return
        val rules = observer.gameInfo.ruleset.ai
        for (side in observer.gameInfo.civilizations) {
            if (!side.isMajorCiv || side == observer) continue
            if (side != victim && !hasDefensivePact(side, victim)) continue
            if (!observer.isHuman()) {
                if (observer.ai.diplomacy.approachTowards(side) == MajorCivApproach.War) return
                if (side == victim && OpinionAi.isUntrustworthyFriend(observer, side)) return
            }
            if (CoopWarAi.isLockedIntoCoopWar(observer, side, rules)) return
        }
        val memory = observer.ai.diplomacy.memoryOf(attacker)
        memory.warmongerAmountTimes100 += triggerPenalty(attacker, victim, observer, Trigger.MajorAttacked)
        memory.numMajorsAttacked++
        updateThreats(observer, updateOnly = true)
    }

    /**
     *  CvDiplomacyAI::ChangeOtherPlayerNumPlayersNuked: [attacker] nuked [victim] (a major or a city-state). As [onMinorAttacked], but
     *  also against an observer at war with the victim.
     */
    fun onNuked(observer: Civilization, attacker: Civilization, victim: Civilization) {
        if (!observer.isAlive() || observer == attacker) return
        if (!observer.knows(attacker) || !(observer == victim || observer.knows(victim))) return
        val memory = observer.ai.diplomacy.memoryOf(attacker)
        memory.warmongerAmountTimes100 += triggerPenalty(attacker, victim, observer, Trigger.Nuked)
        memory.numPlayersNuked++
        updateThreats(observer, updateOnly = true)
    }
    //endregion

    //region City values (conquest and liberation)

    /**
     *  CvGame::getMedianEconomicValue: the median of the economic values ([AttackTargetsAi.economicValue], for the city's owner) of all
     *  cities of living civilizations other than the barbarians; 0 without cities. The original keeps it from the turn's start.
     */
    fun medianEconomicValue(gameInfo: GameInfo): Int {
        val values = ArrayList<Int>()
        for (civ in gameInfo.civilizations) {
            if (civ.isBarbarian || !civ.isAlive()) continue
            for (city in civ.cities) values.add(AttackTargetsAi.economicValue(city, civ))
        }
        if (values.isEmpty()) return 0
        values.sort()
        return values[values.size / 2]
    }

    /**
     *  CvDiplomacyAIHelpers::ApplyWarmongerPenalties: [conqueror] (a major) takes [city] from [cityOwner]. Every living major other than
     *  the conqueror adds the city's [cityWarmongerValue] (whole points) to what it holds against the conqueror. Call while [city] is
     *  still the owner's: its number of times owned does not yet count the conqueror's coming ownership.
     */
    fun applyWarmongerPenalties(city: City, conqueror: Civilization, cityOwner: Civilization) {
        if (!conqueror.isMajorCiv || cityOwner.isBarbarian) return
        val median = medianEconomicValue(conqueror.gameInfo)
        for (observer in conqueror.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == conqueror || !observer.isAlive()) continue
            val value = cityWarmongerValue(city, conqueror, cityOwner, observer, median)
            if (value <= 0) continue
            observer.ai.diplomacy.memoryOf(conqueror).warmongerAmountTimes100 += value * 100
            updateThreats(observer, updateOnly = true)
        }
    }

    /**
     *  CvDiplomacyAIHelpers::ApplyLiberationBonuses: [liberator] (a major) gives [city] back to [newOwner]. Every living major other than
     *  the liberator takes the city's [cityLiberationValue] off what it holds against the liberator.
     */
    fun applyLiberationBonuses(city: City, liberator: Civilization, newOwner: Civilization) {
        if (!liberator.isMajorCiv || newOwner.isBarbarian) return
        val median = medianEconomicValue(liberator.gameInfo)
        for (observer in liberator.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == liberator || !observer.isAlive()) continue
            val value = cityLiberationValue(city, liberator, newOwner, observer, median)
            if (value >= 0) continue
            observer.ai.diplomacy.memoryOf(liberator).warmongerAmountTimes100 =
                max(0, observer.ai.diplomacy.memoryOf(liberator).warmongerAmountTimes100 + value * 100)
            updateThreats(observer, updateOnly = true)
        }
    }

    /** The city's worth as a share of the median city: half the percentage of the median, between 5 and 100, x the define's percent */
    private fun cityWorth(rules: AiRuleset, economicValue: Int, median: Int): Int {
        val share = (economicValue * 100 / max(1, median) / 2).coerceIn(5, 100)
        return share * rules.define(AiDefine.WARMONGER_THREAT_CITY_VALUE_MULTIPLIER) / 100
    }

    /** CvCity::IsOriginalCapitalForPlayer && IsOriginalMajorCapital */
    private fun isOriginalMajorCapitalOf(city: City, civ: Civilization) =
        city.isOriginalCapital && civ.isMajorCiv && city.foundingCivObject == civ

    /**
     *  CvDiplomacyAIHelpers::GetCityWarmongerValue: what [observer] holds against [conqueror] for taking [city] from [cityOwner], in
     *  whole points; 0 when the observer has not met both, the city was the conqueror's own to start with or last, the owner is a
     *  barbarian, or the observer fights the owner and is winning that war less than losing it (its war score against the owner
     *  is negative: the conqueror did it a favour). The steps, each ending at 0 when nothing is left:
     *  - The city's worth: its economic value for its owner as a share of the [median] city (half the percentage, 5 to 100), x 1.5 for the
     *    owner's original capital; / (times the conqueror owned it + 2) when it owned it before.
     *  - Status: x 200% when the owner is the observer or its defensive pact partner, or a coop war against the conqueror is on (the
     *    loss is the observer's own); x 10% when a coop war with the conqueror against the owner is on; -25 points when the observer
     *    is at war with the owner.
     *  - Strength, as the observer sees them: + the conqueror's (+100 immense ... -50 pathetic, not below 0 when the loss is the
     *    observer's own) + the owner's (-75 immense ... 0 average; not when the loss is the observer's own), at least 25%.
     *  - Politics, unless the loss is the observer's own: a city-state owner by the observer's influence (+75 ally, +50 friend, else -25)
     *    and protection (+50 protector, -25 when not an ally); a major owner by the observer's friendship with the owner (+25 friends, +50
     *    allies, +75 battle brothers; else -25 each for a denouncement either way and -50 for an untrustworthy owner), with the conqueror
     *    (the opposite signs: -25, -50; +25 each, +75), religion (the observer's own religion: -25 when the conqueror shares it, -25
     *    more when the owner is of an opposing one; +50 when the owner shares it and the conqueror opposes), ideology (-50 when the
     *    conqueror shares the observer's, -50 more when the owner opposes it; +50 when the conqueror opposes it, +50 more when the owner
     *    shares it) and the conqueror's proximity to the observer (-25 distant, +25 far, +50 close, +75 neighbours); x (100 + that) %,
     *    between 25% and 200%.
     *  - Threat, unless the loss is the observer's own: x (100 + the owner's war score against the observer - the conqueror's) %, between
     *    25% and 100%; a war score is the worse of the current and the most recent one against the owner, the better of them against
     *    the conqueror unless it is beating the observer now.
     *  - Resurrection: / 2 when the observer was resurrected by the conqueror or resurrected it; x 2 for the owner by the same.
     *  - Global Peace Accords x 200%, then the era's warmonger percent.
     */
    fun cityWarmongerValue(city: City, conqueror: Civilization, cityOwner: Civilization, observer: Civilization, median: Int): Int {
        val rules = observer.gameInfo.ruleset.ai
        val ai = observer.ai.diplomacy
        if (!hasMet(observer, conqueror) || !hasMet(observer, cityOwner)) return 0
        if (cityOwner.isBarbarian) return 0
        // Not for taking back its own city, or the one it owned last
        if (city.foundingCivObject == conqueror || city.previousOwner == conqueror.civID) return 0
        if (observer.isAtWarWith(cityOwner) && observer.cities.isNotEmpty() && WarDamageAi.warScore(observer, cityOwner) < 0) return 0

        var value = cityWorth(rules, AttackTargetsAi.economicValue(city, cityOwner), median)
        if (isOriginalMajorCapitalOf(city, cityOwner)) value = value * rules.define(AiDefine.WARMONGER_THREAT_CAPITAL_CITY_PERCENT) / 100
        val timesOwned = city.getNumTimesOwned(conqueror.civID)
        if (timesOwned > 0) value /= timesOwned + 2
        if (value <= 0) value = 1

        // Status
        var status = 100
        var lossIsOurOwn = false
        if (cityOwner.isMajorCiv) {
            if (CoopWarAi.state(observer, conqueror, cityOwner) == CoopWarState.Accepted) {
                status = rules.define(AiDefine.WARMONGER_THREAT_COOP_WAR_PERCENT)
            } else if (CoopWarAi.state(observer, cityOwner, conqueror) == CoopWarState.Accepted ||
                hasDefensivePact(observer, cityOwner) || observer == cityOwner) {
                status = max(100, rules.define(AiDefine.WARMONGER_THREAT_SHARED_FATE_PERCENT))
                lossIsOurOwn = true
            } else if (observer.isAtWarWith(cityOwner)) {
                status += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
            }
        } else if (observer.isAtWarWith(cityOwner)) {
            status += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
        }
        value = value * max(0, status) / 100
        if (value <= 0) return 0

        // Strength
        var strength = attackerStrengthModifier(rules, strengthOf(observer, conqueror))
        if (lossIsOurOwn && strength < 0) strength = 0
        if (!lossIsOurOwn) strength += defenderStrengthModifier(rules, strengthOf(observer, cityOwner))
        value = value * max(25, 100 + strength) / 100
        if (value <= 0) return 0

        // Politics
        var politics = 0
        if (!lossIsOurOwn) {
            if (cityOwner.isCityState) politics += cityStateModifier(rules, observer, cityOwner)
            else {
                politics += ownerFriendshipModifier(rules, observer, cityOwner)
                politics += conquerorFriendshipModifier(rules, observer, conqueror)
                val ownReligion = OpinionAi.stateReligion(observer)?.takeIf { it.second }?.first
                if (ownReligion != null) {
                    if (sameReligion(observer, conqueror)) {
                        politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
                        if (opposingReligion(observer, cityOwner) && !OpinionAi.isIgnoreReligionDifferences(observer, cityOwner))
                            politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
                    } else if (sameReligion(observer, cityOwner) && opposingReligion(observer, conqueror) &&
                        !OpinionAi.isIgnoreReligionDifferences(observer, conqueror))
                        politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
                }
                if (sameIdeology(observer, conqueror)) {
                    politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
                    if (opposingIdeology(observer, cityOwner) && !OpinionAi.isIgnoreIdeologyDifferences(observer, cityOwner))
                        politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
                } else if (opposingIdeology(observer, conqueror) && !OpinionAi.isIgnoreIdeologyDifferences(observer, conqueror)) {
                    politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
                    if (sameIdeology(observer, cityOwner)) politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
                }
                politics += proximityModifier(rules, conqueror, observer)
            }
        }
        value = value * (100 + politics).coerceIn(25, max(100, rules.define(AiDefine.WARMONGER_THREAT_SHARED_FATE_PERCENT))) / 100
        if (value <= 0) return 0

        // Threat: a victim that is a bigger threat to the observer than the conqueror is
        var threat = 0
        if (!lossIsOurOwn) {
            val ownerScore = WarDamageAi.mostRecentWarScore(observer, cityOwner).let {
                if (observer.isAtWarWith(cityOwner)) min(WarDamageAi.warScore(observer, cityOwner), it) else it
            }
            var attackerScore = WarDamageAi.mostRecentWarScore(observer, conqueror)
            if (observer.isAtWarWith(conqueror)) {
                val current = WarDamageAi.warScore(observer, conqueror)
                attackerScore = if (current < 0) current else max(current, attackerScore)
            }
            threat = ownerScore - attackerScore
        }
        value = value * (100 + threat).coerceIn(25, 100) / 100
        if (value <= 0) return 0

        // Resurrection
        if (ai.memoryOf(conqueror).resurrectedUs || conqueror.ai.diplomacy.memoryOf(observer).resurrectedUs) value /= 2
        if (cityOwner.isMajorCiv && (ai.memoryOf(cityOwner).resurrectedUs || cityOwner.ai.diplomacy.memoryOf(observer).resurrectedUs)) value *= 2
        if (value <= 0) return 0

        if (peaceAccords(observer)) value = value * rules.define(AiDefine.WARMONGER_THREAT_ATTACKED_WEIGHT_WORLD_PEACE) / 100
        return max(value * eraPercent(observer) / 100, 0)
    }

    /**
     *  CvDiplomacyAIHelpers::GetCityLiberationValue: what [observer] takes off what it holds against [liberator] for giving [city] back
     *  to [newOwner], in whole points, 0 or negative; 0 when the observer has not met both, the city went to the liberator's own side
     *  or (the observer being someone else) to someone who had owned it or founded it, or the observer fights the new owner. The steps
     *  mirror [cityWarmongerValue] with the sign flipped:
     *  - The city's worth: its economic value for the liberator ([median] city), x 1.5 for the new owner's original capital; / (times
     *    the liberator owned it + 2).
     *  - Status: x 200% when the new owner is the observer or its pact partner (+ 200% more when it is the observer: a city for me!).
     *  - Strength: the liberator's inverted (weak liberators earn more: +100 pathetic ... -50 immense, not below 0 when the gain is the
     *    observer's own) + the new owner's (-75 immense ... 0 average; not when the gain is the observer's own), at least 25%.
     *  - Politics, unless the gain is the observer's own: as the warmonger value for the new owner, with the liberator's side flipped
     *    (+25 friends, +50 allies; -25 for a denouncement either way, -50 untrustworthy), religion (+25 when the new owner shares the
     *    observer's, else -25 when it opposes it), ideology (+50 when the new owner shares the observer's, +50 more when the liberator
     *    does; else -50 when the new owner opposes it, -50 more when the liberator does) and the liberator's proximity; x (100 + that) %,
     *    between 25% and 200%.
     *  - Resurrection: x 2 when the observer was resurrected by the liberator or resurrected it, and again for the new owner.
     *  - Global Peace Accords x 200%, then the era's warmonger percent.
     */
    fun cityLiberationValue(city: City, liberator: Civilization, newOwner: Civilization, observer: Civilization, median: Int): Int {
        val rules = observer.gameInfo.ruleset.ai
        val ai = observer.ai.diplomacy
        if (!hasMet(observer, liberator) || !hasMet(observer, newOwner)) return 0
        if (observer.isAtWarWith(newOwner)) return 0
        // A city we owned before, and they give it to someone else: no bonus
        if (newOwner != observer && city.getNumTimesOwned(observer.civID) > 0) return 0

        var value = cityWorth(rules, AttackTargetsAi.economicValue(city, liberator), median)
        if (isOriginalMajorCapitalOf(city, newOwner)) value = value * rules.define(AiDefine.WARMONGER_THREAT_CAPITAL_CITY_PERCENT) / 100
        val timesOwned = city.getNumTimesOwned(liberator.civID)
        if (timesOwned > 0) value /= timesOwned + 2
        if (value <= 0) value = 1
        value = -value

        // Status
        var status = 100
        var gainIsOurOwn = false
        if (newOwner.isMajorCiv && (hasDefensivePact(observer, newOwner) || observer == newOwner)) {
            status = max(100, rules.define(AiDefine.WARMONGER_THREAT_SHARED_FATE_PERCENT))
            gainIsOurOwn = true
            if (observer == newOwner) status += max(0, rules.define(AiDefine.WARMONGER_THREAT_LIBERATED_TEAM_BONUS_PERCENT))
        }
        value = value * max(0, status) / 100
        if (value >= 0) return 0

        // Strength: the values used for warmongering, flipped
        var strength = invertedAttackerStrengthModifier(rules, strengthOf(observer, liberator))
        if (gainIsOurOwn && strength < 0) strength = 0
        if (!gainIsOurOwn) strength += defenderStrengthModifier(rules, strengthOf(observer, newOwner))
        value = value * max(25, 100 + strength) / 100
        if (value >= 0) return 0

        // Politics
        var politics = 0
        if (!gainIsOurOwn) {
            if (newOwner.isCityState) politics += cityStateModifier(rules, observer, newOwner)
            else {
                politics += ownerFriendshipModifier(rules, observer, newOwner)
                politics += liberatorFriendshipModifier(rules, observer, liberator)
                if (OpinionAi.stateReligion(observer)?.second == true) {
                    if (sameReligion(observer, newOwner)) politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_SMALL)
                    else if (opposingReligion(observer, newOwner) && !OpinionAi.isIgnoreReligionDifferences(observer, newOwner))
                        politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
                }
                if (sameIdeology(observer, newOwner)) {
                    politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
                    if (sameIdeology(observer, liberator)) politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
                } else if (opposingIdeology(observer, newOwner) && !OpinionAi.isIgnoreIdeologyDifferences(observer, newOwner)) {
                    politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
                    if (opposingIdeology(observer, liberator) && !OpinionAi.isIgnoreIdeologyDifferences(observer, liberator))
                        politics += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
                }
                politics += proximityModifier(rules, liberator, observer)
            }
        }
        value = value * (100 + politics).coerceIn(25, max(100, rules.define(AiDefine.WARMONGER_THREAT_SHARED_FATE_PERCENT))) / 100
        if (value >= 0) return 0

        // Resurrection
        if (ai.memoryOf(liberator).resurrectedUs || liberator.ai.diplomacy.memoryOf(observer).resurrectedUs) value *= 2
        if (newOwner.isMajorCiv && (ai.memoryOf(newOwner).resurrectedUs || newOwner.ai.diplomacy.memoryOf(observer).resurrectedUs)) value *= 2
        if (value >= 0) return 0

        if (peaceAccords(observer)) value = value * rules.define(AiDefine.WARMONGER_THREAT_ATTACKED_WEIGHT_WORLD_PEACE) / 100
        return min(value * eraPercent(observer) / 100, 0)
    }

    private fun attackerStrengthModifier(rules: AiRuleset, strength: StrengthLevel) = rules.define(when (strength) {
        StrengthLevel.Immense -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_IMMENSE
        StrengthLevel.Powerful -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_POWERFUL
        StrengthLevel.Strong -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_STRONG
        StrengthLevel.Average -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_AVERAGE
        StrengthLevel.Poor -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_POOR
        StrengthLevel.Weak -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_WEAK
        StrengthLevel.Pathetic -> AiDefine.WARMONGER_THREAT_ATTACKER_STRENGTH_PATHETIC
    })

    /** The attacker table read from the other end: an immense liberator earns what a pathetic warmonger would be charged */
    private fun invertedAttackerStrengthModifier(rules: AiRuleset, strength: StrengthLevel) =
        attackerStrengthModifier(rules, StrengthLevel.values()[StrengthLevel.Immense.ordinal - strength.ordinal])

    private fun defenderStrengthModifier(rules: AiRuleset, strength: StrengthLevel) = when (strength) {
        StrengthLevel.Immense -> rules.define(AiDefine.WARMONGER_THREAT_DEFENDER_STRENGTH_IMMENSE)
        StrengthLevel.Powerful -> rules.define(AiDefine.WARMONGER_THREAT_DEFENDER_STRENGTH_POWERFUL)
        StrengthLevel.Strong -> rules.define(AiDefine.WARMONGER_THREAT_DEFENDER_STRENGTH_STRONG)
        StrengthLevel.Average -> rules.define(AiDefine.WARMONGER_THREAT_DEFENDER_STRENGTH_AVERAGE)
        StrengthLevel.Poor, StrengthLevel.Weak, StrengthLevel.Pathetic -> 0
    }

    /** CvDiplomacyAI::GetMilitaryStrengthComparedToUs; the original starts every player at pathetic */
    private fun strengthOf(observer: Civilization, other: Civilization) =
        WarStateAi.militaryStrengthComparedToUs(observer, other) ?: StrengthLevel.Pathetic

    /** CvMinorCivAI: the observer's influence with the city-state (ally from 60, friend from 30) and its protection */
    private fun cityStateModifier(rules: AiRuleset, observer: Civilization, cityState: Civilization): Int {
        val influence = cityState.getDiplomacyManager(observer)?.getInfluence() ?: 0f
        val ally = influence >= MinorCivHelpers.ALLIES_THRESHOLD
        var modifier = when {
            ally -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_LARGE)
            influence >= MinorCivHelpers.FRIENDS_THRESHOLD -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
            else -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
        }
        if (observer in cityState.cityStateFunctions.getProtectorCivs()) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
        else if (!ally) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
        return modifier
    }

    /**
     *  The observer's friendship with the city's owner or the new owner: a friend by declaration or pact counts by the friendship's
     *  history (friends +25, allies +50, battle brothers +75; a new or untrustworthy one nothing); else -25 for each denouncement
     *  either way and -50 for an untrustworthy owner.
     */
    private fun ownerFriendshipModifier(rules: AiRuleset, observer: Civilization, owner: Civilization): Int {
        if (isFriend(observer, owner)) return when (observer.ai.diplomacy.memoryOf(owner).doFLevel) {
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
            DoFLevel.Friends -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_SMALL)
            DoFLevel.Allies -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
            DoFLevel.BattleBrothers -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_LARGE)
        }
        var modifier = 0
        if (OpinionAi.isDenouncedPlayer(observer, owner)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
        if (OpinionAi.isDenouncedPlayer(owner, observer)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
        if (OpinionAi.isUntrustworthyFriend(observer, owner)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
        return modifier
    }

    /**
     *  The observer's friendship with the conqueror, as [ownerFriendshipModifier] with the opposite signs: friends -25, allies and
     *  battle brothers -50; else +25 for each denouncement either way and +75 for an untrustworthy conqueror.
     */
    private fun conquerorFriendshipModifier(rules: AiRuleset, observer: Civilization, conqueror: Civilization): Int {
        if (isFriend(observer, conqueror)) return when (observer.ai.diplomacy.memoryOf(conqueror).doFLevel) {
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
            DoFLevel.Friends -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL)
            DoFLevel.Allies, DoFLevel.BattleBrothers -> rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_MEDIUM)
        }
        var modifier = 0
        if (OpinionAi.isDenouncedPlayer(observer, conqueror)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_SMALL)
        if (OpinionAi.isDenouncedPlayer(conqueror, observer)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_SMALL)
        if (OpinionAi.isUntrustworthyFriend(observer, conqueror)) modifier += rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_LARGE)
        return modifier
    }

    /** The observer's friendship with the liberator: friends +25, allies and battle brothers +50; else -25 per denouncement, -50 untrustworthy */
    private fun liberatorFriendshipModifier(rules: AiRuleset, observer: Civilization, liberator: Civilization): Int {
        val small = rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_SMALL)
        val medium = rules.define(AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM)
        if (isFriend(observer, liberator)) return when (observer.ai.diplomacy.memoryOf(liberator).doFLevel) {
            DoFLevel.Untrustworthy, DoFLevel.New -> 0
            DoFLevel.Friends -> small
            DoFLevel.Allies, DoFLevel.BattleBrothers -> medium
        }
        var modifier = 0
        if (OpinionAi.isDenouncedPlayer(observer, liberator)) modifier -= small
        if (OpinionAi.isDenouncedPlayer(liberator, observer)) modifier -= small
        if (OpinionAi.isUntrustworthyFriend(observer, liberator)) modifier -= medium
        return modifier
    }

    /** The warmonger's (or liberator's) proximity to the observer: distant or none -25, far +25, close +50, neighbours +75 */
    private fun proximityModifier(rules: AiRuleset, actor: Civilization, observer: Civilization) = rules.define(when (actor.getProximity(observer)) {
        Proximity.None, Proximity.Distant -> AiDefine.WARMONGER_THREAT_MODIFIER_NEGATIVE_SMALL
        Proximity.Far -> AiDefine.WARMONGER_THREAT_MODIFIER_SMALL
        Proximity.Close -> AiDefine.WARMONGER_THREAT_MODIFIER_MEDIUM
        Proximity.Neighbors -> AiDefine.WARMONGER_THREAT_MODIFIER_LARGE
    })

    private fun isFriend(observer: Civilization, other: Civilization) = OpinionAi.isDoFAccepted(observer, other) || hasDefensivePact(observer, other)

    private fun hasDefensivePact(a: Civilization, b: Civilization) = a.getDiplomacyManager(b)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** CvTeam::isHasMet: Unciv has no teams, so every player has met itself */
    private fun hasMet(observer: Civilization, other: Civilization) = observer == other || observer.knows(other)

    /** CvDiplomacyAI::IsPlayerSameReligion: both have a state religion and it is the same one */
    private fun sameReligion(observer: Civilization, other: Civilization): Boolean {
        val ours = OpinionAi.stateReligion(observer)?.first ?: return false
        return OpinionAi.stateReligion(other)?.first == ours
    }

    /** CvDiplomacyAI::IsPlayerOpposingReligion: both have a state religion and they differ */
    private fun opposingReligion(observer: Civilization, other: Civilization): Boolean {
        val ours = OpinionAi.stateReligion(observer)?.first ?: return false
        val theirs = OpinionAi.stateReligion(other)?.first ?: return false
        return ours != theirs
    }

    /** CvDiplomacyAI::IsPlayerSameIdeology: both have an ideology and it is the same one */
    private fun sameIdeology(observer: Civilization, other: Civilization): Boolean {
        val ours = observer.getIdeology() ?: return false
        return other.getIdeology() == ours
    }

    /** CvDiplomacyAI::IsPlayerOpposingIdeology: both have an ideology and they differ */
    private fun opposingIdeology(observer: Civilization, other: Civilization): Boolean {
        val ours = observer.getIdeology() ?: return false
        val theirs = other.getIdeology() ?: return false
        return ours != theirs
    }

    /** CvGameLeagues::GetUnitMaintenanceMod(observer) > 0: Global Peace Accords (Standing Army Tax), which every living civilization joins */
    private fun peaceAccords(observer: Civilization): Boolean {
        val league = observer.gameInfo.league ?: return false
        if (!league.isMember(observer)) return false
        return league.getActiveUniques().filter { it.type == UniqueType.UnitGoldMaintenancePercent }.sumOf { it.params[0].toInt() } > 0
    }

    /** CvEraInfo::getWarmongerPercent of the game's current era (the average of the living civilizations' eras, [DiplomacyTurn.gameEraName]) */
    private fun eraPercent(observer: Civilization) =
        observer.gameInfo.ruleset.ai.eraDiplomacy(DiplomacyTurn.gameEraName(observer)).warmongerPercent
    //endregion

    //region Scores and threats

    /** CvDiplomacyAI::GetOtherPlayerWarmongerScore: the warmonger amount (whole points) x our WarmongerHate (1 to 10) / 20 */
    fun warmongerScore(civ: Civilization, other: Civilization): Int =
        civ.ai.diplomacy.memoryOf(other).warmongerAmountTimes100 / 100 * civ.ai.diplomacyValue(DiplomacyPersonality.WarmongerHate) / 20

    /**
     *  CvDiplomacyAI::DoUpdateWarmongerThreats: the warmonger threat of every other major, from its [warmongerScore] (minor from
     *  WARMONGER_THREAT_MINOR_THRESHOLD, then major, severe, critical), and from the share of the majors ever in the game it has killed
     *  (a major counts 1, a city-state half): severe from the severe percentage, critical from the critical one - not for one that
     *  resurrected us, unless it is untrustworthy. A player we do not know, or that is dead or without cities, is no threat.
     *  Unless [updateOnly] the amounts first decay (WARMONGER_THREAT_PER_TURN_DECAY, at least 1 point), 50 to 300% by the raw strength
     *  of the other (slower for the stronger), 200% when we are friends or have a pact, 50% under Global Peace Accords.
     */
    fun updateThreats(civ: Civilization, updateOnly: Boolean) {
        val rules = civ.gameInfo.ruleset.ai
        val majorsEver = civ.gameInfo.civilizations.count { it.isMajorCiv }
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || other == civ) continue
            val memory = civ.ai.diplomacy.memoryOf(other)
            if (!WarStateAi.isPlayerValid(civ, other)) {
                memory.warmongerThreat = AiThreat.None
                continue
            }
            if (!updateOnly) {
                var modifier = rules.define(when (WarStateAi.rawMilitaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Pathetic) {
                    StrengthLevel.Immense -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_IMMENSE
                    StrengthLevel.Powerful -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_POWERFUL
                    StrengthLevel.Strong -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_STRONG
                    StrengthLevel.Average -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_AVERAGE
                    StrengthLevel.Poor -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_POOR
                    StrengthLevel.Weak -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_WEAK
                    StrengthLevel.Pathetic -> AiDefine.WARMONGER_THREAT_STRENGTH_DECAY_PATHETIC
                })
                if (isFriend(civ, other) || memory.doFLevel >= DoFLevel.Friends)
                    modifier = modifier * rules.define(AiDefine.WARMONGER_THREAT_PER_TURN_DECAY_INCREASED_FRIENDS) / 100
                if (peaceAccords(civ)) modifier = modifier * rules.define(AiDefine.WARMONGER_THREAT_PER_TURN_DECAY_DECREASED) / 100
                val decay = rules.define(AiDefine.WARMONGER_THREAT_PER_TURN_DECAY) * modifier / 100
                memory.warmongerAmountTimes100 = max(0, memory.warmongerAmountTimes100 + min(decay, rules.define(AiDefine.WARMONGER_THREAT_PER_TURN_DECAY_MINIMUM)))
            }
            val score = warmongerScore(civ, other)
            var threat = when {
                score >= rules.define(AiDefine.WARMONGER_THREAT_CRITICAL_THRESHOLD) -> AiThreat.Critical
                score >= rules.define(AiDefine.WARMONGER_THREAT_SEVERE_THRESHOLD) -> AiThreat.Severe
                score >= rules.define(AiDefine.WARMONGER_THREAT_MAJOR_THRESHOLD) -> AiThreat.Major
                score >= rules.define(AiDefine.WARMONGER_THREAT_MINOR_THRESHOLD) -> AiThreat.Minor
                else -> AiThreat.None
            }
            if ((!memory.resurrectedUs || OpinionAi.isUntrustworthyFriend(civ, other)) && threat != AiThreat.Critical) {
                val aggression = civ.ai.aggressionOf(other)
                val killed = aggression.majorsConquered + aggression.minorsConquered / 2
                val percent = killed * 100 / max(1, majorsEver)
                if (percent >= rules.define(AiDefine.WARMONGER_THREAT_CRITICAL_PERCENT_THRESHOLD)) threat = AiThreat.Critical
                else if (percent >= rules.define(AiDefine.WARMONGER_THREAT_SEVERE_PERCENT_THRESHOLD)) threat = AiThreat.Severe
            }
            memory.warmongerThreat = threat
        }
    }
    //endregion
}

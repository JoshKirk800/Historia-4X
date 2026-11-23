package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.diplomacy.WarIntentAi
import com.unciv.logic.automation.strategy.MilitaryTargets
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.automation.strategy.EconomicAiRatios
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarAlliesAi
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 *  What a major civilization's AI does about city-states (CvDiplomacyAI.cpp): the approach to each city-state (Vox Populi's
 *  DoUpdateMinorCivApproaches / SelectBestApproachTowardsMinorCiv), and DoContactMinorCivs: protection pledges and their withdrawal
 *  (DoUpdateMinorCivProtection), Austria's buyout, gold gifts (with the econ AI's MINOR_CIV_GIFT savings), and tribute of a worker or of
 *  gold. Also CvMinorCivAI::DoPickPersonality's flavor change for a city-state ([pickInitialFlavors]).
 *
 *  Proximity follows the original's PlayerProximityTypes ranks ([DiplomacyTurn.proximity]): DISTANT 0 .. NEIGHBORS 3.
 */
object CityStateAi {

    private fun Civilization.define(define: AiDefine) = gameInfo.ruleset.ai.define(define)

    //region Approaches

    /**
     *  Vox Populi's CvDiplomacyAI::DoUpdateMinorCivApproaches (Release-5.4.8; D7) for the city-states we have met: a human player's AI
     *  takes war at war, friendly with friends, allies or a pledge, else neutral; a city-state without cities gets war at war, else
     *  neutral; without cities of our own, neutral. The others are chosen by [selectBestApproach], nearest first (1000 - the plot
     *  distance of their capital to our closest city by path length; ties in player order). Then the first city-state that gets
     *  hostile becomes the bully target and the first that gets war the war target ([DiplomacyAi.csBullyTarget],
     *  [DiplomacyAi.csWarTarget]); a further hostile or war approach is set to neutral. As in VP, a target stays until another is
     *  chosen. The potential war target flag has no reader here (none).
     */
    fun updateMinorCivApproaches(civ: Civilization) = timeThis("AI.MinorCivApproaches") {
        val ai = civ.ai
        val human = civ.isHuman()
        val noCities = civ.getCapital() == null
        val toUpdate = ArrayList<Civilization>()
        for (minor in civ.gameInfo.civilizations) {
            if (!minor.isCityState || !minor.isAlive() || !civ.knows(minor)) continue
            ai.minorCivApproaches[minor.civID] = when {
                human -> when {
                    civ.isAtWarWith(minor) -> MinorCivApproach.Conquest
                    isAllies(minor, civ) || isFriends(minor, civ) || isProtectedBy(minor, civ) -> MinorCivApproach.Friendly
                    else -> MinorCivApproach.Ignore
                }
                minor.getCapital() == null -> if (civ.isAtWarWith(minor)) MinorCivApproach.Conquest else MinorCivApproach.Ignore
                noCities -> MinorCivApproach.Ignore
                else -> { toUpdate.add(minor); continue }
            }
        }
        if (human || noCities) return@timeThis
        val ordered = toUpdate.mapNotNull { minor ->
            val capital = minor.getCapital()!!.getCenterTile()
            val (closest, _) = MilitaryTargets.closestCityByPathLength(civ, minor, capital, allowSea = false) ?: return@mapNotNull null
            minor to 1000 - closest.getCenterTile().aerialDistanceTo(capital)
        }.sortedByDescending { it.second }
        for ((minor, _) in ordered) ai.minorCivApproaches[minor.civID] = selectBestApproach(civ, minor)
        var bullySet = false
        var warSet = false
        for ((minor, _) in ordered) {
            when (ai.minorCivApproaches[minor.civID]) {
                MinorCivApproach.Bully -> if (!bullySet) {
                    ai.diplomacy.csBullyTarget = minor.civID
                    bullySet = true
                } else ai.minorCivApproaches[minor.civID] = MinorCivApproach.Ignore
                MinorCivApproach.Conquest -> if (!warSet) {
                    ai.diplomacy.csWarTarget = minor.civID
                    warSet = true
                } else ai.minorCivApproaches[minor.civID] = MinorCivApproach.Ignore
                else -> Unit
            }
        }
    }

    /** CvMinorCivAI::IsFriends: influence at the friends threshold or more */
    fun isFriends(minor: Civilization, major: Civilization) = MinorCivHelpers.influence(minor, major) >= MinorCivHelpers.FRIENDS_THRESHOLD

    /** CvMinorCivAI::IsAllies */
    fun isAllies(minor: Civilization, major: Civilization) = minor.allyCiv == major

    /** CvMinorCivAI::IsProtectedByMajor */
    fun isProtectedBy(minor: Civilization, major: Civilization) = major in minor.cityStateFunctions.getProtectorCivs()

    /** CvMinorCivAI::CanMajorProtect: not at war, influence not negative, not in the lock-out after a pledge ended (Unciv's 20 turns after a withdrawal) */
    fun canMajorProtect(minor: Civilization, major: Civilization): Boolean {
        // with `cityStateProtectionLikeVp` the whole rule: the military and the ally, trade route or possible route
        if (minor.gameInfo.ruleset.modOptions.constants.cityStateProtectionLikeVp) return minor.cityStateFunctions.canMajorProtect(major, ignoreMilitaryRequirement = false)
        if (minor.isAtWarWith(major)) return false
        val diplomacy = minor.getDiplomacyManager(major) ?: return false
        if (diplomacy.getInfluence() < 0) return false
        return !diplomacy.hasFlag(DiplomacyFlags.RecentlyWithdrewProtection)
    }

    private fun hasQuest(minor: Civilization, major: Civilization, quest: QuestName) =
        minor.questManager.getAssignedQuestsFor(major).any { it.questName == quest.value }

    /** CvPlayerTraits::GetCityStateFriendshipModifier > 0 (Greece): slower influence decay or faster recovery from the civilization's trait */
    private fun hasFriendshipTrait(civ: Civilization) = civ.nation.uniqueObjects.any {
        it.type == UniqueType.CityStateInfluenceRecoversTwiceNormalRate
            || it.type == UniqueType.CityStateInfluenceDegradation && it.params[0].toInt() < 0
    }

    /** CvPlayerTraits::GetCityStateBonusModifier > 0 (Siam): more yields from city-states from the civilization's trait */
    private fun hasBonusTrait(civ: Civilization) = civ.nation.uniqueObjects.any {
        it.type == UniqueType.BonusStatsFromCityStates && it.params[0].toInt() > 0
    }

    /** CvPlayerTraits::GetCityStateCombatModifier > 0 (Mongolia): more strength against city-states from the civilization's trait */
    private fun hasCombatTrait(civ: Civilization) = civ.nation.uniqueObjects.any { unique ->
        unique.type == UniqueType.Strength && unique.params[0].toInt() > 0
            && unique.modifiers.any { modifier -> modifier.params.any { it == "City-States" } }
    }

    /** VP's CivApproachTypes order of the four city-state approaches, for its stable sort: war, hostile, neutral, friendly */
    private val VP_ORDER = listOf(MinorCivApproach.Conquest, MinorCivApproach.Bully, MinorCivApproach.Ignore, MinorCivApproach.Friendly)

    /** A player modifier from the uniques of policies, beliefs and buildings, not the nation (VP keeps traits apart from these) */
    private fun playerModifier(civ: Civilization, type: UniqueType, test: (Unique) -> Boolean): Boolean {
        var found = false
        civ.forEachMatchingUnique(type) { if (!found && it.sourceObjectType != UniqueTarget.Nation && test(it)) found = true }
        return found
    }

    /**
     *  Vox Populi's CvDiplomacyAI::SelectBestApproachTowardsMinorCiv (CvDiplomacyAI.cpp 24035-25150): weights of war (Conquest),
     *  hostile (Bully), neutral (Ignore) and friendly on VP's scale (personality bias x 100, [CivAi.minorCivApproachBias] of Conquest,
     *  Bully, Ignore and Friendly; VP has no protective approach). The highest wins, in VP's order on ties; all zero is neutral.
     *  Rule-dependent switches follow our BNW rules: BALANCE_QUEST_CHANGES off (the kill-city-state quest only gates on being enabled),
     *  BALANCE_HEAVY_TRIBUTE off (bully score / 10), GLOBAL_CS_OVERSEAS_TERRITORY off; BNW's tribute rule is the bully score
     *  (CityStateFunctions.getTributeWillingness, can bully when not negative: VP's "above 0"). MOD_BALANCE_VP's AI weight for the
     *  city-state combat trait is taken (war x 5, no hostile). Inputs VP has and BNW not count as none: VP-only traits and player
     *  modifiers (alliance CS strength or defense, influence per gifted unit, diplomatic marriage, yields from CS allies or minor
     *  demands, bully annexing and its penalties, bully strength and value, bullying friendly city-states, the global bully
     *  reduction, increased quest influence, spy strength, resources counting as monopolies, always religion), World Congress's
     *  "no ally" and permanent allies, embassies, Nuclear Gandhi, a city-state's yield bonus in gold. Its state religion is the one we
     *  founded (BNW has no other).
     */
    fun selectBestApproach(civ: Civilization, minor: Civilization): MinorCivApproach {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val w = IntArray(VP_ORDER.size)
        val bias = IntArray(VP_ORDER.size) { ai.minorCivApproachBias(VP_ORDER[it]) * 100 }
        val war = 0; val hostile = 1; val neutral = 2; val friendly = 3
        for (i in w.indices) w[i] += bias[i]
        val easy = WarStateAi.isEasyTarget(civ, minor)
        val oldApproach = ai.minorCivApproaches[minor.civID] ?: MinorCivApproach.Ignore
        val firstUpdate = civ.gameInfo.turns - ai.diplomacy.memoryOf(minor).turnMet <= 1
        val atWar = civ.isAtWarWith(minor)
        val trait = minor.cityStateType.name
        fun has(category: TraitCategory) = ai.hasTraitCategory(category)

        // NEUTRAL DEFAULT WEIGHT
        w[neutral] += bias[neutral] * civ.define(AiDefine.MINOR_APPROACH_IGNORE_DEFAULT)
        // PREVIOUS APPROACH BIAS
        if (!firstUpdate) {
            if ((atWar || oldApproach == MinorCivApproach.Conquest) && WarStateAi.warState(civ, minor) != null && !WarStateAi.isNoNewWars(civ))
                w[war] += bias[war] * 2
            if (oldApproach == MinorCivApproach.Bully && (easy || (ai.diplomacy.targetValue(minor) ?: TargetValue.Average) >= TargetValue.Average))
                w[hostile] += bias[hostile] * 2
        }
        // RESOURCES
        val numWeLack = MinorCivHelpers.numResourcesMajorLacks(minor, civ)
        if (numWeLack > 0) {
            w[friendly] += bias[friendly] * numWeLack
            w[war] += bias[war] * numWeLack
        }
        // FRIENDS WITH MINOR, PLEDGE TO PROTECT
        if (isFriends(minor, civ)) w[friendly] += bias[friendly] * 2
        if (isProtectedBy(minor, civ)) w[friendly] += bias[friendly] * 2
        var goodWarTarget = false
        var checkIfGoodWarTarget = true
        // EASY TARGET
        if (easy) {
            w[war] += bias[war] * 2
            w[hostile] += bias[hostile] * 2
        }
        // TRAITS THAT AFFECT MINORS
        var friendshipBonus = false
        var aggressionBonus = false
        if (hasFriendshipTrait(civ)) { w[friendly] += bias[friendly] * 5; friendshipBonus = true }
        if (hasBonusTrait(civ)) { w[friendly] += bias[friendly] * 5; friendshipBonus = true }
        if (civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) { w[friendly] += bias[friendly] * 10; friendshipBonus = true }
        val playerFriendship = listOf(
            playerModifier(civ, UniqueType.CityStateRestingPoint) { it.params[0].toInt() > 0 },
            playerModifier(civ, UniqueType.CityStateInfluenceDegradation) { it.params[0].toInt() < 0 },
            playerModifier(civ, UniqueType.CityStateGoldGiftsProvideMoreInfluence) { it.params[0].toInt() > 0 },
            playerModifier(civ, UniqueType.CityStateStatPercent) { it.params[0] == Stat.Science.name },
            playerModifier(civ, UniqueType.CityStateResources) { it.params[0].toInt() > 0 },
            playerModifier(civ, UniqueType.RiggingElectionVotes) { it.params[0].toInt() > 0 })
        for (modifier in playerFriendship) if (modifier) { w[friendly] += bias[friendly] * 2; friendshipBonus = true }
        if (hasCombatTrait(civ)) { w[war] += bias[war] * 5; aggressionBonus = true }
        if (playerModifier(civ, UniqueType.Strength) { unique -> unique.modifiers.any { it.params.any { p -> p == "City-States" } } }) {
            w[hostile] += bias[hostile] * 2
            w[war] += bias[war] * 2
            aggressionBonus = true
        }
        // CONQUEST GRAND STRATEGY
        val closeToConquest = VictoryProgressAi.isCloseToWorldConquest(civ, rules)
        if (ai.isGoingForWorldConquest() || closeToConquest || has(TraitCategory.Warmonger) || has(TraitCategory.Expansionist)) {
            if (trait == MILITARISTIC || trait == MERCANTILE) {
                w[friendly] += bias[friendly] * 2
                checkIfGoodWarTarget = false
            } else if (closeToConquest || (!has(TraitCategory.Smaller) && !ai.isGoingForDiploVictory())) {
                aggressionBonus = true
                w[hostile] += bias[hostile] * 4
                w[war] += bias[war] * 2
            }
        }
        // DIPLO GRAND STRATEGY
        val closeToDiplo = VictoryProgressAi.isCloseToDiploVictory(civ, rules)
        if (ai.isGoingForDiploVictory() || closeToDiplo || has(TraitCategory.Diplomat)) {
            w[friendly] += bias[friendly] * 6
            checkIfGoodWarTarget = false
            friendshipBonus = true
        }
        // CULTURE GRAND STRATEGY
        val closeToCulture = VictoryProgressAi.isCloseToCultureVictory(civ, rules)
        if (ai.isGoingForCultureVictory() || closeToCulture || has(TraitCategory.Tourism) || has(TraitCategory.Smaller)) {
            when (trait) {
                CULTURED, MARITIME -> { w[friendly] += bias[friendly] * 2; checkIfGoodWarTarget = false }
                MERCANTILE -> w[friendly] += bias[friendly]
                else -> w[hostile] += bias[hostile] * 2
            }
        }
        // SCIENCE GRAND STRATEGY
        val closeToScience = VictoryProgressAi.isCloseToSpaceshipVictory(civ, rules)
        if (ai.isGoingForSpaceshipVictory() || closeToScience || has(TraitCategory.Nerd) || has(TraitCategory.Smaller)) {
            when (trait) {
                MARITIME, MILITARISTIC -> { w[friendly] += bias[friendly] * 2; checkIfGoodWarTarget = false }
                MERCANTILE -> w[friendly] += bias[friendly]
                else -> w[hostile] += bias[hostile] * 2
            }
        }
        // RELIGIOUS MINORS
        if (trait == RELIGIOUS && civ.gameInfo.isReligionEnabled()) {
            val religion = civ.religionManager.religion?.takeIf { it.isMajorReligion() }
            if (religion != null) {
                if (!religion.isEnhancedReligion()) {
                    w[friendly] += bias[friendly] * 2
                    checkIfGoodWarTarget = false
                } else w[friendly] += bias[friendly]
            } else if (civ.religionManager.remainingFoundableReligions() <= 0) {
                when {
                    civ.religionManager.religionState >= ReligionState.Pantheon -> {
                        w[friendly] += bias[friendly] * 4
                        checkIfGoodWarTarget = false
                    }
                    civ.gameInfo.religions.values.none { it.isMajorReligion() } -> {
                        w[friendly] += bias[friendly] * 2
                        checkIfGoodWarTarget = false
                    }
                    !has(TraitCategory.Religious) -> {
                        w[hostile] += bias[hostile] * 2
                        w[war] += bias[war]
                        if (easy) { w[hostile] += bias[hostile]; w[war] += bias[war] }
                        if (aggressionBonus) { w[hostile] += bias[hostile]; w[war] += bias[war] }
                    }
                }
            } else {
                w[war] += if (easy || aggressionBonus) bias[war] * 2 else 0
                w[hostile] -= bias[hostile] * 2
                w[neutral] += bias[neutral] * 4
            }
            if (has(TraitCategory.Religious) && (religion != null || civ.religionManager.religionState >= ReligionState.Pantheon)) {
                w[friendly] += bias[friendly] * 2
                checkIfGoodWarTarget = false
            }
        }
        if (friendshipBonus && civ.cities.count { !it.isPuppet } < 4) {
            w[hostile] = 0
            w[war] = 0
            checkIfGoodWarTarget = false
            aggressionBonus = false
        }
        // Does this city-state own an original major capital? Allying it and conquering it both count towards domination.
        // (VP also remembers it for Nuclear Gandhi, none here)
        minor.cities.firstOrNull { it.isOriginalCapital && it.foundingCivObject?.isMajorCiv == true }?.let { capital ->
            friendshipBonus = true
            if (capital.foundingCivObject != civ) {
                aggressionBonus = true
                checkIfGoodWarTarget = true
            }
        }
        // AT WAR RIGHT NOW
        for (other in civ.gameInfo.civilizations) {
            if (other == minor || !WarStateAi.isPlayerValid(civ, other)) continue
            if (civ.isAtWarWith(other)) {
                w[neutral] += bias[neutral]
                w[friendly] += bias[friendly]
                if (other.isMajorCiv) {
                    w[war] -= bias[war] * 2
                    w[hostile] -= bias[hostile] * 2
                }
                // VP clears its potential war target flag here and below (no reader of it here)
            }
            if (other.isMajorCiv) when (DiplomacyTurn.proximity(civ, other)) {
                DiplomacyTurn.PROXIMITY_NEIGHBORS -> w[war] -= bias[war] * 2
                DiplomacyTurn.PROXIMITY_CLOSE -> w[war] -= bias[war]
            }
        }
        // PROXIMITY
        val proximity = DiplomacyTurn.proximity(civ, minor)
        val aggressive = easy || aggressionBonus
        when (proximity) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> {
                w[friendly] += bias[friendly] * (if (friendshipBonus) 3 else 2)
                w[hostile] += if (aggressive) bias[hostile] else bias[hostile] / 2
                w[war] += if (aggressive) bias[war] * 2 else bias[war]
            }
            DiplomacyTurn.PROXIMITY_CLOSE -> {
                w[friendly] += bias[friendly] * (if (friendshipBonus) 2 else 1)
                w[hostile] += if (aggressive) bias[hostile] * 2 else bias[hostile]
                w[war] += if (aggressive) bias[war] else bias[war] / 2
            }
            DiplomacyTurn.PROXIMITY_FAR -> w[neutral] += bias[neutral] * 4
            else -> w[neutral] += bias[neutral] * 8
        }
        // MINOR PERSONALITY
        when (minor.cityStatePersonality) {
            CityStatePersonality.Friendly -> w[friendly] += bias[friendly] * 2
            CityStatePersonality.Neutral -> w[neutral] += bias[neutral]
            CityStatePersonality.Hostile -> { w[war] += bias[war] * 2; w[neutral] += bias[neutral] * 2 }
            CityStatePersonality.Irrational -> { w[hostile] += bias[hostile] * 2; w[neutral] += bias[neutral] * 2 }
        }
        // TRIBUTE HISTORY
        if (minor.civID in ai.deals.bulliedMinors) {
            w[hostile] += if (easy || aggressionBonus) bias[hostile] * 4 else bias[hostile] * 2
            w[war] += if (easy || aggressionBonus) bias[war] * 2 else bias[war]
        }
        // QUESTS
        if (hasQuest(minor, civ, QuestName.PledgeToProtect)) w[friendly] += if (friendshipBonus) bias[friendly] * 2 else bias[friendly]
        if (civ.gameInfo.ruleset.quests.containsKey(QuestName.ConquerCityState.value)) {
            for (other in civ.gameInfo.civilizations) {
                if (other == minor || !other.isCityState || !WarStateAi.isPlayerValid(civ, other)) continue
                val wantsThem = other.questManager.getAssignedQuestsFor(civ)
                    .any { it.questName == QuestName.ConquerCityState.value && it.data1 == minor.civName }
                if (!wantsThem) continue
                if ((ai.diplomacy.targetValue(minor) ?: TargetValue.Average) >= TargetValue.Favorable) {
                    w[war] += if (easy || aggressionBonus) bias[war] * 4 else bias[war] * 2
                    aggressionBonus = true
                }
            }
        }
        // Are we getting yields from trade with them?
        val tradeValue = tradeValueFrom(civ, minor)
        if (tradeValue > 0) w[friendly] += bias[friendly] * tradeValue
        else w[neutral] += bias[neutral] * 2
        // See if this minor is on the same landmass as a major we want to attack
        val goodWarTargetMajors = HashSet<Civilization>()
        if (checkIfGoodWarTarget) {
            val minorContinent = minor.getCapital()!!.getCenterTile().getContinent()
            val ourContinent = civ.getCapital()?.getCenterTile()?.getContinent()
            for (other in civ.gameInfo.civilizations) {
                if (!other.isMajorCiv || other == civ || !WarStateAi.isPlayerValid(civ, other)) continue
                if (!civ.isAtWarWith(other) && ai.diplomacy.approachTowards(other) != MajorCivApproach.War) continue
                val theirCapital = other.getCapital() ?: continue
                if (DiplomacyTurn.proximity(other, minor) < DiplomacyTurn.PROXIMITY_NEIGHBORS || proximity < DiplomacyTurn.PROXIMITY_NEIGHBORS) continue
                if (ourContinent == minorContinent && theirCapital.getCenterTile().getContinent() == minorContinent) {
                    goodWarTarget = true
                    goodWarTargetMajors.add(other)
                }
            }
        }
        if (goodWarTarget) {
            w[war] += bias[war] * 2
            if (easy) w[war] += bias[war] * 2
            if (aggressionBonus) w[war] += bias[war] * 2
            if (ai.isGoingForWorldConquest() || closeToConquest) w[war] += bias[war] * 2
        } else if (proximity <= DiplomacyTurn.PROXIMITY_FAR) {
            w[war] = 0
            w[hostile] = 0
        }
        // APPROACHES TOWARDS OTHER PLAYERS: a war we plan or fight against a close major leaves no room for this one
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || other == civ || other in goodWarTargetMajors || !WarStateAi.isPlayerValid(civ, other)) continue
            if (!civ.isAtWarWith(other) && ai.diplomacy.approachTowards(other) != MajorCivApproach.War) continue
            if (!WarIntentAi.isPhonyWar(civ, other) && DiplomacyTurn.proximity(civ, other) >= DiplomacyTurn.PROXIMITY_CLOSE) {
                w[war] = 0
                w[neutral] += bias[neutral] * 2
            }
        }
        // No bullying while at war!
        if (atWar) w[hostile] = 0
        // PREVIOUSLY CAPTURED OUR CITIES
        val canDeclareWar = civ.getDiplomacyManager(minor)?.canDeclareWar() == true
        // If we can't declare war, ignore any captures for the time being
        val theyCapturedFromUs = (canDeclareWar || atWar) && minor.cities.any { it.foundingCivObject == civ }
        val ally = minor.allyCiv
        if (theyCapturedFromUs) {
            w[war] += bias[war] * 10
            w[friendly] = 0
            w[neutral] = 0
            w[hostile] = 0
        } else if (ally == civ) {
            w[war] = 0
            w[hostile] = 0
            w[friendly] += bias[friendly] * (if (friendshipBonus) 6 else 3)
            w[neutral] = 0
        } else if (ally != null && WarAlliesAi.avoidExchangesWithPlayer(civ, ally, warOnly = true)) w[hostile] = 0
        // BULLY VIABILITY
        if (w[hostile] > 0) {
            val bullyScore = minor.cityStateFunctions.getTributeWillingness(civ)
            if (bullyScore >= 0) w[hostile] += bullyScore * 100 / 10 else w[hostile] = 0
        }
        // MILITARY TARGET VALUE
        for (i in w.indices) if (w[i] < 0) w[i] = 0
        val goodAttackTarget = ai.military.havePreferredAttackTarget(minor) || theyCapturedFromUs
        val wantsConquest = aggressionBonus && goodAttackTarget
        val (conquestPercent, minorPercent, peacefulBonus) = when (ai.diplomacy.targetValue(minor) ?: TargetValue.Average) {
            TargetValue.Impossible -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_IMPOSSIBLE, AiDefine.MINOR_APPROACH_WAR_TARGET_IMPOSSIBLE, 5)
            TargetValue.Bad -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_BAD, AiDefine.MINOR_APPROACH_WAR_TARGET_BAD, 4)
            TargetValue.Difficult -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_DIFFICULT, AiDefine.MINOR_APPROACH_WAR_TARGET_DIFFICULT, 3)
            TargetValue.Average -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_AVERAGE, AiDefine.MINOR_APPROACH_WAR_TARGET_AVERAGE, 2)
            TargetValue.Favorable -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_FAVORABLE, AiDefine.MINOR_APPROACH_WAR_TARGET_FAVORABLE, 1)
            TargetValue.Soft -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_SOFT, AiDefine.MINOR_APPROACH_WAR_TARGET_SOFT, 0)
            TargetValue.Cakewalk -> Triple(AiDefine.CONQUEST_WAR_MULTIPLIER_TARGET_CAKEWALK, AiDefine.MINOR_APPROACH_WAR_TARGET_CAKEWALK, 0)
        }
        val percent = civ.define(if (wantsConquest) conquestPercent else minorPercent)
        w[war] = w[war] * percent / 100
        w[hostile] = w[hostile] * percent / 100
        w[neutral] += bias[neutral] * peacefulBonus
        w[friendly] += bias[friendly] * peacefulBonus
        // RECENTLY BULLIED
        if (MinorCivHelpers.isRecentlyBulliedByMajor(minor, civ)) {
            w[friendly] /= 2
            w[hostile] = 0
            if (!goodWarTarget) w[war] = 0
        }
        // CAN WE DECLARE WAR?
        if (!canDeclareWar && !atWar) {
            w[war] = 0
            w[hostile] = 0
        }
        // No target? Don't waste our time on city-states that are not good targets either
        if (!ai.military.havePossibleAttackTarget(minor)) {
            w[war] = 0
            w[hostile] = 0
        } else if (!goodWarTarget && !goodAttackTarget) w[war] = 0
        // If we're in bad shape, don't waste time trying to go after city-states
        if (WarStateAi.isNoNewWars(civ)) {
            w[hostile] = 0
            if (!theyCapturedFromUs || WarStateAi.isInTerribleShapeForWar(civ)) w[war] = 0
        }
        // At war with this city-state and the war goes badly (troubled or worse)
        if (atWar && ((theyCapturedFromUs && WarStateAi.stateAllWars(civ) == StateAllWars.Losing) ||
                (!theyCapturedFromUs && (WarStateAi.warState(civ, minor) ?: WarState.Calm) <= WarState.Troubled))) {
            w[war] = 0
            w[hostile] = 0
        }
        // PEACE TREATY: made peace with them recently
        val peaceTurn = ai.diplomacy.turnMadePeaceWith(minor)
        val dampener = rules.handicaps.firstOrNull { it.name == civ.gameInfo.difficulty }?.values?.get("PeaceTreatyDampenerTurns") ?: 20
        if (peaceTurn > -1 && civ.gameInfo.turns - peaceTurn < dampener) {
            w[hostile] = 0
            w[war] = 0
        }
        // Teammates and friends: not the city-states our friends are allied with or protect
        if (!theyCapturedFromUs && (w[war] > 0 || w[hostile] > 0)) {
            for (other in civ.gameInfo.civilizations) {
                if (!other.isMajorCiv || other == civ || !other.isAlive()) continue
                val friendOrAlly = OpinionAi.isDoFAccepted(civ, other) ||
                    civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.DefensivePact
                if (friendOrAlly && (minor.allyCiv == other || isProtectedBy(minor, other))) {
                    w[hostile] = 0
                    w[war] = 0
                    break
                }
            }
        }
        // TOO FAR TO BULLY
        if (w[hostile] > 0) {
            val distance = MilitaryTargets.closestCityByPathLength(civ, minor, minor.getCapital()!!.getCenterTile(), allowSea = false)?.second ?: Int.MAX_VALUE
            if (distance > 23) w[hostile] = 0
        }
        // AGGRESSIVE MODE
        if (VictoryProgressAi.isAiAggressiveMode(civ)) {
            w[war] *= 2
            w[hostile] *= 2
        }
        for (i in w.indices) if (w[i] < 0) w[i] = 0
        if (w.all { it == 0 }) return MinorCivApproach.Ignore
        var best = 0
        for (i in 1 until w.size) if (w[i] > w[best]) best = i
        return VP_ORDER[best]
    }

    /**
     *  The trade value of SelectBestApproachTowardsMinorCiv: gold, culture and science our trade routes to [minor] bring us, averaged
     *  (VP's sum x 100 / 300); VP's city-state gold bonus is none in BNW
     */
    private fun tradeValueFrom(civ: Civilization, minor: Civilization): Int {
        var sum = 0f
        for (route in civ.tradeRoutes.routes) if (route.getDestinationCiv() == minor)
            sum += route.originYields.gold + route.originYields.culture + route.originYields.science
        return (sum / 3).toInt()
    }
    //endregion

    //region Contacts

    /**
     *  Vox Populi's CvDiplomacyAI::DoUpdateMinorCivProtection: an AI pledges to protect a city-state it is friendly towards or allied
     *  with when it can, and otherwise withdraws a pledge when it can. VP keeps a pledge while the protection boosts influence
     *  (BALANCE_INFLUENCE_BOOST_PROTECTION_MINOR) unless the city-state has no capital or a damaged one; BNW's rules have no such
     *  boost (0), so the pledge is withdrawn as soon as we may.
     */
    fun updateMinorCivProtection(civ: Civilization, minor: Civilization, approach: MinorCivApproach) {
        if (civ.isHuman()) return
        val functions = minor.cityStateFunctions
        if (approach == MinorCivApproach.Friendly || isAllies(minor, civ)) {
            if (functions.otherCivCanPledgeProtection(civ)) functions.addProtectorCiv(civ)
        } else if (isProtectedBy(minor, civ) && functions.otherCivCanWithdrawProtection(civ)) {
            functions.removeProtectorCiv(civ)
        }
    }

    /** A gold gift DoContactMinorCivs wants to give (MinorGoldGiftInfo) */
    class GoldGift(val minor: Civilization) {
        var quickBoost = false
        var majorRival: Civilization? = null
    }

    /** The turn's wishes of DoContactMinorCivs; the random rolls use [Random] from [CivAi.random] */
    class ContactWishes(val buyout: Boolean, val goldGift: Boolean, val bullyUnit: Boolean, val bullyGold: Boolean)

    /**
     *  The first part of Vox Populi's DoContactMinorCivs: whether we want to buy out (whenever we are able), gift, take a worker or gold
     *  from a city-state this turn. Gift: a diplomacy flavor of MC_ALWAYS_GIFT_DIPLO_THRESHOLD or more, a diplomatic or culture victory,
     *  saving for a gift, a gold quest or a gold rate above 100, else a roll of 0..9 below the diplomacy flavor. Worker, with a
     *  city-state we are at war or hostile towards: few workers and improvements (workers per city below 0.25, improved tiles below
     *  half), else a roll below the tile improvement flavor. Gold: a gold flavor of 6 or more, world conquest, saving for a unit or
     *  building, losing money or a negative gold rate, else a roll below the gold flavor. (VP's trait and policy overrides for bullying
     *  are VP-only: none.)
     */
    fun contactWishes(civ: Civilization): ContactWishes {
        val ai = civ.ai
        val diplomacy = ai.personalityAndGrandStrategy(Flavor.Diplomacy)
        val gold = ai.personalityAndGrandStrategy(Flavor.Gold)
        val tileImprovement = ai.personalityAndGrandStrategy(Flavor.TileImprovement)
        val gpt = DealAi.goldRate(civ)
        val savings = ai.purchaseSavings

        val buyout = civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)

        var goldGift = diplomacy >= civ.define(AiDefine.MC_ALWAYS_GIFT_DIPLO_THRESHOLD) || ai.isGoingForDiploVictory()
            || ai.isGoingForCultureVictory() || savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)
            || hasActiveGoldQuest(civ) || gpt > 100
        if (!goldGift) goldGift = ai.random("DiplomacyAI.GoldGift").nextInt(10) < diplomacy

        var bullyUnit = false
        val warOrHostile = civ.gameInfo.civilizations.any { minor ->
            minor.isCityState && ai.minorCivApproaches[minor.civID].let { it == MinorCivApproach.Conquest || it == MinorCivApproach.Bully }
        }
        if (warOrHostile) {
            bullyUnit = EconomicAiRatios.workersToCitiesRatio(civ) < 0.25 && EconomicAiRatios.improvedToImprovablePlotsRatio(civ) < 0.50
            if (!bullyUnit) bullyUnit = ai.random("DiplomacyAI.BullyUnit").nextInt(10) < tileImprovement
        }

        var bullyGold = gold >= 6 || ai.isGoingForWorldConquest() || savings.isSavingForThisPurchase(PurchaseType.Unit)
            || savings.isSavingForThisPurchase(PurchaseType.Building) || ai.isEconomicStrategyActive(LOSING_MONEY) || gpt < 0
        if (!bullyGold) bullyGold = ai.random("DiplomacyAI.BullyGold").nextInt(10) < gold
        return ContactWishes(buyout, goldGift, bullyUnit, bullyGold)
    }

    /** CvDiplomacyAI::IsHasActiveGoldQuest: a city-state asks us for gold */
    fun hasActiveGoldQuest(civ: Civilization) =
        civ.gameInfo.getAliveCityStates().any { civ.knows(it) && hasQuest(it, civ, QuestName.GiveGold) }

    /**
     *  Vox Populi's CvDiplomacyAI::DoContactMinorCivs: for every valid city-state we are not at war with, the protection update, then the
     *  desire to buy it out (Austria), to give it gold, to take a worker, to take gold; then the buyout (one a turn, else saving), the
     *  gifts (several a turn, never below half of the turn's starting treasury, else saving), one worker, and every gold tribute.
     *  The route wish (SetWantToRouteConnectToMinor) is set for a neighbouring city-state we are friendly towards, with an average gold
     *  rate above min(20 x era, 50), that is our ally or gave us a route quest; cleared otherwise, and kept for a city-state we are at
     *  war with; [BuilderTaskingAi] reads it. Unciv has no intrusion state (SetMajorIntruding); VP's bully annexing (Rome), diplomatic
     *  marriage (its Austria; ours is BNW's buyout) and tile improvement gifts to an ally are not here.
     */
    fun contactMinorCivs(civ: Civilization) = timeThis("AI.ContactMinorCivs") {
        val wishes = contactWishes(civ)
        val buyouts = ArrayList<Pair<Civilization, Int>>()
        val gifts = ArrayList<Pair<GoldGift, Int>>()
        val bullyUnits = ArrayList<Pair<Civilization, Int>>()
        val bullyGolds = ArrayList<Pair<Civilization, Int>>()
        val wantsRoute = civ.ai.wantsRouteToMinors
        // The original skips the wish update of a city-state at war, so that wish is kept as it was
        wantsRoute.retainAll { id -> civ.gameInfo.getCivilization(id).let { it.isAlive() && civ.isAtWarWith(it) } }
        for (minor in DiplomacyTurn.validPlayers(civ).filter { it.isCityState }) {
            if (civ.isAtWarWith(minor)) continue
            val approach = civ.ai.minorCivApproaches[minor.civID] ?: MinorCivApproach.Ignore
            updateMinorCivProtection(civ, minor, approach)
            val era = maxOf(1, civ.getEraNumber())
            if (approach == MinorCivApproach.Friendly && WarStateAi.avgGoldRate(civ) > minOf(20 * era, 50)
                && DiplomacyTurn.proximity(civ, minor) == DiplomacyTurn.PROXIMITY_NEIGHBORS
                && (isAllies(minor, civ) || hasQuest(minor, civ, QuestName.Route)))
                wantsRoute.add(minor.civID)
            var buyingOut = false
            if (wishes.buyout) {
                val value = buyoutValue(civ, minor)
                if (value != null && value > 100) {
                    buyouts.add(minor to value)
                    buyingOut = true
                }
            }
            var giving = false
            if (wishes.goldGift && !buyingOut && minor.cityStateFunctions.goldGiftsAllowed()) {
                val gift = GoldGift(minor)
                val value = goldGiftValue(civ, minor, approach, gift)
                if (value != null && value > civ.define(AiDefine.MC_GIFT_WEIGHT_THRESHOLD)) {
                    gifts.add(gift to value)
                    giving = true
                }
            }
            var bullyingUnit = false
            if (wishes.bullyUnit && !buyingOut && !giving) {
                val value = bullyUnitValue(civ, minor, approach)
                if (value != null && value > 100) {
                    bullyUnits.add(minor to value)
                    bullyingUnit = true
                }
            }
            if (wishes.bullyGold && !buyingOut && !giving && !bullyingUnit) {
                val value = bullyGoldValue(civ, minor, approach)
                if (value != null && value > 100) bullyGolds.add(minor to value)
            }
        }

        val goldReserve = civ.gold
        val savings = civ.ai.purchaseSavings
        buyouts.sortByDescending { it.second }
        for ((minor, _) in buyouts) {
            val cost = minor.cityStateFunctions.getDiplomaticMarriageCost()
            if (civ.gold >= cost) {
                if (minor.cityStateFunctions.canBeMarriedBy(civ)) {
                    minor.cityStateFunctions.diplomaticMarriage(civ)
                    break
                }
            } else if (!savings.isSavingForThisPurchase(PurchaseType.MinorCivGift))
                savings.startSaveForPurchase(PurchaseType.MinorCivGift, cost, civ.define(AiDefine.AI_GOLD_PRIORITY_BUYOUT_CITY_STATE))
        }

        val large = civ.define(AiDefine.MINOR_GOLD_GIFT_LARGE)
        val medium = civ.define(AiDefine.MINOR_GOLD_GIFT_MEDIUM)
        val small = civ.define(AiDefine.MINOR_GOLD_GIFT_SMALL)
        gifts.sortByDescending { it.second }
        for ((gift, _) in gifts) {
            val goldLeft = civ.gold
            val amount = giftAmount(goldLeft, gift.quickBoost, small, medium, large)
            if (amount > 0 && goldLeft >= goldReserve / 2) {
                gift.minor.cityStateFunctions.receiveGoldGift(civ, amount)
                if (savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)) savings.cancelSaveForPurchase(PurchaseType.MinorCivGift)
            } else if (!savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)) {
                val toSave = if (gift.quickBoost) small else medium
                val priority = civ.define(AiDefine.AI_GOLD_PRIORITY_DIPLOMACY_BASE) +
                    civ.define(AiDefine.AI_GOLD_PRIORITY_DIPLOMACY_PER_FLAVOR_POINT) * civ.ai.personalityAndGrandStrategy(Flavor.Diplomacy)
                savings.startSaveForPurchase(PurchaseType.MinorCivGift, toSave, priority)
            }
        }

        bullyUnits.sortByDescending { it.second }
        for ((minor, _) in bullyUnits) {
            if (!canMajorBullyUnit(minor, civ)) continue
            minor.cityStateFunctions.tributeWorker(civ)
            civ.ai.deals.bulliedMinors.add(minor.civID)
            break
        }
        bullyGolds.sortByDescending { it.second }
        for ((minor, _) in bullyGolds) {
            if (!canMajorBullyGold(minor, civ)) continue
            minor.cityStateFunctions.tributeGold(civ)
            civ.ai.deals.bulliedMinors.add(minor.civID)
        }
    }

    /** The gift size of DoContactMinorCivs: a small one for a quick boost, else large, else medium, as far as the treasury allows; 0 if none */
    fun giftAmount(goldLeft: Int, quickBoost: Boolean, small: Int, medium: Int, large: Int): Int = when {
        goldLeft >= small && quickBoost -> small
        goldLeft >= large -> large
        goldLeft >= medium -> medium
        else -> 0
    }

    /**
     *  The gift desirability of Vox Populi's DoContactMinorCivs for [minor], null when we are not friendly toward it: a base of
     *  MC_GIFT_WEIGHT_PROTECTIVE over the threshold, then BNW's terms. Fills [gift]'s quick boost and rival. Counts only above
     *  MC_GIFT_WEIGHT_THRESHOLD.
     */
    fun goldGiftValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach, gift: GoldGift): Int? {
        if (approach != MinorCivApproach.Friendly) return null
        val ai = civ.ai
        var value = civ.define(AiDefine.MC_GIFT_WEIGHT_THRESHOLD)
        value += civ.define(AiDefine.MC_GIFT_WEIGHT_PROTECTIVE) // some base value
        value += min(max(0, DealAi.goldRate(civ) - 50), 100)
        val trait = minor.cityStateType.name
        if (ai.isGoingForDiploVictory()) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        if (civ.gameInfo.league?.unitedNations == true) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        else if (ai.isGoingForCultureVictory()) {
            if (trait == CULTURED) value += civ.define(AiDefine.MC_GIFT_WEIGHT_CULTURE_VICTORY)
        } else if (ai.isGoingForWorldConquest()) {
            if (trait == MILITARISTIC) value += civ.define(AiDefine.MC_GIFT_WEIGHT_CONQUEST_VICTORY)
        }
        if (hasQuest(minor, civ, QuestName.GiveGold)) value += 150
        if (hasQuest(minor, civ, QuestName.Invest)) value += 100
        if (hasFriendshipTrait(civ) || hasBonusTrait(civ)) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        if (trait == MARITIME && !civ.isEmpireUnhappy())
            value += civ.define(AiDefine.MC_GIFT_WEIGHT_MARITIME_GROWTH) * ai.personalityAndGrandStrategy(Flavor.Growth) * max(1, civ.cities.size / 3)
        if (trait == MILITARISTIC && !ai.isGoingForWorldConquest()) value += civ.define(AiDefine.MC_GIFT_WEIGHT_MILITARISTIC)
        val lacking = MinorCivHelpers.numResourcesMajorLacks(minor, civ)
        if (lacking > 0) value += lacking * civ.define(AiDefine.MC_GIFT_WEIGHT_RESOURCE_WE_NEED)
        if (minor.cityStatePersonality == CityStatePersonality.Hostile) value += civ.define(AiDefine.MC_GIFT_WEIGHT_HOSTILE)
        when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_NEIGHBORS)
            DiplomacyTurn.PROXIMITY_CLOSE -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_CLOSE)
            DiplomacyTurn.PROXIMITY_FAR -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_FAR)
        }

        val functions = minor.cityStateFunctions
        val mediumFriendship = functions.influenceGainedByGift(civ, civ.define(AiDefine.MINOR_GOLD_GIFT_MEDIUM))
        val smallFriendship = functions.influenceGainedByGift(civ, civ.define(AiDefine.MINOR_GOLD_GIFT_SMALL))
        val friendship = MinorCivHelpers.influence(minor, civ)
        for (other in civ.gameInfo.getAliveMajorCivs()) {
            val otherFriendship = MinorCivHelpers.influence(minor, other)
            if (otherFriendship <= 0) continue
            // As in the original: only rivals we are already ahead of are looked at
            if (friendship <= otherFriendship) continue
            val mediumAllies = friendship + mediumFriendship >= MinorCivHelpers.ALLIES_THRESHOLD
            val smallAllies = friendship + smallFriendship >= MinorCivHelpers.ALLIES_THRESHOLD
            if (smallAllies && otherFriendship - friendship < smallFriendship) {
                value += civ.define(AiDefine.MC_SMALL_GIFT_WEIGHT_PASS_OTHER_PLAYER)
                gift.quickBoost = true
                gift.majorRival = other
            } else if (mediumAllies && otherFriendship - friendship < mediumFriendship) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_PASS_OTHER_PLAYER)
                gift.majorRival = other
            } else value = 0
        }

        if (isAllies(minor, civ)) {
            if (MinorCivHelpers.isCloseToNotBeingAllies(minor, civ)) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_ALMOST_NOT_ALLIES)
                gift.quickBoost = true
            } else value = 0
        } else if (isFriends(minor, civ)) {
            if (MinorCivHelpers.isCloseToNotBeingFriends(minor, civ)) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_ALMOST_NOT_FRIENDS)
                gift.quickBoost = true
            } else if (!ai.isGoingForDiploVictory() || civ.gameInfo.league?.unitedNations != true) value = 0
        }
        if (MinorCivHelpers.isRecentlyBulliedByMajor(minor, civ)) value -= 100
        return value
    }

    /** CvMinorCivAI::CanMajorBullyUnit: Unciv's tribute rule (willingness to give a worker not negative, with `cityStateBullyLikeVp` the score for a heavy tribute) and a capital to give it from */
    fun canMajorBullyUnit(minor: Civilization, major: Civilization): Boolean =
        minor.getCapital() != null && minor.cityStateFunctions.canBullyHeavy(major)

    /** CvMinorCivAI::CanMajorBullyGold: Unciv's tribute rule (willingness not negative, with `cityStateBullyLikeVp` the score for gold) */
    fun canMajorBullyGold(minor: Civilization, major: Civilization): Boolean =
        minor.cityStateFunctions.canBullyGold(major)

    /**
     *  The worker tribute desirability of Vox Populi's DoContactMinorCivs; null unless hostile and possible. 100, by proximity +25,
     *  +15, -15, -25, +25 when we bullied it before, -20 with a protector or an ally else +20; 0 when we are its friend or ally. Counts
     *  above 100.
     */
    fun bullyUnitValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach): Int? {
        if (approach != MinorCivApproach.Bully || !canMajorBullyUnit(minor, civ)) return null
        var value = 100
        value += when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 25
            DiplomacyTurn.PROXIMITY_CLOSE -> 15
            DiplomacyTurn.PROXIMITY_FAR -> -15
            DiplomacyTurn.PROXIMITY_DISTANT -> -25
            else -> 0
        }
        if (minor.civID in civ.ai.deals.bulliedMinors) value += 25
        value += if (minor.cityStateFunctions.getProtectorCivs().isNotEmpty() || minor.allyCiv != null) -20 else 20
        if (isAllies(minor, civ) || isFriends(minor, civ)) value = 0
        return value
    }

    /**
     *  The gold tribute desirability of Vox Populi's DoContactMinorCivs; null unless hostile and possible. 100 + the tribute's gold x our
     *  gold flavor / 10, +25 when we bullied it before, -20 with a protector else +20; 0 when we are its friend or ally. Counts above 100.
     */
    fun bullyGoldValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach): Int? {
        if (approach != MinorCivApproach.Bully || !canMajorBullyGold(minor, civ)) return null
        var value = 100
        value += minor.cityStateFunctions.goldGainedByTribute() * civ.ai.personalityAndGrandStrategy(Flavor.Gold) / 10
        if (minor.civID in civ.ai.deals.bulliedMinors) value += 25
        value += if (minor.cityStateFunctions.getProtectorCivs().isNotEmpty()) -20 else 20
        if (isAllies(minor, civ) || isFriends(minor, civ)) value = 0
        return value
    }

    /** The buyout desirability of DoContactMinorCivs (Austria's diplomatic marriage); null if not possible now. Counts above 100 */
    fun buyoutValue(civ: Civilization, minor: Civilization): Int? {
        val minorCapital = minor.getCapital() ?: return null
        if (!civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold) || !minor.cityStateFunctions.canBeMarriedBy(civ)) return null
        val ai = civ.ai
        var value = 100
        val continent = minorCapital.getCenterTile().getContinent()
        val presenceInArea = civ.cities.any { it.getCenterTile().getContinent() == continent }
        // As in the original, every visible rival capital counts, wherever it is
        val majorCapitalsVisible = civ.gameInfo.getAliveMajorCivs().count { it != civ && it.getCapital()?.getCenterTile()?.isVisible(civ) == true }
        var minorMilitaryUnits = 0
        var minorUnits = 0
        for (unit in minor.units.getCivUnits()) {
            if (unit.isMilitary()) minorMilitaryUnits++
            minorUnits++
        }
        val losingAllWars = WarStateAi.stateAllWars(civ) == StateAllWars.Losing
        if (!presenceInArea) {
            value += when {
                ai.isGoingForWorldConquest() && majorCapitalsVisible > 0 -> 100
                ai.isEconomicStrategyActive(EXPAND_TO_OTHER_CONTINENTS) -> 60
                else -> -50
            }
        } else when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> {
                value += 100
                if (losingAllWars) value += if (minorMilitaryUnits > 0) minorMilitaryUnits * 10 else -50
            }
            DiplomacyTurn.PROXIMITY_CLOSE -> value += 10
            DiplomacyTurn.PROXIMITY_FAR -> value += -50
            DiplomacyTurn.PROXIMITY_DISTANT -> value += -100
        }
        if (civ.stats.getUnitSupply() >= civ.units.getCivUnitsSize() + minorUnits) value += minorMilitaryUnits * 5
        val needHappiness = ai.isEconomicStrategyActive(NEED_HAPPINESS)
        val needHappinessCritical = ai.isEconomicStrategyActive(NEED_HAPPINESS_CRITICAL)
        if (needHappiness) value += -50
        if (needHappinessCritical) value += -150
        if (minor.cityStateType.name == CULTURED && ai.isGoingForCultureVictory()) value += -70
        else if (minor.cityStateType.name == MERCANTILE) {
            if (needHappiness) value += -100
            if (needHappinessCritical) value += -150
        }
        return value
    }
    //endregion

    /**
     *  CvMinorCivAI::DoPickPersonality's flavor change for a city-state AI: Friendly lowers City Defense, Defense and Offense by 2,
     *  Hostile raises them by 2, each through CvFlavorManager::GetAdjustedValue within 0..10. As in the original, the "plus-minus" is
     *  the change itself, so a Hostile city-state gets a random ±2 and a Friendly one a random number from a range of 2 × (-2) + 1
     *  read as an unsigned 16-bit count (65533), which puts these flavors at 10 nearly always. Unciv keeps the personality of the
     *  nation instead of a random one.
     */
    fun pickInitialFlavors(civ: Civilization, flavors: IntArray, random: Random) {
        val change = when (civ.cityStatePersonality) {
            CityStatePersonality.Friendly -> -2
            CityStatePersonality.Hostile -> 2
            else -> return
        }
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) {
            val range = (change * 2 + 1) and 0xFFFF
            flavors[flavor.ordinal] = (flavors[flavor.ordinal] + random.nextInt(range) - change).coerceIn(0, 10)
        }
    }

    private const val CULTURED = "Cultured"
    private const val MILITARISTIC = "Militaristic"
    private const val MARITIME = "Maritime"
    private const val MERCANTILE = "Mercantile"
    private const val RELIGIOUS = "Religious"
    private const val EXPAND_TO_OTHER_CONTINENTS = "ECONOMICAISTRATEGY_EXPAND_TO_OTHER_CONTINENTS"
    private const val NEED_HAPPINESS = "ECONOMICAISTRATEGY_NEED_HAPPINESS"
    private const val NEED_HAPPINESS_CRITICAL = "ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL"
    private const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"
}

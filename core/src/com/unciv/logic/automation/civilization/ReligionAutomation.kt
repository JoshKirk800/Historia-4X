package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.city.HurryChoice
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.TheftAi
import com.unciv.logic.automation.diplomacy.TheftType
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.strategy.UnitProductionAi
import com.unciv.logic.automation.unit.CityDistances
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.mapunit.actions.UnitActionsReligion
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PurchaseReason
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.Countables
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.translations.removeConditionals
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Port of CvReligionAI (CvReligionClasses.cpp): faith purchases, belief choices and the strategic targets of religious units */
object ReligionAutomation {

    //region Inputs the original reads

    /** Vox Populi's religion AI (ModConstants.religionCultureAiLikeVp) */
    fun religionAiLikeVp(civ: Civilization): Boolean = civ.gameInfo.ruleset.modOptions.constants.religionCultureAiLikeVp

    /**
     *  CvReligionAI::GetReligionToSpread: the [foundedReligion]; with [religionAiLikeVp] VP's (V:CvReligionClasses.cpp:6720-6763): none for a
     *  City-State, else the state religion if it is a founded one, else with [considerForeign] the [favoriteForeignReligion] for spreading at home.
     *  VP works it out once a turn, so whichever caller asks first decides whether foreign religions count for that turn; here each caller's
     *  [considerForeign] counts, as the callers mean it.
     */
    fun religionToSpread(civ: Civilization, considerForeign: Boolean = false): Religion? {
        if (!religionAiLikeVp(civ)) return foundedReligion(civ)
        if (!civ.isMajorCiv) return null
        civ.religionManager.getStateReligionCached()?.takeIf { it.isMajorReligion() }?.let { return it }
        return if (considerForeign) favoriteForeignReligion(civ, forInternalSpread = true) else null
    }

    /** The religion this civilization founded, null before it has one (a pantheon does not count): the older CvReligionAI::GetReligionToSpread,
     *  which the code not yet ported from Vox Populi reads */
    fun foundedReligion(civ: Civilization): Religion? =
        civ.religionManager.religion?.takeIf { civ.religionManager.religionState >= ReligionState.Religion }

    /**
     *  CvReligionAI::GetFavoriteForeignReligion (V:CvReligionClasses.cpp:6767-6858), once no more religions can be founded: of the founded
     *  religions of others, the one whose beliefs (no Founder or Enhancer beliefs, [BeliefScoring.scoreBelief]) we like best, × our opinion of its
     *  founder (Ally 150%, Friend 130%, Favorable 115%, Neutral 100%, Competitor 50%, else 20%), among those that are the majority in one of our
     *  cities, as only there can we buy their missionaries. [forInternalSpread]: none if that one scores below 2/3 of the best of all.
     */
    fun favoriteForeignReligion(civ: Civilization, forInternalSpread: Boolean): Religion? {
        if (civ.religionManager.remainingFoundableReligions() > 0) return null
        val context = BeliefScoringContext(civ)
        var bestOverall = 0
        var bestValidScore = 0
        var bestValid: Religion? = null
        for (religion in civ.religionManager.religionsInFoundingOrder()) {
            if (!religion.isMajorReligion() || religion.foundingCiv == civ) continue
            var score = 0
            for (belief in religion.getAllBeliefsOrdered())
                if (belief.type != BeliefType.Founder && belief.type != BeliefType.Enhancer) score += BeliefScoring.scoreBelief(belief, context)
            score = score * when (civ.ai.diplomacy.opinionOf(religion.foundingCiv)) {
                MajorCivOpinion.Ally -> 150
                MajorCivOpinion.Friend -> 130
                MajorCivOpinion.Favorable -> 115
                MajorCivOpinion.Neutral -> 100
                MajorCivOpinion.Competitor -> 50
                else -> 20
            } / 100
            if (score > bestOverall) bestOverall = score
            if (score > bestValidScore && civ.cities.any { majorityReligionName(it) == religion.name }) {
                bestValidScore = score
                bestValid = religion
            }
        }
        // a bird in the hand is worth two in the bush, but not three
        if (forInternalSpread && bestValidScore * 3 < bestOverall * 2) return null
        return bestValid
    }

    /** The original's UNIT_MISSIONARY, as this civilization's unit: the first unit that can spread religion and is not a Great Person */
    fun missionaryUnit(civ: Civilization): BaseUnit? =
        civ.gameInfo.ruleset.units.values
            .firstOrNull { it.replaces == null && it.hasUnique(UniqueType.CanSpreadReligion) && !it.isGreatPerson }
            ?.let { civ.getEquivalentUnit(it) }

    /** The original's UNIT_INQUISITOR, as this civilization's unit: the first unit that can remove heresy and is not a Great Person */
    fun inquisitorUnit(civ: Civilization): BaseUnit? =
        civ.gameInfo.ruleset.units.values
            .firstOrNull { it.replaces == null && it.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals) && !it.isGreatPerson }
            ?.let { civ.getEquivalentUnit(it) }

    /** CvPlayer::GetNumUnitsWithUnitAI(UNITAI_MISSIONARY) */
    private fun numberOfMissionaries(civ: Civilization): Int =
        civ.units.getCivUnits().count { it.baseUnit.hasUnique(UniqueType.CanSpreadReligion) && !it.baseUnit.isGreatPerson }

    private fun isIndustrialOrLater(civ: Civilization): Boolean =
        civ.getEraNumber() >= (civ.gameInfo.ruleset.eras["Industrial era"]?.eraNumber ?: -1)

    /** CvMap::GetAIMapHint() & 1 (naval map): the map scripts that set it are Archipelago and Tiny Islands, Unciv's Archipelago */
    private fun isNavalMap(civ: Civilization): Boolean = civ.gameInfo.tileMap.mapParameters.type == MapType.archipelago

    /** The name of the Great Person class of [unit] (the original's UNITCLASS_*): its own name or the unit it replaces */
    private fun unitClass(unit: BaseUnit): String = unit.replaces ?: unit.name

    private fun majorityReligionName(city: City): String? = city.religion.getMajorityReligionName()

    /** CvCityReligions::GetFollowersOtherReligions: followers of the founded religions other than [religion] (no pantheons, no atheists) */
    private fun followersOfOtherReligions(city: City, religion: String): Int {
        val religions = city.civ.gameInfo.religions
        var followers = 0
        for ((name, amount) in city.religion.getNumberOfFollowers())
            if (name != religion && religions[name]?.isMajorReligion() == true) followers += amount
        return followers
    }
    //endregion

    //region Faith purchases (CvReligionAI::DoFaithPurchases)

    /** The branches of CvReligionAI::DoFaithPurchases, in priority order */
    enum class FaithPurchase {
        /** No religion (or not enhanced yet) and still possible: save */
        Save,
        /** Locked out of founding: a faith building of the majority religion of one of our cities */
        LockedOutFaithBuilding,
        /** Locked out of founding, nothing to build, Industrial era: the desired Great Person */
        LockedOutGreatPerson,
        /** A city of ours needs a Great Prophet critically, and we have earned at most 5 */
        Prophet,
        MissionaryForOwnCities,
        FaithBuilding,
        NonFaithBuilding,
        GreatPerson,
        MissionaryForPuppets,
        MissionaryForCivsWithoutReligion,
        Inquisitor,
        MissionaryForReligionFounders,
        OtherFaithBuilding,
    }

    /** CvReligionAI::DoTurn → DoFaithPurchases */
    fun spendFaithOnReligion(civ: Civilization) {
        if (civ.cities.isEmpty()) return
        if (religionAiLikeVp(civ)) {
            doTurnLikeVp(civ)
            return
        }
        when (chooseFaithPurchase(civ)) {
            FaithPurchase.Save -> {}
            FaithPurchase.LockedOutFaithBuilding, FaithPurchase.OtherFaithBuilding -> buyAnyAvailableFaithBuilding(civ)
            FaithPurchase.LockedOutGreatPerson, FaithPurchase.GreatPerson -> getDesiredFaithGreatPerson(civ)?.let { buyGreatPerson(civ, it) }
            FaithPurchase.Prophet -> civ.religionManager.getGreatProphetEquivalent()?.let { buyGreatPerson(civ, it) }
            FaithPurchase.MissionaryForOwnCities, FaithPurchase.MissionaryForPuppets,
            FaithPurchase.MissionaryForCivsWithoutReligion, FaithPurchase.MissionaryForReligionFounders ->
                buyReligiousUnit(civ, missionaryUnit(civ))
            FaithPurchase.FaithBuilding -> {
                val religion = foundedReligion(civ) ?: return
                val building = faithBuildingAvailable(civ, religion) ?: return
                buyFaithBuilding(civ, religion, building)
            }
            FaithPurchase.NonFaithBuilding -> findNonFaithBuildingPurchase(civ)?.let { (city, building) -> purchase(city, building) }
            FaithPurchase.Inquisitor -> buyReligiousUnit(civ, inquisitorUnit(civ))
        }
    }

    /** CvReligionAI::DoFaithPurchases: which purchase this turn's faith is saved for */
    fun chooseFaithPurchase(civ: Civilization): FaithPurchase {
        val religionManager = civ.religionManager
        val religion = foundedReligion(civ)
        if (religion == null || religionManager.religionState != ReligionState.EnhancedReligion) {
            if (religion != null || religionManager.remainingFoundableReligions() > 0) return FaithPurchase.Save
            if (findAnyAvailableFaithBuilding(civ) != null) return FaithPurchase.LockedOutFaithBuilding
            if (isIndustrialOrLater(civ) && getDesiredFaithGreatPerson(civ) != null) return FaithPurchase.LockedOutGreatPerson
            return FaithPurchase.Save
        }

        val faithBuilding = faithBuildingAvailable(civ, religion)
        val tooManyMissionaries = numberOfMissionaries(civ) > civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MAX_MISSIONARIES)
        return when {
            religionManager.getGreatProphetEquivalent() != null && chooseProphetConversionCity(civ, true) != null
                && religionManager.greatProphetsEarned() <= 5 -> FaithPurchase.Prophet
            !tooManyMissionaries && !areAllOurCitiesConverted(civ, religion, includePuppets = false) -> FaithPurchase.MissionaryForOwnCities
            faithBuilding != null && !areAllOurCitiesWithFaithBuilding(civ, religion, faithBuilding, includePuppets = false) ->
                FaithPurchase.FaithBuilding
            findNonFaithBuildingPurchase(civ) != null -> FaithPurchase.NonFaithBuilding
            isIndustrialOrLater(civ) && getDesiredFaithGreatPerson(civ) != null -> FaithPurchase.GreatPerson
            !tooManyMissionaries && faithBuilding != null && !areAllOurCitiesWithFaithBuilding(civ, religion, faithBuilding, includePuppets = true) ->
                FaithPurchase.MissionaryForPuppets
            !tooManyMissionaries && haveNearbyConversionTarget(civ, religion, canIncludeReligionStarter = false) ->
                FaithPurchase.MissionaryForCivsWithoutReligion
            !haveEnoughInquisitors(civ, religion) -> FaithPurchase.Inquisitor
            !tooManyMissionaries && haveNearbyConversionTarget(civ, religion, canIncludeReligionStarter = true) ->
                FaithPurchase.MissionaryForReligionFounders
            else -> FaithPurchase.OtherFaithBuilding
        }
    }

    /** CvReligionAI::AreAllOurCitiesConverted (to no religion: none of them has a majority) */
    private fun areAllOurCitiesConverted(civ: Civilization, religion: Religion?, includePuppets: Boolean): Boolean =
        civ.cities.none { majorityReligionName(it) != religion?.name && (includePuppets || !it.isPuppet) }

    /** CvReligionAI::AreAllOurCitiesHaveFaithBuilding: every city following [religion] has [building] */
    private fun areAllOurCitiesWithFaithBuilding(civ: Civilization, religion: Religion, building: Building, includePuppets: Boolean): Boolean =
        civ.cities.none {
            majorityReligionName(it) == religion.name && (includePuppets || !it.isPuppet) && !it.cityConstructions.isBuilt(building.name)
        }

    /** CvReligionAI::FaithBuildingAvailable: the first building [religion]'s beliefs make purchasable that yields Faith, as ours */
    internal fun faithBuildingAvailable(civ: Civilization, religion: Religion): Building? =
        religion.buildingsPurchasableByBeliefs.asSequence()
            .map { civ.getEquivalentBuilding(it) }
            .firstOrNull { it.faith > 0 }

    /**
     *  CvReligionAI::HaveNearbyConversionTarget: a revealed city not of our religion within missionary moves × RELIGION_MISSIONARY_RANGE_IN_TURNS
     *  of our capital, owned by a city-state or by a major that has not founded a religion (or any major, with [canIncludeReligionStarter]),
     *  skipping majors that are a bad target for a conversion ([TheftAi.isBadTheftTarget]).
     */
    internal fun haveNearbyConversionTarget(civ: Civilization, religion: Religion, canIncludeReligionStarter: Boolean): Boolean {
        val missionary = missionaryUnit(civ) ?: return false
        val capital = civ.getCapital() ?: return false
        val range = missionary.movement * civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MISSIONARY_RANGE_IN_TURNS)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
            val startedOwnReligion = if (other.isCityState) false else {
                if (TheftAi.isBadTheftTarget(civ, other, TheftType.Conversion)) continue
                religionToSpread(other) != null
            }
            if (startedOwnReligion && !canIncludeReligionStarter) continue
            for (city in other.cities)
                if (city.getCenterTile().isExplored(civ) && majorityReligionName(city) != religion.name
                    && capital.getCenterTile().aerialDistanceTo(city.getCenterTile()) <= range)
                    return true
        }
        return false
    }

    /** CvReligionAI::HaveEnoughInquisitors: one per own city whose majority is another founded religion, plus one */
    internal fun haveEnoughInquisitors(civ: Civilization, religion: Religion): Boolean {
        val needed = 1 + civ.cities.count {
            val majority = it.religion.getMajorityReligion()
            majority != null && majority.isMajorReligion() && majority != religion
        }
        val inquisitors = civ.units.getCivUnits().count { it.baseUnit.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals) && it.religion == religion.name }
        return inquisitors >= needed
    }
    //endregion

    //region Faith purchases like Vox Populi (CvReligionAI::DoFaithPurchases)

    /** The city filter of a belief's yields in the cities that follow its religion (Belief_YieldPerFollowingCity, Belief_YieldPerXFollowers) */
    private const val FOLLOWING_CITIES = "in cities following this religion"

    /** The city filter of a unit bought only where an enhanced religion leads: the original's RequiresEnhancedReligion */
    private const val ENHANCED_RELIGION_CITIES = "in all cities in which the majority religion is an enhanced religion"

    /**
     *  CvReligionAI::DoFaithPurchases (V:CvReligionClasses.cpp:7134-7374), with [religionAiLikeVp]; true when the faith is spent or saved for, so that
     *  nothing else may be bought with it. The religion we own ([com.unciv.logic.civilization.managers.ReligionManager.getOwnedReligion]) and the one we
     *  spread ([religionToSpread], foreign ones considered) decide, in order:
     *  1. a Great Prophet to enhance our religion, bought or saved for, when we have too many missionaries, or all our core cities follow the religion we
     *     spread, a prophet comes soon enough ([isProphetGainRateAcceptable]) and there is no easy target (or spreading earns us nothing, [spreadScore]);
     *  2. from the Industrial era on, the desired Great Person ([getDesiredFaithGreatPerson]), bought or saved for when it is less than
     *     max(13, 23 - religion flavor) turns of faith away;
     *  then only with a city whose majority is the religion we spread:
     *  3. a missionary or an inquisitor ([buyMissionaryOrInquisitorLikeVp]), bought or saved for, while one of our cities (puppets too) follows another;
     *  4. with all core cities converted to our own religion, missionaries for foreign cities: always saved for with a religion flavor of 10 or more and
     *     any target near; else saved for with a flavor of 7 or more (5 with easy targets) and a target that did not found a religion.
     *  Missionaries count while they can spread our religion or the one we spread; at most RELIGION_MAX_MISSIONARIES plus [spreadScore], or 1 for a
     *  religion we do not own (0 when all our cities follow it). Not built: the trait that forbids training missionaries (India) and the unique-belief
     *  rule for a civilization without natural spread, which only applies without BALANCE_UNIQUE_BELIEFS_ONLY_FOR_CIV. Unlike VP, Unciv buys no
     *  civilian in a city whose center already holds one, so such a purchase fails and the faith is saved.
     */
    fun doFaithPurchasesLikeVp(civ: Civilization): Boolean {
        val religionManager = civ.religionManager
        val owned = religionManager.getOwnedReligion()
        val spread = religionToSpread(civ, considerForeign = true)
        val prophet = religionManager.getGreatProphetEquivalent()
        val allConvertedCore = areAllOurCitiesConverted(civ, spread, includePuppets = false)
        val allConvertedWithPuppets = areAllOurCitiesConverted(civ, spread, includePuppets = true)
        val missionaries = civ.units.getCivUnits().count {
            it.religion != null && (it.religion == owned?.name || it.religion == spread?.name)
                && UnitActionModifiers.getUsableUnitActionUniques(it, UniqueType.CanSpreadReligion).any()
        }
        val flavorReligion = civ.ai.personality(Flavor.Religion)
        val desireToSpread = if (owned != null) spreadScore(civ, owned) else 0
        val maxMissionaries = when {
            owned == null || spread != owned -> if (allConvertedWithPuppets) 0 else 1
            else -> civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MAX_MISSIONARIES) + desireToSpread
        }
        val tooManyMissionaries = missionaries >= maxMissionaries
        val haveEasyTargets = spread != null && haveNearbyConversionTargetLikeVp(civ, spread, canIncludeReligionStarter = true, heathensOnly = true)
        val wantToEnhance = (!haveEasyTargets || desireToSpread < 1) && allConvertedCore && isProphetGainRateAcceptable(civ) || tooManyMissionaries

        if (owned != null && !owned.isEnhancedReligion() && wantToEnhance && prophet != null && !buyGreatPersonLikeVp(civ, prophet, owned))
            return true

        if (isIndustrialOrLater(civ)) {
            val greatPerson = getDesiredFaithGreatPerson(civ)
            val cost = civ.getCapital()?.let { greatPerson?.getStatBuyCost(it, Stat.Faith) }
            if (greatPerson != null && cost != null) {
                val faithPerTurnTimes100 = (civ.stats.statsForNextTurn.faith * 100).toInt()
                val turns = (cost * 100 - religionManager.storedFaith * 100) / max(1, faithPerTurnTimes100)
                if (turns < max(13, 23 - flavorReligion) && !buyGreatPersonLikeVp(civ, greatPerson, if (greatPerson == prophet) owned else null))
                    return true
            }
        }

        if (spread == null || civ.cities.none { majorityReligionName(it) == spread.name }) return false

        if (!tooManyMissionaries && !allConvertedWithPuppets && !buyMissionaryOrInquisitorLikeVp(civ, spread)) return true

        if (owned != null && !tooManyMissionaries && allConvertedCore && spread == owned) {
            if (flavorReligion >= 10 && haveNearbyConversionTargetLikeVp(civ, owned, canIncludeReligionStarter = true, heathensOnly = false)) {
                buyReligiousUnitLikeVp(civ, missionaryUnit(civ), owned)
                return true
            }
            if (flavorReligion >= 7 - (if (haveEasyTargets) 2 else 0)
                && haveNearbyConversionTargetLikeVp(civ, owned, canIncludeReligionStarter = false, heathensOnly = false)
                && !buyReligiousUnitLikeVp(civ, missionaryUnit(civ), owned)) return true
        }
        return false
    }

    /** CvReligionAI::BuyMissionaryOrInquisitor: an inquisitor while we have too few ([haveEnoughInquisitorsLikeVp]), else a missionary if there is a target */
    private fun buyMissionaryOrInquisitorLikeVp(civ: Civilization, religion: Religion): Boolean {
        if (!haveEnoughInquisitorsLikeVp(civ, religion)) return buyReligiousUnitLikeVp(civ, inquisitorUnit(civ), religion)
        if (haveNearbyConversionTargetLikeVp(civ, religion, canIncludeReligionStarter = true, heathensOnly = false))
            return buyReligiousUnitLikeVp(civ, missionaryUnit(civ), religion)
        return false
    }

    /** CvReligionAI::BuyMissionary / BuyInquisitor: if its price in the capital fits our faith, in the [bestCityForFaithUnit] of [religion] */
    private fun buyReligiousUnitLikeVp(civ: Civilization, unit: BaseUnit?, religion: Religion): Boolean {
        if (unit == null || !capitalCanAfford(civ, unit)) return false
        val city = bestCityForFaithUnit(civ, unit, religion) ?: return false
        return purchase(city, unit)
    }

    /** CvReligionAI::BuyGreatPerson: in the [bestCityForFaithUnit], if its price there fits our faith */
    private fun buyGreatPersonLikeVp(civ: Civilization, unit: BaseUnit, religion: Religion?): Boolean {
        val city = bestCityForFaithUnit(civ, unit, religion) ?: return false
        val cost = unit.getStatBuyCost(city, Stat.Faith) ?: return false
        if (cost > civ.religionManager.storedFaith) return false
        return purchase(city, unit)
    }

    /**
     *  CvReligionAI::GetSpreadScore (V:CvReligionClasses.cpp:7070-7132): how much spreading [religion] earns us, one for each yield its beliefs give
     *  on a conversion, a spread (here or abroad), per following city or per follower in the owner's cities, plus one for each City-State that asks
     *  us to contest its faith. Not built: the City-State influence of missionaries (Belief MissionaryInfluenceCS has no Unciv form).
     */
    fun spreadScore(civ: Civilization, religion: Religion): Int {
        var score = 0
        for (belief in religion.getAllBeliefsOrdered()) for (unique in belief.uniqueObjects) {
            if (unique.hasModifier(UniqueType.TriggerUponCityAdoptingOurReligion) || unique.hasModifier(UniqueType.TriggerUponSpreadingReligion)
                || unique.hasModifier(UniqueType.TriggerUponSpreadingReligionAbroad)) score++
            else if (unique.type == UniqueType.StatsPerCity && unique.params[1] == FOLLOWING_CITIES
                && unique.getModifiers(UniqueType.ForEveryCountable).all { it.params[0] == Countables.MajorityFollowersInOwnerCities.text })
                score += unique.stats.count { it.value > 0 }
        }
        for (cityState in civ.gameInfo.getAliveCityStates())
            if (cityState.questManager.getAssignedQuestsFor(civ).any { it.questName == QuestName.ContestFaith.value }) score++
        return score
    }

    /**
     *  CvReligionAI::HaveNearbyConversionTarget (V:CvReligionClasses.cpp:11273-11316): a city of anyone we are not at war with, within missionary moves ×
     *  RELIGION_MISSIONARY_RANGE_IN_TURNS of our nearest city, that a missionary of [religion] would score ([scoreCityForMissionaryLikeVp]); its owner
     *  must not spread a religion of its own ([religionToSpread]) unless [canIncludeReligionStarter]; with [heathensOnly] a city not of [religion] needs
     *  followers of no religion or a pantheon at least half of those not of [religion].
     */
    fun haveNearbyConversionTargetLikeVp(civ: Civilization, religion: Religion, canIncludeReligionStarter: Boolean, heathensOnly: Boolean): Boolean {
        val missionary = missionaryUnit(civ) ?: return false
        val range = missionary.movement * civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MISSIONARY_RANGE_IN_TURNS)
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || civ.isAtWarWith(other)) continue
            if (!canIncludeReligionStarter && religionToSpread(other) != null) continue
            for (city in other.cities) {
                if (CityDistances.cityDistanceInPlots(civ, city.getCenterTile()) > range) continue
                if (heathensOnly && majorityReligionName(city) != religion.name) {
                    val followers = city.religion.getNumberOfFollowers()
                    val ofMajorReligions = followers.filter { (name, _) -> civ.gameInfo.religions[name]?.isMajorReligion() == true }.values.sum()
                    val population = city.population.population
                    if (population - ofMajorReligions < (population - followers[religion.name]) / 2) continue
                }
                if (scoreCityForMissionaryLikeVp(civ, city, null, religion) > 0) return true
            }
        }
        return false
    }

    /**
     *  CvReligionAI::HaveEnoughInquisitors (V:CvReligionClasses.cpp:11336-11379): enough if we cannot have inquisitors (CanHaveInquisitors: no unit, or
     *  one bought only in cities of an enhanced religion while [religion] is not) or do not own [religion]; else our inquisitors of [religion] must be at
     *  least our cities an inquisitor would cleanse ([scoreCityForInquisitorOffensiveLikeVp]), plus 2 once [religion] is enhanced.
     */
    private fun haveEnoughInquisitorsLikeVp(civ: Civilization, religion: Religion): Boolean {
        val inquisitor = inquisitorUnit(civ) ?: return true
        val needsEnhanced = inquisitor.getMatchingUniques(UniqueType.CanBePurchasedWithStat, GameContext.IgnoreConditionals)
            .any { it.params[1] == ENHANCED_RELIGION_CITIES }
        if (needsEnhanced && !religion.isEnhancedReligion()) return true
        if (religion != civ.religionManager.getOwnedReligion()) return true
        val inquisitors = civ.units.getCivUnits()
            .count { it.baseUnit.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals) && it.religion == religion.name }
        val needed = civ.cities.count { scoreCityForInquisitorOffensiveLikeVp(civ, it, null, religion) > 0 }
        return inquisitors >= needed + if (religion.isEnhancedReligion()) 2 else 0
    }

    /**
     *  CvReligionAI::IsProphetGainRateAcceptable (V:CvReligionClasses.cpp:11452-11483): the next Great Prophet's faith
     *  ([com.unciv.logic.civilization.managers.ReligionManager.faithForNextGreatProphet]) comes in under 30 turns at this turn's faith; under 25 with a
     *  religion we own whose beliefs give a faith building, under 20 while at most 2 cities follow it.
     */
    fun isProphetGainRateAcceptable(civ: Civilization): Boolean {
        val faithPerTurnTimes100 = (civ.stats.statsForNextTurn.faith * 100).toInt()
        val turns = civ.religionManager.faithForNextGreatProphet() * 100 / max(1, faithPerTurnTimes100)
        var maxTurns = 30
        val owned = civ.religionManager.getOwnedReligion()
        if (owned != null) {
            if (faithBuildingAvailable(civ, owned) != null) maxTurns = 25
            if (civ.gameInfo.getCities().count { majorityReligionName(it) == owned.name } <= 2) maxTurns = 20
        }
        return turns < maxTurns
    }

    /**
     *  CvReligionAI::DoTurn (V:CvReligionClasses.cpp:6121-6158), with [religionAiLikeVp]: the faith goes to [doFaithPurchasesLikeVp], else to emergency
     *  inquisitors ([doReligionDefenseInCitiesLikeVp]); if neither spent nor saved it and we need not save for founding (religions still to found while
     *  we own none), each of our cities not in resistance or under siege may buy a building or unit ([doFaithPurchasesInCityLikeVp]), most faith first (halved
     *  for puppets, doubled for holy cities of any religion, ties in city order).
     */
    private fun doTurnLikeVp(civ: Civilization) {
        if (doFaithPurchasesLikeVp(civ) || doReligionDefenseInCitiesLikeVp(civ)) return
        val religionManager = civ.religionManager
        if (religionManager.remainingFoundableReligions() > 0 && religionManager.getOwnedReligion() == null) return
        val byFaith = civ.cities.filter { !it.isInResistance() && !CityDangerAi.isUnderSiege(it) }
            .sortedByDescending { city ->
                var faith = (city.cityStats.currentCityStats.faith * 100).toInt()
                if (city.isPuppet) faith /= 2
                if (city.religion.religionThisIsTheHolyCityOf != null) faith *= 2
                faith
            }
        for (city in byFaith) doFaithPurchasesInCityLikeVp(civ, city)
    }

    /**
     *  CvReligionAI::DoReligionDefenseInCities (V:CvReligionClasses.cpp:7014-7067): in each of our cities of the religion we spread ([religionToSpread],
     *  foreign ones considered) whose inquisitor price we can pay there and that no inquisitor of ours guards (CvCityReligions::IsDefendedByOurInquisitor),
     *  an inquisitor for each tile within 3 that holds a foreign Great Prophet of another religion able to reach the city or a tile next to it this turn.
     *  True once one was tried, bought or not, as the original.
     */
    fun doReligionDefenseInCitiesLikeVp(civ: Civilization): Boolean {
        val desired = religionToSpread(civ, considerForeign = true)
        val inquisitor = inquisitorUnit(civ) ?: return false
        var result = false
        for (city in civ.cities.toList()) {
            if (majorityReligionName(city) != desired?.name) continue
            val cost = inquisitor.getStatBuyCost(city, Stat.Faith) ?: continue
            if (cost > civ.religionManager.storedFaith) continue
            if (desired != null && isDefendedByOurInquisitor(city, desired.name)) continue
            val center = city.getCenterTile()
            for (tile in center.getTilesInDistance(3)) {
                if (tile.getUnits().none { unit ->
                        unit.civ != civ && unit.hasUnique(UniqueType.MayFoundReligion) && unit.religion != desired?.name
                            && center.getTilesInDistance(1).any { unit.movement.canReachInCurrentTurn(it) }
                    }) continue
                purchase(city, inquisitor)
                result = true
            }
        }
        return result
    }

    /** CvCityReligions::IsDefendedByOurInquisitor: a unit of [religion] of the city's owner in or next to it that prevents the spread */
    private fun isDefendedByOurInquisitor(city: City, religion: String): Boolean =
        city.getCenterTile().getTilesInDistance(1).any { tile ->
            tile.getUnits().any { it.civ == city.civ && it.religion == religion && it.hasUnique(UniqueType.PreventSpreadingReligion) }
        }

    /**
     *  CvReligionAI::DoFaithPurchasesInCities (V:CvReligionClasses.cpp:6865-7012) for [city] with a majority religion:
     *  1. the faith building of that religion ([faithBuildingAvailable]) if the city lacks it and can buy it now;
     *  2. else the first of our buildings the city lacks and can buy now with Faith that adds no religious pressure (NaturalReligionSpreadStrength);
     *  4. else, once all our cities but puppets follow that religion, with net gold above 0 and no unit out of supply, the unit [HurryChoice.choose]
     *     picks for Faith if [UnitProductionAi.checkUnitBuildSanity] (for purchase, weight 100) is above 0 and it is the unit an operation or army
     *     wants here, or we have at most 5 units (ships too) whose unit AI is its default one (CvPlayer::GetNumUnitsWithUnitAI).
     *  The original has no third. Not built: the choice among several faith buildings by build sanity (the first is taken), the 5 times 10 with
     *  the trait CanPurchaseNavalUnitsFaith (no Unciv counterpart).
     */
    private fun doFaithPurchasesInCityLikeVp(civ: Civilization, city: City) {
        val religion = city.religion.getMajorityReligion() ?: return
        val faithBuilding = faithBuildingAvailable(civ, religion)
        if (faithBuilding != null && !city.cityConstructions.isBuilt(faithBuilding.name) && canPurchase(city, faithBuilding)) {
            purchase(city, faithBuilding)
            return
        }
        for (building in civ.gameInfo.ruleset.buildings.values) {
            if (civ.getEquivalentBuilding(building) != building || city.cityConstructions.isBuilt(building.name)) continue
            if (building.getMatchingUniques(UniqueType.NaturalReligionSpreadStrength, GameContext.IgnoreConditionals).any { it.params[0].toInt() > 0 })
                continue
            if (canPurchase(city, building)) {
                purchase(city, building)
                return
            }
        }
        // FOURTH PRIORITY - OTHER UNITS (V:CvReligionClasses.cpp:6955-7007)
        if (!areAllOurCitiesConverted(civ, religion, includePuppets = false)) return
        if (civ.stats.statsForNextTurn.gold.toInt() <= 0 || civ.stats.getUnitSupplyDeficit() > 0) return
        val unit = HurryChoice.choose(city, unitOnly = true, faithPurchase = true)?.construction as? BaseUnit ?: return
        if (UnitProductionAi.checkUnitBuildSanity(city, unit, 100, forOperation = false, forPurchase = true) <= 0) return
        val wanted = unit == civ.ai.operations.nextUnitForOperationSlot(city)?.unit || unit == MilitaryOperations.unitForArmy(city)
        val unitAi = UnitAiTypes.defaultOf(unit, civ.gameInfo.ruleset.ai)
        if (wanted || UnitAiTypes.numUnitsWithUnitAi(civ, unitAi, includeBeingTrained = false, includeWater = true) <= MAX_FAITH_UNITS_OF_A_KIND)
            purchase(city, unit)
    }

    /** CvReligionAI::DoFaithPurchasesInCities' iPurchaseAmount (V:CvReligionClasses.cpp:6959), not times 10 for IsCanPurchaseNavalUnitsFaith (no such trait) */
    private const val MAX_FAITH_UNITS_OF_A_KIND = 5
    //endregion

    //region Where to buy (CvReligionAI::Buy*, CvReligionAIHelpers)

    private fun canPurchase(city: City, construction: INonPerpetualConstruction): Boolean {
        val cost = construction.getStatBuyCost(city, Stat.Faith) ?: return false
        return city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Faith, cost)
    }

    /** The Buy* functions buy only if the price in the capital fits the stored faith */
    private fun capitalCanAfford(civ: Civilization, construction: INonPerpetualConstruction): Boolean {
        val capital = civ.getCapital() ?: return false
        val cost = construction.getStatBuyCost(capital, Stat.Faith) ?: return true
        return cost <= civ.religionManager.storedFaith
    }

    private fun purchase(city: City, construction: INonPerpetualConstruction): Boolean =
        city.cityConstructions.purchaseConstruction(construction, -1, true, Stat.Faith)

    /** CvReligionAI::BuyMissionary / BuyInquisitor */
    private fun buyReligiousUnit(civ: Civilization, unit: BaseUnit?) {
        if (unit == null || !capitalCanAfford(civ, unit)) return
        val city = bestCityForFaithUnit(civ, unit, foundedReligion(civ)) ?: return
        purchase(city, unit)
    }

    /** CvReligionAI::BuyGreatPerson */
    private fun buyGreatPerson(civ: Civilization, unit: BaseUnit) {
        if (!capitalCanAfford(civ, unit)) return
        val city = bestCityForFaithUnit(civ, unit, null) ?: return
        purchase(city, unit)
    }

    /** CvReligionAI::BuyFaithBuilding */
    private fun buyFaithBuilding(civ: Civilization, religion: Religion, building: Building) {
        if (!capitalCanAfford(civ, building)) return
        val city = bestCityForFaithBuilding(civ, building, religion) ?: return
        purchase(city, building)
    }

    /**
     *  CvReligionAIHelpers::GetBestCityFaithUnitPurchase: a Great Person in the first city that can buy it; a religious unit (one that spreads
     *  religion or removes heresy) first in a city of our religion that gives extra spreads (Great Mosque of Djenne), then in our holy city
     *  if it still follows our religion, then in any other city of our religion.
     */
    internal fun bestCityForFaithUnit(civ: Civilization, unit: BaseUnit, religion: Religion?): City? {
        val religious = unit.hasUnique(UniqueType.CanSpreadReligion) || unit.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals)
        if (!religious && unit.isGreatPerson) return civ.cities.firstOrNull { canPurchase(it, unit) }

        var holyCity: City? = null
        if (religious && religion != null) {
            if (unit.hasUnique(UniqueType.CanSpreadReligion)) {
                val ruleset = civ.gameInfo.ruleset
                civ.cities.firstOrNull { city ->
                    majorityReligionName(city) == religion.name && canPurchase(city, unit)
                        && city.getMatchingUniques(UniqueType.UnitStartingPromotions).any {
                            ruleset.unitPromotions[it.params[2]]?.hasUnique(UniqueType.CanSpreadReligion) == true
                        }
                }?.let { return it }
            }
            holyCity = civ.cities.firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
            if (holyCity != null && majorityReligionName(holyCity) == religion.name && canPurchase(holyCity, unit)) return holyCity
        }
        return civ.cities.firstOrNull { it != holyCity && majorityReligionName(it) == religion?.name && canPurchase(it, unit) }
    }

    /** CvReligionAIHelpers::GetBestCityFaithBuildingPurchase: our holy city first, then any city that can buy it */
    private fun bestCityForFaithBuilding(civ: Civilization, building: Building, religion: Religion): City? {
        val holyCity = civ.cities.firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
        if (holyCity != null && canPurchase(holyCity, building)) return holyCity
        return civ.cities.firstOrNull { it != holyCity && canPurchase(it, building) }
    }

    /** CvReligionAI::BuyAnyAvailableFaithBuilding: in the first city whose majority religion has a faith building we can buy there */
    private fun findAnyAvailableFaithBuilding(civ: Civilization): Pair<City, Building>? {
        for (city in civ.cities) {
            val majority = city.religion.getMajorityReligion() ?: continue
            if (!majority.isMajorReligion()) continue
            val building = faithBuildingAvailable(civ, majority) ?: continue
            if (canPurchase(city, building)) return city to building
        }
        return null
    }

    private fun buyAnyAvailableFaithBuilding(civ: Civilization) {
        val (city, building) = findAnyAvailableFaithBuilding(civ) ?: return
        purchase(city, building)
    }

    /**
     *  CvReligionAI::CanBuyNonFaithBuilding / BuyAnyAvailableNonFaithBuilding: the first of our buildings that yields no Faith and that a city
     *  (in city order) can buy with Faith now (e.g. Jesuit Education). Candidates are the buildings a Faith purchase unique of the city names,
     *  or that are purchasable with Faith by themselves.
     */
    private fun findNonFaithBuildingPurchase(civ: Civilization): Pair<City, Building>? {
        val ruleset = civ.gameInfo.ruleset
        val ourBuildings = ruleset.buildings.values.filter { it.faith == 0f && civ.getEquivalentBuilding(it) == it }
        for (city in civ.cities) {
            val state = city.state
            val filters = ArrayList<String>()
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsWithStat, state))
                if (unique.params[1] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsByProductionCost, state))
                if (unique.params[1] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsForAmountStat, state))
                if (unique.params[2] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost, state))
                if (unique.params[2] == Stat.Faith.name) filters.add(unique.params[0])
            for (building in ourBuildings) {
                val candidate = filters.any { building.matchesFilter(it, state) }
                    || building.canBePurchasedWithStatReasons(null, Stat.Faith) == PurchaseReason.UniqueAllowed
                if (candidate && canPurchase(city, building)) return city to building
            }
        }
        return null
    }
    //endregion

    //region Great People for Faith (CvReligionAI::GetDesiredFaithGreatPerson)

    /**
     *  CvReligionAI::GetDesiredFaithGreatPerson: among the Great People this civilization can buy with Faith in its capital, the best by
     *  - Prophet (with a founded religion): 1500 if a city needs one critically, 750 if any city is worth converting; ÷ (1 + prophets earned)
     *  - Writer, Artist, Musician: 1000 with the Culture grand strategy, else 400
     *  - Scientist: 1000 (the original computes 1000 for Spaceship / 400 otherwise, then overwrites it with 1000)
     *  - Merchant: 1000 with the United Nations grand strategy, else 400
     *  - Engineer: 2000 while ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH is active, else 500
     *  - General / Admiral: 1000 with the Conquest grand strategy on a land / naval map, else 400; also ÷ the ones we have
     *  each ÷ (1 + that Great Person bought with Faith). Null if none scores above 0.
     */
    fun getDesiredFaithGreatPerson(civ: Civilization): BaseUnit? = timeThis("AI.DesiredFaithGreatPerson") {
        val capital = civ.getCapital() ?: return@timeThis null
        val ai = civ.ai
        val culture = ai.isGoingForCultureVictory()
        val bought = civ.civConstructions.boughtItemsWithIncreasingPrice
        var best: BaseUnit? = null
        var bestScore = 0
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!unit.isGreatPerson || !unit.hasUnique(UniqueType.Unbuildable, GameContext.IgnoreConditionals)) continue
            if (civ.getEquivalentUnit(unit) != unit) continue
            if (!unit.canBePurchasedWithStat(capital, Stat.Faith)) continue
            val fromFaith = bought[unit.name]
            val score = when (unitClass(unit)) {
                "Great Prophet" -> {
                    if (religionToSpread(civ) == null) 0
                    else {
                        val base = when {
                            chooseProphetConversionCity(civ, true) != null -> 1500
                            chooseProphetConversionCity(civ, false) != null -> 750
                            else -> 0
                        }
                        base / (1 + civ.religionManager.greatProphetsEarned())
                    }
                }
                "Great Writer", "Great Artist", "Great Musician" -> (if (culture) 1000 else 400) / (1 + fromFaith)
                "Great Scientist" -> 1000 / (1 + fromFaith)
                "Great Merchant" -> (if (ai.isGoingForDiploVictory()) 1000 else 400) / (1 + fromFaith)
                "Great Engineer" ->
                    (if (ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH")) 2000 else 500) / (1 + fromFaith)
                "Great General" -> (if (ai.isGoingForWorldConquest() && !isNavalMap(civ)) 1000 else 400) /
                    (1 + fromFaith + civ.units.getCivUnits().count { unitClass(it.baseUnit) == "Great General" })
                "Great Admiral" -> (if (ai.isGoingForWorldConquest() && isNavalMap(civ)) 1000 else 400) /
                    (1 + fromFaith + civ.units.getCivUnits().count { unitClass(it.baseUnit) == "Great Admiral" })
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = unit
            }
        }
        best
    }
    //endregion

    //region Strategic targets of religious units

    /**
     *  CvReligionAI::ChooseProphetConversionCity: the city a Great Prophet should convert, scoring above 50.
     *  Our cities: our lost holy city at once; else heretics × 100 / (distance to the holy city + 1), × 3 if another religion holds the majority
     *  (0 otherwise when [onlyBetterThanEnhancingReligion]). Then, unless [onlyBetterThanEnhancingReligion], other players' cities not defended
     *  against our religion: heretics × 50 / (distance + 1), × 3 if not of our religion, ÷ 2 for the owner's holy city, × 2 if the majority
     *  religion's founder does not own the city.
     */
    fun chooseProphetConversionCity(civ: Civilization, onlyBetterThanEnhancingReligion: Boolean): City? =
        timeThis("AI.ChooseProphetConversionCity") {
            val religion = foundedReligion(civ) ?: return@timeThis null
            val holyCity = civ.gameInfo.getCities().firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
                ?: return@timeThis null
            val holyTile = holyCity.getCenterTile()
            var bestScore = 50 // not 0, so that prophets do not always pick something
            var best: City? = null

            for (city in civ.cities) {
                val majority = city.religion.getMajorityReligion()
                if (city == holyCity && majority != religion) return@timeThis holyCity
                var score = followersOfOtherReligions(city, religion.name) * 100 / (city.getCenterTile().aerialDistanceTo(holyTile) + 1)
                if (majority != religion && majority != null && majority.isMajorReligion()) score *= 3
                else if (onlyBetterThanEnhancingReligion) score = 0
                if (score > bestScore) {
                    bestScore = score
                    best = city
                }
            }
            if (onlyBetterThanEnhancingReligion) return@timeThis best

            for (other in civ.gameInfo.civilizations) {
                if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
                val ownersReligion = foundedReligion(other)
                for (city in other.cities) {
                    if (city.religion.isProtectedByInquisitor(religion.name)) continue
                    val majority = city.religion.getMajorityReligion()
                    var score = followersOfOtherReligions(city, religion.name) * 50 / (city.getCenterTile().aerialDistanceTo(holyTile) + 1)
                    if (majority != religion) score *= 3
                    if (ownersReligion != null && city.religion.religionThisIsTheHolyCityOf == ownersReligion.name) score /= 2
                    if (majority != null && majority.foundingCiv != other) score *= 2
                    if (score > bestScore) {
                        bestScore = score
                        best = city
                    }
                }
            }
            best
        }

    /** CvReligionAI::ShouldBecomeNewMajority: [newPressure] more would give [religion] more than half of the city's pressure */
    fun shouldBecomeNewMajority(city: City, religion: String, newPressure: Int): Boolean {
        val pressures = city.religion.getPressures()
        val total = pressures.sumValues() + newPressure
        return (pressures[religion] + newPressure) * 2 > total
    }

    /**
     *  CvReligionAI::ScoreCityForMissionary: 0 if unrevealed, already of our religion, or a major that is a bad target for a conversion
     *  ([TheftAi.isBadTheftTarget]: a friend, one we promised, and the like); else 100,
     *  × 2 if the visit makes our religion the majority, × 5 for our own city or × 3 for an owner without a religion of its own,
     *  − the distance, × 2 if not at war with the owner, ÷ 2 for the owner's holy city.
     */
    fun scoreCityForMissionary(civ: Civilization, city: City, unit: MapUnit, religion: Religion): Int {
        if (!city.getCenterTile().isExplored(civ)) return 0
        if (majorityReligionName(city) == religion.name) return 0
        var score = 100
        if (shouldBecomeNewMajority(city, religion.name, UnitActionsReligion.getPressureAddedFromSpread(unit))) score *= 2
        val owner = city.civ
        if (owner.isMajorCiv && TheftAi.isBadTheftTarget(civ, owner, TheftType.Conversion)) return 0
        val ownersReligion = foundedReligion(owner)
        if (owner == civ) score *= 5
        else if (ownersReligion == null) score *= 3
        score -= unit.getTile().aerialDistanceTo(city.getCenterTile())
        if (!civ.isAtWarWith(owner)) score *= 2
        if (ownersReligion != null && city.religion.religionThisIsTheHolyCityOf == ownersReligion.name) score /= 2
        return score
    }

    /**
     *  CvCityReligions::IsDefendedAgainstSpread for the missionary's choice: an inquisitor of another religion in or next to the city.
     *  For a foreign city only the units we can see count (Unciv's AI does not read hidden units).
     */
    private fun isDefendedAgainstSpread(civ: Civilization, city: City, religion: String): Boolean {
        if (city.civ == civ) return city.religion.isProtectedByInquisitor(religion)
        return city.getCenterTile().getTilesInDistance(1).any { tile ->
            tile in civ.viewableTiles && tile.getUnits().any {
                it.religion != null && it.religion != religion && it.hasUnique(UniqueType.PreventSpreadingReligion)
            }
        }
    }

    /** CvReligionAI::ChooseMissionaryTargetCity: the best-scoring city of any player not defended against our religion */
    fun chooseMissionaryTargetCity(civ: Civilization, unit: MapUnit): City? = timeThis("AI.ChooseMissionaryTarget") {
        val religion = foundedReligion(civ) ?: return@timeThis null
        var bestScore = 0
        var best: City? = null
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive()) continue
            for (city in other.cities) {
                if (isDefendedAgainstSpread(civ, city, religion.name)) continue
                val score = scoreCityForMissionary(civ, city, unit, religion)
                if (score > bestScore) {
                    bestScore = score
                    best = city
                }
            }
        }
        best
    }

    /** CvReligionAI::ChooseInquisitorTargetCity / ScoreCityForInquisitor: our city not of our religion, scoring 100 − distance */
    internal fun chooseInquisitorTargetCity(civ: Civilization, unit: MapUnit): City? = timeThis("AI.ChooseInquisitorTarget") {
        val religion = foundedReligion(civ) ?: return@timeThis null
        var bestScore = 0
        var best: City? = null
        for (city in civ.cities) {
            if (majorityReligionName(city) == religion.name) continue
            val score = 100 - unit.getTile().aerialDistanceTo(city.getCenterTile())
            if (score > bestScore) {
                bestScore = score
                best = city
            }
        }
        best
    }
    //endregion

    //region Targets of religious units like Vox Populi (religionCultureAiLikeVp)

    /** CvReligionAI::ChooseMissionaryTargetCity: no target for a city further than this from our nearest city by path */
    private const val MISSIONARY_MAX_CITY_PATH_LENGTH = 37

    /** The holy city of [religion], whoever holds it (CvReligion::GetHolyCity) */
    private fun holyCityOf(civ: Civilization, religion: String): City? = civ.gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion) }

    /** CvCityReligions::GetTotalAccumulatedPressure(false): the pressure of the founded religions in [city], at least 1 as the callers use it */
    private fun accumulatedPressureLikeVp(city: City): Int {
        val religions = city.civ.gameInfo.religions
        var total = 0
        for ((name, pressure) in city.religion.getPressures())
            if (religions[name]?.isMajorReligion() == true) total += pressure
        return max(1, total)
    }

    /**
     *  RELIGION_MISSIONARY_PRESSURE_MULTIPLIER × ReligiousStrength × ReligionSpreads of our missionary (CvReligionAI::ScoreCityForMissionary,
     *  ScoreCityForInquisitorDefensive: "assume we spread multiple times with same strength"). Unciv's pressures are VP's ÷ 10 (a neighbour city's 6
     *  for VP's 65 on Standard, and a spread adds the religious strength itself, [UnitActionsReligion.getPressureAddedFromSpread]), so the
     *  multiplier is left out; 1 without a missionary.
     */
    private fun missionaryPressureLikeVp(civ: Civilization): Int {
        val missionary = missionaryUnit(civ) ?: return 1
        val spreads = missionary.getMatchingUniques(UniqueType.CanSpreadReligion, GameContext.IgnoreConditionals).firstOrNull()
            ?.getModifiers(UniqueType.UnitActionLimitedTimes)?.maxOfOrNull { it.params[0].toInt() } ?: 1
        return missionary.religiousStrength * spreads
    }

    /** min(100, the missionary's pressure as a percent of the city's [accumulated] pressure) */
    private fun missionaryImpactPercentLikeVp(civ: Civilization, accumulated: Int): Int =
        min(100, missionaryPressureLikeVp(civ) * 100 / accumulated)

    /**
     *  CvReligionAI::ScoreCityForMissionary (V:CvReligionClasses.cpp:10961-11118) for [unit], or for no unit when it is null:
     *  0 if unrevealed, at war with the owner, already of [religion], or a major that is a bad target for a conversion ([TheftAi.isBadTheftTarget]);
     *  else 50 − the distances to the holy city and to the unit (at least 0), + 3 × each of (missionary impact − 23) and (our share + impact − 54)
     *  where positive, × 3/2 without a founded religion as majority, 0 if an inquisitor guards the city, × 2 for our own city, else × 3/2 for a major
     *  that owns no religion (CvPlayerReligions::OwnsReligion) and has another state religion, ÷ 2 for the holy city of the religion the owner owns
     *  ([com.unciv.logic.civilization.managers.ReligionManager.getOwnedReligion], founded or conquered).
     *  Not built: VP lets a missionary spread at half strength next to an inquisitor (MOD_BALANCE_INQUISITOR_NERF, × 50%); Unciv's spread is
     *  blocked there ([com.unciv.logic.civilization.managers.ReligionManager]), as VP's is without the option, so the city scores 0. Neither is the
     *  × 3/2 for a City-State with a missionary-influence belief (no such belief in Unciv), the Horde and Rebellion quests (none in Unciv), the
     *  unique-belief rule (off in VP, MOD_BALANCE_UNIQUE_BELIEFS_ONLY_FOR_CIV) or the teammate check (no teams).
     */
    fun scoreCityForMissionaryLikeVp(civ: Civilization, city: City, unit: MapUnit?, religion: Religion): Int {
        val center = city.getCenterTile()
        if (!center.isExplored(civ)) return 0
        val owner = city.civ
        if (civ.isAtWarWith(owner)) return 0
        if (majorityReligionName(city) == religion.name) return 0
        if (owner.isMajorCiv && TheftAi.isBadTheftTarget(civ, owner, TheftType.Conversion)) return 0

        val toHolyCity = holyCityOf(civ, religion.name)?.let { center.aerialDistanceTo(it.getCenterTile()) } ?: 0
        val toUnit = unit?.let { center.aerialDistanceTo(it.getTile()) } ?: 0
        var score = max(0, 50 - toHolyCity - toUnit)
        val accumulated = accumulatedPressureLikeVp(city)
        val impact = missionaryImpactPercentLikeVp(civ, accumulated)
        val ourShare = city.religion.getPressures()[religion.name] * 100 / accumulated
        score += max(0, impact - 23) * 3
        score += max(0, ourShare + impact - 54) * 3

        if (city.religion.getMajorityReligion()?.isMajorReligion() != true) score = score * 3 / 2
        if (isDefendedAgainstSpread(civ, city, religion.name)) return 0
        if (owner == civ) return score * 2
        val ownersReligion = owner.religionManager.getOwnedReligion()
        if (owner.isMajorCiv && ownersReligion == null && owner.religionManager.getStateReligion() != religion)
            score = score * 3 / 2
        if (ownersReligion != null && city.isHolyCityOf(ownersReligion.name)) score /= 2
        return score
    }

    /**
     *  CvReligionAIHelpers::ShouldRemoveHeresy (V:CvReligionClasses.cpp:11946-11981): a founded religion other than [religion] is the majority of
     *  [city]; or, without a majority, the founded religion with the most pressure is another one, or is ours while the runner-up gains more pressure
     *  each turn ([com.unciv.logic.city.managers.CityReligionManager.getPressuresFromSurroundingCities]). Never for a pantheon majority.
     *  Pressure ties keep the order of the city's pressures, where VP's are left as its last recount sorted them. No teams, so no teammate check.
     */
    fun shouldRemoveHeresyLikeVp(city: City, religion: String): Boolean {
        val majority = city.religion.getMajorityReligion()
        if (majority != null) return majority.isMajorReligion() && majority.name != religion
        val religions = city.civ.gameInfo.religions
        val ranked = city.religion.getPressures().entries
            .filter { it.value > 0 && religions[it.key] != null }
            .sortedByDescending { it.value }
            .map { religions[it.key]!! }
        val strongest = ranked.firstOrNull() ?: return false
        if (!strongest.isMajorReligion()) return false
        if (strongest.name != religion) return true
        val runnerUp = ranked.getOrNull(1) ?: return false
        val perTurn = city.religion.getPressuresFromSurroundingCities()
        return perTurn[runnerUp.name] > perTurn[religion]
    }

    /**
     *  CvReligionAI::ScoreCityForInquisitorOffensive (V:CvReligionClasses.cpp:11121-11170): our city, not under siege ([CityDangerAi.isUnderSiege]),
     *  where heresy should go ([shouldRemoveHeresyLikeVp]) and the followers of other religions, doubled when ours is not the majority, are more than 6;
     *  the score is then the distance to [unit] (as VP has it, the farthest first, and 0 for a unit in the city), or that follower count without a unit.
     */
    fun scoreCityForInquisitorOffensiveLikeVp(civ: Civilization, city: City, unit: MapUnit?, religion: Religion): Int {
        if (CityDangerAi.isUnderSiege(city) || city.civ != civ) return 0
        if (!shouldRemoveHeresyLikeVp(city, religion.name)) return 0
        var otherFollowers = followersOfOtherReligions(city, religion.name)
        if (majorityReligionName(city) != religion.name) otherFollowers *= 2
        if (otherFollowers <= 6) return 0
        return unit?.let { city.getCenterTile().aerialDistanceTo(it.getTile()) } ?: otherFollowers
    }

    /**
     *  CvReligionAI::ScoreCityForInquisitorDefensive (V:CvReligionClasses.cpp:11173-11222): our city, not under siege, where heresy need not go;
     *  7 for our holy city, + 11 on the border with an [unfriendly] major ([AiOperationLogic.isBorderLand], CvCity::isBorderCity), + a missionary's
     *  impact percent; above 23 it scores the distance to [unit] (or itself without a unit), else 0.
     */
    fun scoreCityForInquisitorDefensiveLikeVp(
        civ: Civilization, city: City, unit: MapUnit?, religion: Religion, unfriendly: List<Civilization>
    ): Int {
        if (CityDangerAi.isUnderSiege(city) || city.civ != civ) return 0
        if (shouldRemoveHeresyLikeVp(city, religion.name)) return 0
        var score = if (city.isHolyCityOf(religion.name)) 7 else 0
        if (unfriendly.isNotEmpty() && AiOperationLogic.isBorderLand(civ, city.getCenterTile(), unfriendly)) score += 11
        score += missionaryImpactPercentLikeVp(civ, accumulatedPressureLikeVp(city))
        if (score <= 23) return 0
        return unit?.let { city.getCenterTile().aerialDistanceTo(it.getTile()) } ?: score
    }

    /** CvCityReligions::IsDefendedByOurInquisitor: another inquisitor of the city's owner and of [religion] stands in or next to [city] */
    private fun isDefendedByOurInquisitor(city: City, religion: String, ignore: MapUnit): Boolean =
        city.getCenterTile().getTilesInDistance(1).any { tile ->
            tile.getUnits().any {
                it != ignore && it.civ == city.civ && it.religion == religion && it.hasUnique(UniqueType.PreventSpreadingReligion)
            }
        }

    /** The best first, ties going to the later one: VP's ascending std::stable_sort, then std::reverse */
    private fun bestFirstLikeVp(targets: List<Pair<City, Int>>): List<City> =
        targets.asReversed().sortedByDescending { it.second }.map { it.first }

    /**
     *  CvReligionAI::ChooseMissionaryTargetCity (V:CvReligionClasses.cpp:6367-6431) before its path check: the cities with a positive
     *  [scoreCityForMissionaryLikeVp], best first, for a [unit] of the religion we spread; skipping a city whose danger to the unit is above a quarter of
     *  its health ([com.unciv.logic.automation.unit.PlotDanger]), one being razed, one [taken] by another missionary this turn and one more than 37
     *  steps from our nearest city ([CityDistances.cityDistancePathLength]). A religion we spread but do not own
     *  ([com.unciv.logic.civilization.managers.ReligionManager.getOwnedReligion]) is spread only in our own cities. The caller takes the first it can reach.
     */
    fun missionaryTargetsLikeVp(civ: Civilization, unit: MapUnit, taken: Map<City, MapUnit>): List<City> {
        val religion = religionToSpread(civ, considerForeign = true) ?: return emptyList()
        if (unit.religion != religion.name) return emptyList()
        val ownedOnly = religion != civ.religionManager.getOwnedReligion()
        val danger = civ.ai.plotDanger
        val targets = ArrayList<Pair<City, Int>>()
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive() || ownedOnly && other != civ) continue
            for (city in other.cities) {
                if (danger.get(unit, city.getCenterTile()) > unit.health / 4 || city.isBeingRazed) continue
                val holder = taken[city]
                if (holder != null && holder != unit) continue
                if (CityDistances.cityDistancePathLength(civ, city.getCenterTile()) > MISSIONARY_MAX_CITY_PATH_LENGTH) continue
                val score = scoreCityForMissionaryLikeVp(civ, city, unit, religion)
                if (score > 0) targets += city to score
            }
        }
        return bestFirstLikeVp(targets)
    }

    /** The cities an inquisitor would go to, best first: to remove heresy, and to guard */
    class InquisitorTargets(val offensive: List<City>, val defensive: List<City>)

    /**
     *  CvReligionAI::ChooseInquisitorTargetCity (V:CvReligionClasses.cpp:6434-6489) before its path checks: our cities without danger to [unit],
     *  not [taken] by another inquisitor this turn and not already guarded by another of our inquisitors ([isDefendedByOurInquisitor]), scored by
     *  [scoreCityForInquisitorOffensiveLikeVp] and [scoreCityForInquisitorDefensiveLikeVp] for the unit's own religion, which need not be the one we
     *  spread; there must be one to spread, though. The caller tries the offensive ones first.
     */
    fun inquisitorTargetsLikeVp(civ: Civilization, unit: MapUnit, taken: Map<City, MapUnit>): InquisitorTargets {
        val religion = unit.religion?.let { civ.gameInfo.religions[it] }
        if (religionToSpread(civ, considerForeign = true) == null || religion == null || !religion.isMajorReligion())
            return InquisitorTargets(emptyList(), emptyList())
        val danger = civ.ai.plotDanger
        val unfriendly = AiOperationLogic.unfriendlyMajors(civ)
        val offensive = ArrayList<Pair<City, Int>>()
        val defensive = ArrayList<Pair<City, Int>>()
        for (city in civ.cities) {
            if (danger.get(unit, city.getCenterTile()) > 0) continue
            val holder = taken[city]
            if (holder != null && holder != unit) continue
            if (isDefendedByOurInquisitor(city, religion.name, unit)) continue
            val offensiveScore = scoreCityForInquisitorOffensiveLikeVp(civ, city, unit, religion)
            if (offensiveScore > 0) offensive += city to offensiveScore
            val defensiveScore = scoreCityForInquisitorDefensiveLikeVp(civ, city, unit, religion, unfriendly)
            if (defensiveScore > 0) defensive += city to defensiveScore
        }
        return InquisitorTargets(bestFirstLikeVp(offensive), bestFirstLikeVp(defensive))
    }

    /** CvUnitReligion::IsFullStrength for a Great Prophet: it has used none of its spreads yet */
    fun isFullStrength(unit: MapUnit): Boolean =
        unit.getMatchingUniques(UniqueType.CanSpreadReligion).none { (unit.abilityToTimesUsed[it.text.removeConditionals()] ?: 0) > 0 }

    /** CvCityReligions::GetFollowersOtherReligions with bIncludePantheons: followers of the pantheons and founded religions other than [religion] */
    private fun followersOfOtherReligionsAndPantheons(city: City, religion: String): Int {
        val religions = city.civ.gameInfo.religions
        var followers = 0
        for ((name, amount) in city.religion.getNumberOfFollowers())
            if (name != religion && religions[name] != null) followers += amount
        return followers
    }

    /**
     *  CvReligionAI::ChooseProphetConversionCity (V:CvReligionClasses.cpp:6492-6717) before its path checks: the cities a Great Prophet of the religion
     *  we spread ([religionToSpread], foreign ones not considered) would convert, best first, with their scores; those must exceed 500, or 10 for
     *  a [unit] that has spread already ([isFullStrength]). There must be a holy city of the religion.
     *  - our cities not of the religion: its holy city 100000; else, unless under siege or (for a [unit]) guarded by another religion's inquisitor,
     *    (followers of other religions and pantheons + atheists) × 100 / (distance to the holy city + 1), × 3 under another founded religion;
     *  - cities of others not at war with us, not bad conversion targets ([TheftAi.isBadTheftTarget]), at most 23
     *    steps from our nearest city ([CityDistances.cityDistancePathLength]), not guarded against the religion and not of it, with followers of
     *    other religions and pantheons: those × 1000 / (distance to the holy city + 7), × the majority's pressure per turn / ours when that is
     *    smaller, ÷ 2 for the holy city of the religion the owner owns, × 2 when the majority religion's founder does not own the city, × 2 per yield
     *    other than Happiness (no VP yield) our beliefs give per foreign city or per foreign followers ([BeliefAiProfile]), × 2 for happiness per
     *    peaceful foreign followers.
     *  Not built: the Prophet Fervor and foreign-spread-immune traits (none in Unciv), the unique-belief rule, the city-state follower yields (no
     *  such belief), the instant yields of a foreign spread (none in the belief profile), the teammate checks (no teams).
     */
    fun prophetTargetsLikeVp(civ: Civilization, unit: MapUnit?): List<Pair<City, Int>> {
        val minScore = if (unit != null && !isFullStrength(unit)) 10 else 500
        val religion = religionToSpread(civ) ?: return emptyList()
        val holyTile = holyCityOf(civ, religion.name)?.getCenterTile() ?: return emptyList()
        val candidates = ArrayList<Pair<City, Int>>()
        for (city in civ.cities) {
            val majority = city.religion.getMajorityReligion()
            if (majority == religion) continue
            if (city.isHolyCityOf(religion.name)) {
                candidates += city to 100_000
                continue
            }
            if (CityDangerAi.isUnderSiege(city)) continue
            if (unit != null && city.religion.isProtectedByInquisitor(religion.name)) continue
            val atheists = city.population.population - city.religion.getNumberOfFollowers().values.sum()
            val heretics = followersOfOtherReligionsAndPantheons(city, religion.name) + atheists
            var score = heretics * 100 / (city.getCenterTile().aerialDistanceTo(holyTile) + 1)
            if (majority != null && majority.isMajorReligion()) score *= 3
            if (score > minScore) candidates += city to score
        }

        val profiles = religion.getAllBeliefsOrdered().map { BeliefAiProfile.of(it, civ) }.toList()
        val yieldDoublings = Stat.entries.count { stat ->
            stat != Stat.Happiness && profiles.any { it.yieldPerXForeignFollowers[stat] > 0 || it.yieldPerForeignCity[stat] > 0 }
        }
        val peacefulFollowers = profiles.any { it.happinessPerXPeacefulForeignFollowers > 0 }
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive() || other == civ || other.isBarbarian || other.isSpectator || civ.isAtWarWith(other)) continue
            if (TheftAi.isBadTheftTarget(civ, other, TheftType.Conversion)) continue
            val ownersReligion = other.religionManager.getOwnedReligion()
            for (city in other.cities) {
                val center = city.getCenterTile()
                if (CityDistances.cityDistancePathLength(civ, center) > PROPHET_MAX_CITY_PATH_LENGTH) continue
                if (isDefendedAgainstSpread(civ, city, religion.name)) continue
                val heretics = followersOfOtherReligionsAndPantheons(city, religion.name)
                if (heretics == 0) continue
                val majority = city.religion.getMajorityReligion()
                if (majority == religion) continue
                val perTurn = city.religion.getPressuresFromSurroundingCities()
                val ours = max(1, perTurn[religion.name])
                val theirs = majority?.let { perTurn[it.name] } ?: 0
                var score = heretics * 1000 / (center.aerialDistanceTo(holyTile) + 7)
                if (theirs < ours) score = score * theirs / ours
                if (ownersReligion != null && city.isHolyCityOf(ownersReligion.name)) score /= 2
                if (majority != null && majority.foundingCivName != other.civName) score *= 2
                repeat(yieldDoublings) { score *= 2 }
                if (peacefulFollowers) score *= 2
                if (score > minScore) candidates += city to score
            }
        }
        return candidates.asReversed().sortedByDescending { it.second }
    }

    /** CvReligionAI::ChooseProphetConversionCity's "ignore far-flung cities" (V:CvReligionClasses.cpp:6600-6602) */
    private const val PROPHET_MAX_CITY_PATH_LENGTH = 23
    //endregion

    //region Belief choices (CvReligionAI::Choose*Belief, CvGameReligions::GetReligionToFound)

    internal fun chooseReligiousBeliefs(civ: Civilization) {
        choosePantheon(civ)
        foundReligion(civ)
        enhanceReligion(civ)
        chooseFreeBeliefs(civ)
    }

    /** CvReligionAI::ChoosePantheonBelief (from CvGameReligions::DoPlayerTurn) */
    private fun choosePantheon(civ: Civilization) {
        if (!civ.religionManager.canFoundOrExpandPantheon()) return
        val pantheon = chooseBeliefs(civ, Counter<BeliefType>().apply { add(BeliefType.Pantheon, 1) }).firstOrNull() ?: return
        civ.religionManager.chooseBeliefs(listOf(pantheon), useFreeBeliefs = civ.religionManager.usingFreeBeliefs())
    }

    /** CvUnit::DoFoundReligion: the religion from CvGameReligions::GetReligionToFound, then the beliefs */
    private fun foundReligion(civ: Civilization) {
        if (civ.religionManager.religionState != ReligionState.FoundingReligion) return
        val religionName = religionToFound(civ) ?: return
        civ.religionManager.foundReligion(religionName, religionName)
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.getBeliefsToChooseAtFounding()).toList())
    }

    /** CvGameReligions::GetReligionToFound: our nation's religion if still free, else the first free one no civilization in the game
     *  prefers, else the first free one */
    internal fun religionToFound(civ: Civilization): String? {
        val used = civ.gameInfo.religions.keys
        val available = civ.gameInfo.ruleset.religions.filter { it !in used }
        civ.nation.favoredReligion?.let { if (it in available) return it }
        val preferred = civ.gameInfo.civilizations.mapNotNullTo(HashSet()) { it.nation.favoredReligion }
        return available.firstOrNull { it !in preferred } ?: available.firstOrNull()
    }

    /** CvUnit::DoEnhanceReligion: a follower and an enhancer belief */
    private fun enhanceReligion(civ: Civilization) {
        if (civ.religionManager.religionState != ReligionState.EnhancingReligion) return
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.getBeliefsToChooseAtEnhancing()).toList())
    }

    /** Free beliefs, among them the Reformation belief (CvReligionAI::ChooseReformationBelief from CvGameReligions::DoPlayerTurn) */
    private fun chooseFreeBeliefs(civ: Civilization) {
        if (!civ.religionManager.hasFreeBeliefs()) return
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.freeBeliefsAsEnums()).toList(), useFreeBeliefs = true)
    }

    /** One belief per slot of [beliefsToChoose], each by [chooseBelief]; a bonus belief (BeliefType.Any) excludes the ones chosen here */
    private fun chooseBeliefs(civ: Civilization, beliefsToChoose: Counter<BeliefType>): LinkedHashSet<Belief> {
        val chosen = LinkedHashSet<Belief>()
        val context = BeliefScoringContext(civ)
        for (type in BeliefType.entries) {
            if (type == BeliefType.None) continue
            repeat(beliefsToChoose[type]) { index ->
                chosen.add(chooseBelief(civ, type, chosen, context, index) ?: return@repeat)
            }
        }
        return chosen
    }

    /** The beliefs of [type] still available to [civ]: in no religion, not chosen in this batch, and allowed by the ruleset */
    internal fun availableBeliefs(civ: Civilization, type: BeliefType, exclude: Set<Belief> = emptySet()): List<Belief> =
        civ.gameInfo.ruleset.beliefs.values.filter {
            type.includes(it.type)
                && it !in exclude
                && civ.religionManager.getReligionWithBelief(it) == null
                && it.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                    .none { unique -> !unique.conditionalsApply(civ.state) }
                && it.getMatchingUniques(UniqueType.Unavailable, civ.state).none()
        }

    /**
     *  CvReligionAI::ChoosePantheonBelief / ChooseFounderBelief / ChooseFollowerBelief / ChooseEnhancerBelief / ChooseBonusBelief /
     *  ChooseReformationBelief: [BeliefScoring.scoreBelief] for each available belief (× the ruleset's AI weight uniques for the belief),
     *  those above 0 roll among the top 3 weighted by score.
     */
    private fun chooseBelief(civ: Civilization, type: BeliefType, exclude: Set<Belief>, context: BeliefScoringContext, index: Int): Belief? {
        val gameContext = GameContext(civ)
        val scored = availableBeliefs(civ, type, exclude).mapNotNull { belief ->
            val score = (BeliefScoring.scoreBelief(belief, context) * belief.getWeightForAiDecision(gameContext)).toInt()
            if (score > 0) belief to score else null
        }
        return chooseFromTopChoices(scored, 3, civ.ai.random("ReligionAutomation.chooseBelief.${type.name}.$index"))
    }

    /** CvWeightedVector::ChooseFromTopChoices after SortItems: a roll up to the summed weight of the [numChoices] heaviest, ties in input order */
    fun <T> chooseFromTopChoices(choices: List<Pair<T, Int>>, numChoices: Int, random: Random): T? {
        if (choices.isEmpty()) return null
        val top = choices.sortedByDescending { it.second }.take(numChoices)
        var roll = random.nextInt(top.sumOf { it.second })
        for ((choice, weight) in top) {
            roll -= weight
            if (roll < 0) return choice
        }
        return top.last().first
    }
    //endregion
}

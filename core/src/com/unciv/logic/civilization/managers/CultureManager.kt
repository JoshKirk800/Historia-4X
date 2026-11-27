package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Accomplishment
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.HistoricEvent
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.Counter
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far a civilization's tourism has made another take to its culture (CvCultureClasses.h InfluenceLevels) */
enum class InfluenceLevel(val label: String) {
    Unknown("Unknown"), Exotic("Exotic"), Familiar("Familiar"), Popular("Popular"), Influential("Influential"), Dominant("Dominant");

    /** The tourism needed, in percent of the culture of the other civilization, see the `influencePercent...` constants */
    @Readonly
    fun getThresholdPercent(civ: Civilization): Int {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        return when (this) {
            Unknown -> 0
            Exotic -> constants.influencePercentExotic
            Familiar -> constants.influencePercentFamiliar
            Popular -> constants.influencePercentPopular
            Influential -> constants.influencePercentInfluential
            Dominant -> constants.influencePercentDominant
        }
    }

    companion object {
        @Readonly
        fun fromLabel(label: String): InfluenceLevel? = entries.firstOrNull { it.label == label }
    }
}

/** The relations that change the modifier of the tourism towards another civilization, in the unique `[relativeAmount]% Tourism towards civilizations with [tourismRelation]` */
enum class TourismRelation(val label: String) {
    SharedReligion("Shared Religion"),
    OpenBorders("Open Borders"),
    TradeRoute("Trade Route"),
    LowerHappiness("Lower Happiness"),
    CommonEnemies("Common Enemies"),
    /** Both civilizations follow the same ideology */
    SharedIdeology("Shared Ideology"),
    /** Both follow an ideology, and not the same one */
    DifferentIdeologies("Different Ideologies");

    companion object {
        @Readonly
        fun fromLabel(label: String): TourismRelation? = entries.firstOrNull { it.label == label }
    }
}

enum class InfluenceTrend { Rising, Falling, Static }

/**
 * Tourism and influence of a civilization (CvPlayerCulture in the original).
 *
 * The tourism of its cities is added every turn to its influence on every other civilization it has met, after the modifiers that
 * depend on the two civilizations. Influence only grows. How influential a civilization is over another depends on how much that
 * is compared to all the culture the other has made, see [getInfluenceLevel]. Being [InfluenceLevel.Influential] over all other civilizations wins the culture victory.
 */
class CultureManager : IsPartOfGameInfoSerialization {

    /** Tourism towards each other civilization added up over the game, by civ id */
    var influence = Counter<String>()
        private set
    /** With [ModConstants.tourismPrecisionLikeVp]: the hundredths of [influence] below a whole one, which the original keeps
     *  (CvPlayerCulture::ChangeInfluenceOnTimes100 keeps m_aiCulturalInfluence in hundredths, V:CvCultureClasses.cpp:3680-3693) */
    var influenceRemainder = Counter<String>()
        private set

    /** [influence], its [influenceRemainder] and the lifetime culture as they were before the last turn, to see where things are heading */
    var lastTurnInfluence = Counter<String>()
        private set
    var lastTurnInfluenceRemainder = Counter<String>()
        private set
    var lastTurnLifetimeCulture = 0
        private set
    var lastTurnLifetimeCultureRemainder = 0
        private set

    /** What the last turn added to the influence on each civilization in hundredths (CvPlayerCulture::m_aiLastTurnCulturalIPT, set in DoTurn), and the lifetime
     *  culture that turn made, in hundredths (CvPlayer::doTurnPostDiplomacy sets m_iLastTurnCPT, V:CvPlayer.cpp:10448), for [ModConstants.influenceTrendLikeVp] */
    var lastTurnInfluenceDelta = Counter<String>()
        private set
    var lastTurnCulturePerTurnTimes100 = 0L
        private set

    /** With [ModConstants.influenceTrendLikeVp]: every change of the influence on each civilization, the turn's included (CvPlayerCulture::
     *  ChangeInfluenceOnTimes100, V:CvCultureClasses.cpp:3680-3693), and the culture of instant yields under [INSTANT_CULTURE] */
    var instantTourismHistory = InstantHistory()
        private set
    var instantCultureHistory = InstantHistory()
        private set

    /** With [ModConstants.historicEventsLikeVp]: the historic events the civilization has had (CvPlayer::m_iNumHistoricEvent), the culture and tourism of each of the
     *  last [YIELD_HISTORY_TURNS] turns (CvPlayer::m_vviYieldHistory), and all the culture and tourism of instant yields (CvPlayer::m_viInstantYieldsTotal) */
    var historicEvents = 0
        private set
    var cultureHistory = ArrayList<Int>()
        private set
    var tourismHistory = ArrayList<Int>()
        private set
    var instantCultureTotal = 0
        private set
    var instantTourismTotal = 0
        private set

    /** The notifications about the progress towards a culture victory come once each */
    var reportedTwoCivsAway = false
        private set
    var reportedOneCivAway = false
        private set
    var numCivsInfluentialLastTurn = 0
        private set

    /** What the people think of the ideology of the civilization */
    var publicOpinion = PublicOpinion()
        private set

    /**
     * The work of each kind that the civilization is willing to swap with another (CvPlayerCulture::m_iSwappable*Index), by [GreatWorkInstance.id],
     * "" for none. The AI sets them every turn (CvPlayerCulture::MoveWorks); a swap uses them up (CvGameCulture::SwapGreatWorks).
     */
    var swappableWriting = ""
    var swappableArt = ""
    var swappableArtifact = ""
    var swappableMusic = ""

    @Transient
    lateinit var civ: Civilization
        private set

    fun clone(): CultureManager {
        val toReturn = CultureManager()
        toReturn.influence = influence.clone()
        toReturn.influenceRemainder = influenceRemainder.clone()
        toReturn.lastTurnInfluence = lastTurnInfluence.clone()
        toReturn.lastTurnInfluenceRemainder = lastTurnInfluenceRemainder.clone()
        toReturn.lastTurnLifetimeCulture = lastTurnLifetimeCulture
        toReturn.lastTurnLifetimeCultureRemainder = lastTurnLifetimeCultureRemainder
        toReturn.lastTurnInfluenceDelta = lastTurnInfluenceDelta.clone()
        toReturn.lastTurnCulturePerTurnTimes100 = lastTurnCulturePerTurnTimes100
        toReturn.instantTourismHistory = instantTourismHistory.clone()
        toReturn.instantCultureHistory = instantCultureHistory.clone()
        toReturn.historicEvents = historicEvents
        toReturn.cultureHistory = ArrayList(cultureHistory)
        toReturn.tourismHistory = ArrayList(tourismHistory)
        toReturn.instantCultureTotal = instantCultureTotal
        toReturn.instantTourismTotal = instantTourismTotal
        toReturn.reportedTwoCivsAway = reportedTwoCivsAway
        toReturn.reportedOneCivAway = reportedOneCivAway
        toReturn.numCivsInfluentialLastTurn = numCivsInfluentialLastTurn
        toReturn.publicOpinion = publicOpinion.clone()
        toReturn.swappableWriting = swappableWriting
        toReturn.swappableArt = swappableArt
        toReturn.swappableArtifact = swappableArtifact
        toReturn.swappableMusic = swappableMusic
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        publicOpinion.setTransients(civ)
    }

    //region Tourism of the cities
    /** The tourism a city makes that does not depend on who it goes to, before the modifiers of the city (CvCityCulture::GetBaseTourismBeforeModifiers),
     *  in whole tourism or, with [scale] 100, in hundredths, which keeps the parts of the percentages as CvCity::getBaseYieldRateTimes100 does */
    @Readonly
    fun getCityTourismBeforeModifiers(city: City, scale: Int = 1): Int {
        if (city.isInResistance() || city.isBeingRazed) return 0
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val state = city.state

        // CvCityBuildings::GetYieldFromGreatWorksTimes100 (V:CvBuildingClasses.cpp:6651-6656, 6696-6702): the tourism of each work, the base and the buildings'
        // (global and local), for each work, then the great-work tourism modifier
        var perWork = constants.greatWorkBaseTourism
        for (unique in city.getMatchingUniques(UniqueType.TourismFromGreatWorks, state))
            if (city.matchesFilter(unique.params[1])) perWork += unique.params[0].toInt()
        var base = city.greatWorks.getNumWorks() * perWork * scale
        var greatWorkPercent = 0
        for (unique in city.getMatchingUniques(UniqueType.GreatWorksTourismPercent, state))
            if (city.matchesFilter(unique.params[1])) greatWorkPercent += unique.params[0].toInt()
        base += greatWorkPercent * base / 100
        base += city.greatWorks.getTotalThemingBonus() * scale

        var landmarkPercent = 0
        for (unique in city.getMatchingUniques(UniqueType.LandmarksTourismPercent, state))
            if (city.matchesFilter(unique.params[1])) landmarkPercent += unique.params[0].toInt()
        if (landmarkPercent > 0)
            base += (getCultureOfWonders(city) + getCultureOfWorkedTiles(city)) * scale * landmarkPercent / 100

        for (unique in city.getMatchingUniques(UniqueType.FlatTourism, state))
            if (city.matchesFilter(unique.params[1])) base += unique.params[0].toInt() * scale

        // Sacred Sites: CvCity::getBaseYieldRateTimes100 asks the majority religion of the city for GetFaithBuildingTourism(getOwner(), this) (V:CvCity.cpp:23358), which
        // goes through IsBeliefValid. Without reformationBeliefsLikeVp the beliefs of the majority religion count, whoever founded it; with it a Reformation belief only if the
        // owner of the city owns the holy city of the religion or has it as its state religion (the city follows it, it being the majority there)
        val majority = city.religion.getMajorityReligion()
        val faithBuildingTourism: (Unique) -> Unit = { unique ->
            if (city.matchesFilter(unique.params[2]))
                base += unique.params[0].toInt() * getNumBuildingsOnlyBoughtWith(city, unique.params[1]) * scale
        }
        if (majority != null) {
            if (!constants.reformationBeliefsLikeVp)
                majority.founderBeliefUniqueMap.forEachMatchingUnique(UniqueType.FlatTourismFromFaithBuildings, state, faithBuildingTourism)
            else {
                majority.founderWithoutReformationUniqueMap.forEachMatchingUnique(UniqueType.FlatTourismFromFaithBuildings, state, faithBuildingTourism)
                if (city.civ.religionManager.reformationBeliefApplies(majority, city))
                    majority.reformationBeliefUniqueMap.forEachMatchingUnique(UniqueType.FlatTourismFromFaithBuildings, state, faithBuildingTourism)
            }
        }
        return base
    }

    /** CvCityBuildings::GetNumBuildingsFromFaith (CvBuildingClasses.cpp:3185): the buildings of the city that cannot be built and that
     *  some belief of the ruleset lets you buy with [stat] */
    @Readonly
    private fun getNumBuildingsOnlyBoughtWith(city: City, stat: String): Int {
        val beliefs = city.getRuleset().beliefs.values
        return city.cityConstructions.getBuiltBuildings().count { building ->
            building.hasUnique(UniqueType.Unbuildable) && beliefs.any { belief ->
                belief.getMatchingUniques(UniqueType.BuyBuildingsWithStat, GameContext.IgnoreConditionals)
                    .any { it.params[1] == stat && building.matchesFilter(it.params[0]) }
                || belief.getMatchingUniques(UniqueType.BuyBuildingsForAmountStat, GameContext.IgnoreConditionals)
                    .any { it.params[2] == stat && building.matchesFilter(it.params[0]) }
                || belief.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost, GameContext.IgnoreConditionals)
                    .any { it.params[2] == stat && building.matchesFilter(it.params[0]) }
            }
        }
    }

    /** CvCityCulture::GetCultureFromWonders: the culture of the world wonders of the city, with what the World Congress adds per world wonder */
    @Readonly
    fun getCultureOfWonders(city: City): Int {
        var culture = 0f
        for (building in city.cityConstructions.getBuiltBuildings()) {
            if (!building.isWonder) continue
            culture += building.culture
            city.forEachMatchingUnique(UniqueType.StatsFromObject, city.state) { unique ->
                if (building.matchesFilter(unique.params[1], city.state)) culture += unique.stats.culture
            }
        }
        return culture.toInt()
    }

    /** CvCityCulture::GetCultureFromNaturalWonders and GetCultureFromImprovements: the culture of worked natural wonders and improved tiles, the city tile left out */
    @Readonly
    fun getCultureOfWorkedTiles(city: City): Int =
        city.getWorkedTiles()
            .filter { !it.isCityCenter() && (it.isNaturalWonder() || it.improvement != null) }
            .sumOf { it.stats.getTileStats(city, civ).culture.toInt() }

    /** The tourism of a city (CvCityCulture::GetBaseTourism): the above, increased by the bonuses of the city if they add up to something,
     *  in hundredths with [scale] 100 */
    @Readonly
    fun getCityTourism(city: City, skipBlockable: Civilization? = null, scale: Int = 1): Int {
        val before = getCityTourismBeforeModifiers(city, scale)
        var percent = 0
        for (unique in city.getMatchingUniques(UniqueType.TourismPercent, city.state))
            if (city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        for (unique in city.getMatchingUniques(UniqueType.TourismPercentUnlessBlocked, city.state)) {
            if (!city.matchesFilter(unique.params[1])) continue
            if (skipBlockable != null && skipBlockable.cities.any { it.cityConstructions.containsBuildingOrEquivalent(unique.params[2]) }) continue
            percent += unique.params[0].toInt()
        }
        percent += civ.gameInfo.league?.getHolyCityTourismPercent(city) ?: 0 // World Religion (LR 4.3.10)
        return if (percent > 0) before * (100 + percent) / 100 else before
    }

    /** Everything the cities of the civilization make, before what depends on who it goes to (CvPlayerCulture::GetTourism) */
    @Readonly
    fun getTourism(): Int = civ.cities.sumOf { getCityTourism(it) }
    //endregion

    //region Modifiers between two civilizations
    @Readonly
    private fun getReligionInMostCities(civ: Civilization): String? {
        val religions = civ.cities.mapNotNull { it.religion.getMajorityReligionName() }
        return religions.distinct().firstOrNull { name -> religions.count { it == name } * 2 > civ.cities.size }
    }

    @Readonly
    private fun areConnectedByTrade(other: Civilization): Boolean =
        civ.tradeRoutes.routes.any { it.getDestinationCiv() == other } || other.tradeRoutes.routes.any { it.getDestinationCiv() == civ }

    /** The relations between this civilization and [other] that hold, as seen from this one as the one who makes the tourism */
    @Readonly
    fun getRelationsWith(other: Civilization): List<TourismRelation> {
        val likeVp = civ.gameInfo.ruleset.modOptions.constants.tourismModifierLikeVp
        return TourismRelation.entries.filter { relation -> holds(relation, other, likeVp) }
    }

    /** Vox Populi: some of the people of the other follow our state religion; Brave New World: most cities of both follow the same religion.
     *  Open borders in Brave New World let us in, in Vox Populi (MOD_BALANCE_FLIPPED_OPEN_BORDERS_TOURISM) let the other in (V:CvCultureClasses.cpp:4341-4342) */
    @Readonly
    private fun holds(relation: TourismRelation, other: Civilization, likeVp: Boolean): Boolean =
        when (relation) {
            TourismRelation.SharedReligion ->
                if (likeVp) getSharedReligionFollowersLikeVp(other) > 0
                else getReligionInMostCities(civ).let { it != null && it == getReligionInMostCities(other) }
            TourismRelation.OpenBorders ->
                if (likeVp) other.getDiplomacyManager(civ)?.hasOpenBorders == true
                else civ.getDiplomacyManager(other)?.hasOpenBorders == true
            TourismRelation.TradeRoute -> areConnectedByTrade(other)
            TourismRelation.LowerHappiness -> civ.stats.happiness > other.stats.happiness
            TourismRelation.CommonEnemies -> getNumCommonEnemies(other) > 0
            TourismRelation.SharedIdeology -> civ.getIdeology().let { it != null && it == other.getIdeology() }
            TourismRelation.DifferentIdeologies -> civ.getIdeology().let { mine -> mine != null && other.getIdeology().let { it != null && it != mine } }
        }

    /** The people of [other] that follow our state religion, none if it is a pantheon (CvPlayerCulture::GetTourismModifierSharedReligion) */
    @Readonly
    private fun getSharedReligionFollowersLikeVp(other: Civilization): Int {
        val religion = civ.religionManager.getStateReligion()?.takeIf { it.isMajorReligion() } ?: return 0
        return other.cities.sumOf { it.religion.getFollowersOf(religion.name) }
    }

    /** Vox Populi's percent more tourism for the share of the people of [other] that follow our state religion, capped, times the policies'
     *  percent (TOURISM_MODIFIER_SHARED_RELIGION_TYPE 2, V:CvCultureClasses.cpp:4702-4732) */
    @Readonly
    private fun getSharedReligionModifierLikeVp(other: Civilization, constants: ModConstants): Int {
        val population = other.cities.sumOf { it.population.population }
        var modifier = constants.tourismModifierSharedReligion * 100 * getSharedReligionFollowersLikeVp(other) / max(population, 1)
        if (constants.tourismModifierSharedReligionMax > 0) modifier = min(constants.tourismModifierSharedReligionMax, modifier)
        val multiplier = civ.getMatchingUniques(UniqueType.TourismTowardsPercent)
            .filter { it.params[1] == TourismRelation.SharedReligion.label }.sumOf { it.params[0].toInt() }
        return modifier * (100 + multiplier) / 100
    }

    /** CvPlayerCulture::GetInfluenceSurveillanceTime: a familiar major (or an allied city-state's major ally) needs one turn, otherwise three. */
    @Readonly
    fun getSurveillanceTurns(other: Civilization): Int {
        val target = if (other.isCityState) other.allyCiv else other
        return if (target != null && target.isMajorCiv &&
            (getInfluenceLevel(target) ?: InfluenceLevel.Unknown) >= InfluenceLevel.Familiar) 1 else 3
    }

    @Readonly
    private fun getNumCommonEnemies(other: Civilization): Int =
        civ.gameInfo.civilizations.count {
            it.isMajorCiv && !it.isDefeated() && it != civ && it != other && civ.isAtWarWith(it) && other.isAtWarWith(it)
        }

    /** The percent more that the tourism towards [other] is (CvCityCulture::GetTourismMultiplier, which the original asks of the capital) */
    @Readonly
    fun getTourismModifierToward(other: Civilization): Int {
        if (civ.getCapital() == null) return 0
        val constants = civ.gameInfo.ruleset.modOptions.constants
        var modifier = 0
        val likeVp = constants.tourismModifierLikeVp
        val relations = getRelationsWith(other)
        for (relation in relations) modifier += when (relation) {
            TourismRelation.SharedReligion -> if (likeVp) getSharedReligionModifierLikeVp(other, constants) else constants.tourismModifierSharedReligion
            TourismRelation.OpenBorders -> constants.tourismModifierOpenBorders
            TourismRelation.TradeRoute -> constants.tourismModifierTradeRoute
            TourismRelation.DifferentIdeologies -> constants.tourismModifierDifferentIdeologies
            else -> 0
        }
        // CvPlayerCulture::GetTourismModifierWith: in Brave New World propaganda only counters a *different* ideology,
        // in Vox Populi the diplomat counts whatever the ideologies (V:CvCultureClasses.cpp:4415-4418)
        if ((likeVp || TourismRelation.DifferentIdeologies in relations) && civ.espionageManager.getActiveDiplomatFor(other) != null)
            modifier += constants.tourismModifierDiplomat
        for (unique in civ.getMatchingUniques(UniqueType.TourismTowardsPercent)) {
            val relation = TourismRelation.fromLabel(unique.params[1]) ?: continue
            if (relation !in relations) continue
            if (likeVp && relation == TourismRelation.SharedReligion) continue // a multiplier there, see getSharedReligionModifierLikeVp
            // Brave New World adds the common foe bonus for each foe, Vox Populi once (V:CvCultureClasses.cpp:4381-4398)
            val times = if (relation == TourismRelation.CommonEnemies && !likeVp) getNumCommonEnemies(other) else 1
            modifier += unique.params[0].toInt() * times
        }
        // Vox Populi: never less than all of it (V:CvCultureClasses.cpp:4479)
        return if (likeVp) max(modifier, -100) else modifier
    }
    //endregion

    //region Influence
    /** Whether tourism goes to [other] at all: it has to be a living major civilization that we have met */
    @Readonly
    fun canInfluence(other: Civilization) = other != civ && other.isMajorCiv && !other.isDefeated() && civ.knows(other)

    /** The tourism that is added to the influence on [other] every turn (CvPlayerCulture::GetInfluencePerTurn) */
    @Readonly
    fun getInfluencePerTurn(other: Civilization): Int {
        if (!canInfluence(other)) return 0
        val total = civ.cities.sumOf { getCityTourism(it, skipBlockable = other) }
        return total * (100 + getTourismModifierToward(other)) / 100
    }

    /** [getInfluencePerTurn] in hundredths. With [ModConstants.tourismPrecisionLikeVp] the tourism of the cities is added up in hundredths
     *  before the modifier towards [other] (CvPlayerCulture::GetInfluencePerTurnTimes100, V:CvCultureClasses.cpp:3778-3793) */
    @Readonly
    fun getInfluencePerTurnTimes100(other: Civilization): Long {
        if (!civ.gameInfo.ruleset.modOptions.constants.tourismPrecisionLikeVp) return getInfluencePerTurn(other) * 100L
        if (!canInfluence(other)) return 0L
        val total = civ.cities.sumOf { getCityTourism(it, skipBlockable = other, scale = 100).toLong() }
        return total * (100 + getTourismModifierToward(other)) / 100
    }

    /** The actual integer influence gained by a first trade connection; a second route adds no new modifier. */
    @Readonly
    fun getTourismIncreaseFromTradeConnection(other: Civilization): Int {
        if (!canInfluence(other) || areConnectedByTrade(other)) return 0
        val total = civ.cities.sumOf { getCityTourism(it, skipBlockable = other) }
        val baseModifier = getTourismModifierToward(other)
        val tradeBonus = civ.gameInfo.ruleset.modOptions.constants.tourismModifierTradeRoute +
            civ.getMatchingUniques(UniqueType.TourismTowardsPercent)
                .filter { it.params[1] == TourismRelation.TradeRoute.label }
                .sumOf { it.params[0].toInt() }
        return total * (100 + baseModifier + tradeBonus) / 100 -
            total * (100 + baseModifier) / 100
    }

    @Readonly
    fun getInfluence(other: Civilization) = influence[other.civID]

    /** The influence on [other] in hundredths, with what [ModConstants.tourismPrecisionLikeVp] keeps below a whole one */
    @Readonly
    fun getInfluenceTimes100(other: Civilization): Long = influence[other.civID] * 100L + influenceRemainder[other.civID]

    /** The influence on [other] as a percentage of the culture it has made in the whole game (CvPlayerCulture::GetInfluenceLevel, V:CvCultureClasses.cpp:3796-3820) */
    @Readonly
    fun getInfluencePercent(other: Civilization): Int {
        val lifetimeCulture = other.getLifetimeCultureTimes100()
        return if (lifetimeCulture > 0) (getInfluenceTimes100(other) * 100 / lifetimeCulture).toInt() else 0
    }

    /** CvPlayerCulture::GetInfluenceLevel, null if we have not met [other] */
    @Readonly
    fun getInfluenceLevel(other: Civilization): InfluenceLevel? {
        if (!civ.knows(other)) return null
        val percent = getInfluencePercent(other)
        return InfluenceLevel.entries.reversed().first { percent >= it.getThresholdPercent(civ) }
    }

    @Readonly
    fun isInfluentialOver(other: Civilization) = (getInfluenceLevel(other) ?: InfluenceLevel.Unknown) >= InfluenceLevel.Influential

    /** CvPlayerCulture::GetInfluenceTrend: whether the influence on [other] grows faster than the culture of the other */
    @Readonly
    fun getInfluenceTrend(other: Civilization): InfluenceTrend? {
        if (civ.gameInfo.ruleset.modOptions.constants.influenceTrendLikeVp) return getInfluenceTrendLikeVp(other)
        val now = other.totalCultureForContests.toLong()
        val before = other.culture.lastTurnLifetimeCulture.toLong()
        if (now <= 0 || before <= 0) return null
        val lhs = getInfluence(other).toLong() * before
        val rhs = lastTurnInfluence[other.civID].toLong() * now
        return when {
            lhs > rhs -> InfluenceTrend.Rising
            lhs < rhs -> InfluenceTrend.Falling
            else -> InfluenceTrend.Static
        }
    }

    /** CvPlayerCulture::GetTurnsToInfluential, if things go on as they are. 999 if they will not */
    @Readonly
    fun getTurnsToInfluential(other: Civilization): Int {
        val level = getInfluenceLevel(other) ?: return NEVER
        if (level >= InfluenceLevel.Influential) return 0
        if (civ.gameInfo.ruleset.modOptions.constants.influenceTrendLikeVp) return getTurnsToInfluentialLikeVp(other)
        if (getInfluenceTrend(other) != InfluenceTrend.Rising) return NEVER
        val percent = civ.gameInfo.ruleset.modOptions.constants.influencePercentInfluential
        val needed = percent.toLong() * other.totalCultureForContests / 100 - getInfluence(other)
        val perTurn = getInfluencePerTurn(other) - percent.toLong() * other.stats.statsForNextTurn.culture.toInt() / 100
        return if (perTurn > 0) ((needed + perTurn - 1) / perTurn).toInt() else NEVER
    }

    /** CvPlayerCulture::GetOtherPlayerCulturePerTurnIncludingInstantTimes100 (V:CvCultureClasses.cpp:3891-3897): the culture [other] makes per turn
     *  and its recent instant culture, in hundredths */
    @Readonly
    private fun getCulturePerTurnIncludingInstantTimes100(other: Civilization): Long =
        (other.stats.statsForNextTurn.culture * 100).toLong() +
            other.culture.instantCultureHistory.recentAverage(INSTANT_CULTURE, civ.gameInfo.turns) * 100L

    /** CvPlayerCulture::GetTourismPerTurnIncludingInstantTimes100 (V:CvCultureClasses.cpp:3901-3914): this turn's influence on [other] and the recent
     *  changes of it, in hundredths. The original's global instant tourism average is left out: nothing records it (CvPlayer::doInstantYield skips Tourism) */
    @Readonly
    private fun getTourismPerTurnIncludingInstantTimes100(other: Civilization): Long =
        getInfluencePerTurnTimes100(other) + instantTourismHistory.recentAverage(other.civID, civ.gameInfo.turns) * 100L

    /** CvPlayerCulture::GetInfluenceTrend with [ModConstants.influenceTrendLikeVp] (V:CvCultureClasses.cpp:3849-3888), its units kept: influence in
     *  hundredths over whole culture, and last turn's culture per turn in hundredths added to last turn's lifetime culture */
    @Readonly
    private fun getInfluenceTrendLikeVp(other: Civilization): InfluenceTrend {
        val theirCultureNow = (getCulturePerTurnIncludingInstantTimes100(other) / 100 + other.totalCultureForContests).toFloat().coerceAtLeast(1f)
        val theirCultureBefore = (other.culture.lastTurnLifetimeCulture + other.culture.lastTurnCulturePerTurnTimes100).toFloat().coerceAtLeast(1f)
        val ourTourismNow = (getInfluenceTimes100(other) + getTourismPerTurnIncludingInstantTimes100(other)).toFloat()
        val ourTourismBefore = (lastTurnInfluence[other.civID] * 100L + lastTurnInfluenceRemainder[other.civID] + lastTurnInfluenceDelta[other.civID]).toFloat()
        val before = ourTourismBefore / theirCultureBefore
        val now = ourTourismNow / theirCultureNow
        return when {
            abs(before - now) <= 0.1f -> InfluenceTrend.Static
            before < now -> InfluenceTrend.Rising
            else -> InfluenceTrend.Falling
        }
    }

    /** CvPlayerCulture::GetTurnsToInfluential with [ModConstants.influenceTrendLikeVp] (V:CvCultureClasses.cpp:3917-3952), below Influential */
    @Readonly
    private fun getTurnsToInfluentialLikeVp(other: Civilization): Int {
        if (getInfluenceTrendLikeVp(other) != InfluenceTrend.Rising) return NEVER
        val tourismPerTurn = getTourismPerTurnIncludingInstantTimes100(other)
        if (tourismPerTurn == 0L) return NEVER
        val percent = civ.gameInfo.ruleset.modOptions.constants.influencePercentInfluential
        val needed = percent * other.getLifetimeCultureTimes100() / 100 - getInfluenceTimes100(other)
        val divisor = max(1L, tourismPerTurn - percent * getCulturePerTurnIncludingInstantTimes100(other) / 100)
        var turns = needed / divisor
        if (needed % divisor != 0L) turns++
        return if (turns <= 0 || turns >= NEVER) NEVER else turns.toInt()
    }

    /** How many steps above Exotic the influence on [other] is: 0 up to Exotic, 1 for Familiar, up to 4 for Dominant */
    @Readonly
    private fun getInfluenceSteps(other: Civilization): Int =
        ((getInfluenceLevel(other)?.ordinal ?: 0) - InfluenceLevel.Exotic.ordinal).coerceAtLeast(0)

    /** The science more that a trade route to [other] gives (CvPlayerCulture::GetInfluenceTradeRouteScienceBonus) */
    @Readonly
    fun getTradeRouteScienceBonus(other: Civilization): Int = getInfluenceSteps(other)

    /** The percent by which a conquest of a city of [other] costs less population and resistance (CvPlayerCulture::GetInfluenceCityConquestReduction) */
    @Readonly
    fun getConquestReductionPercent(other: Civilization): Int = 25 * getInfluenceSteps(other)

    /** The civilizations that count for a culture victory: all other living major civilizations, those we have not met too */
    @Readonly
    fun getOtherMajorCivs(): List<Civilization> =
        civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() && it != civ }

    @Readonly
    fun getNumCivsInfluentialOn(): Int = getOtherMajorCivs().count { isInfluentialOver(it) }

    /**
     *  The influence on [other] that [amount] of tourism of an event makes (CvPlayerCulture::ChangeInfluenceOn with modifiers and speed, V:CvCultureClasses.cpp:3699-3735):
     *  in hundredths, times the speed's culture cost modifier, then times (100 + [getTourismModifierToward]) percent, cut to a whole number.
     *  The original's halving for a civilization with IsNoOpenTrade that has no trade connection with us is not built.
     */
    @Readonly
    fun getInstantInfluenceOn(other: Civilization, amount: Int): Int {
        var hundredths = amount.toLong() * 100 * (civ.gameInfo.speed.cultureCostModifier * 100).roundToInt() / 100
        val modifier = getTourismModifierToward(other)
        if (modifier != 0) hundredths = hundredths * (100 + modifier) / 100
        return (hundredths / 100).toInt()
    }

    fun addInfluence(other: Civilization, amount: Int) = addInfluenceTimes100(other, amount * 100L)

    /** Adds [hundredths] to the influence on [other]; the part below a whole one stays in [influenceRemainder] (CvPlayerCulture::ChangeInfluenceOnTimes100,
     *  V:CvCultureClasses.cpp:3680-3693). Only [ModConstants.tourismPrecisionLikeVp] adds what is not whole */
    fun addInfluenceTimes100(other: Civilization, hundredths: Long) {
        if (hundredths <= 0 || !canInfluence(other)) return
        val total = influenceRemainder[other.civID] + hundredths
        influence.add(other.civID, (total / 100).toInt())
        influenceRemainder[other.civID] = (total % 100).toInt()
        if (civ.gameInfo.ruleset.modOptions.constants.influenceTrendLikeVp) instantTourismHistory.add(civ.gameInfo.turns, other.civID, (hundredths / 100).toInt())
    }

    /** Tourism that is not made by the cities: goes to every civilization we have met, as it is (CvPlayerCulture::AddTourismAllKnownCivs), or with
     *  [ModConstants.instantTourismLikeVp] times the speed and the modifier towards each (AddTourismAllKnownCivsWithModifiers, V:CvCultureClasses.cpp:4835-4851) */
    fun addInfluenceOnAllKnownCivs(amount: Int) {
        val likeVp = civ.gameInfo.ruleset.modOptions.constants.instantTourismLikeVp
        for (other in getOtherMajorCivs()) addInfluence(other, if (likeVp) getInstantInfluenceOn(other, amount) else amount)
    }

    /** CvUnit::DoConcertTour: the [strength] of the tour, times the speed, with the civilization that owns the tile, a share [percentOthers] of it for
     *  each of the others. With [ModConstants.instantTourismLikeVp] each of those also takes the modifier towards that civilization, the share being
     *  of the strength before the speed (V:CvUnit.cpp:13186-13191, 13236-13249). Returns what the owner of the tile got */
    fun addInfluenceFromConcertTour(target: Civilization, strength: Int, percentOthers: Int): Int {
        val likeVp = civ.gameInfo.ruleset.modOptions.constants.instantTourismLikeVp
        val blast = (strength * civ.gameInfo.speed.cultureCostModifier).toInt()
        var toTarget = 0
        for (other in getOtherMajorCivs()) {
            val amount = when {
                likeVp -> getInstantInfluenceOn(other, if (other == target) strength else strength * percentOthers / 100)
                other == target -> blast
                else -> blast * percentOthers / 100
            }
            if (other == target) toTarget = amount
            addInfluence(other, amount)
        }
        // With [ModConstants.influenceTrendLikeVp] the strength is recorded for the owner of the tile once more, as the original does (V:CvUnit.cpp:13236-13237)
        if (civ.gameInfo.ruleset.modOptions.constants.influenceTrendLikeVp) instantTourismHistory.add(civ.gameInfo.turns, target.civID, strength)
        return toTarget
    }
    //endregion

    //region Turns
    /** What the trend looks at: how things were before this turn's culture and tourism */
    fun rememberLastTurn() {
        lastTurnInfluence = influence.clone()
        lastTurnInfluenceRemainder = influenceRemainder.clone()
        lastTurnCulturePerTurnTimes100 = civ.getLifetimeCultureTimes100() - (lastTurnLifetimeCulture * 100L + lastTurnLifetimeCultureRemainder)
        lastTurnLifetimeCulture = civ.totalCultureForContests
        lastTurnLifetimeCultureRemainder = civ.totalCultureRemainder
    }

    /** Adds this turn's tourism to the influence on every civilization met, and tells how that is going (CvPlayerCulture::DoTurn) */
    fun endTurn() {
        val delta = Counter<String>()
        for (other in getOtherMajorCivs()) {
            val hundredths = getInfluencePerTurnTimes100(other)
            addInfluenceTimes100(other, hundredths)
            if (hundredths > 0) delta.add(other.civID, hundredths.toInt())
        }
        lastTurnInfluenceDelta = delta
        publicOpinion.update()
        reportProgress()
    }

    private fun reportProgress() {
        if (!civ.isMajorCiv || civ.isSpectator) return
        val victory = civ.gameInfo.ruleset.victories.values.firstOrNull { victory ->
            victory.milestoneObjects.any { it.type == com.unciv.models.ruleset.MilestoneType.InfluentialOverAllCivs }
        }
        if (victory == null || victory.name !in civ.gameInfo.gameParameters.victoryTypes) return

        val count = getNumCivsInfluentialOn()
        val needed = getOtherMajorCivs().size
        if (count > numCivsInfluentialLastTurn && count < needed)
            civ.addNotification("We are now Influential over [$count] of [$needed] civilizations", NotificationCategory.Diplomacy, "StatIcons/Culture")
        else if (count < numCivsInfluentialLastTurn)
            civ.addNotification("We are no longer Influential over every civilization we were", NotificationCategory.Diplomacy, "StatIcons/Culture")
        numCivsInfluentialLastTurn = count

        val everAlive = civ.gameInfo.civilizations.count { it.isMajorCiv }
        if (needed > 0 && count == needed - 2 && count > 0 && !reportedTwoCivsAway && everAlive >= 4) {
            reportedTwoCivsAway = true
            for (other in civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() })
                other.addNotification("[${civ.civName}] is Influential over all but two civilizations and nears a culture victory!", NotificationCategory.Diplomacy, "StatIcons/Culture")
        }
        if (needed > 0 && count == needed - 1 && !reportedOneCivAway && everAlive >= 3) {
            reportedOneCivAway = true
            for (other in civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() })
                other.addNotification("[${civ.civName}] is Influential over all but one civilization and is about to win a culture victory!", NotificationCategory.Diplomacy, "StatIcons/Culture")
        }
    }
    //endregion

    //region Historic events
    /** CvPlayer::updateYieldPerTurnHistory (V:CvPlayer.cpp:42593-42603): the culture and tourism of the turn, of which the last [YIELD_HISTORY_TURNS] are kept */
    fun recordYieldHistory() {
        if (!civ.gameInfo.ruleset.modOptions.constants.historicEventsLikeVp) return
        cultureHistory.add(civ.stats.statsForNextTurn.culture.toInt())
        tourismHistory.add(getTourism())
        while (cultureHistory.size > YIELD_HISTORY_TURNS) cultureHistory.removeAt(0)
        while (tourismHistory.size > YIELD_HISTORY_TURNS) tourismHistory.removeAt(0)
    }

    /** Culture or tourism that instant yields gave, for [getYieldPerTurnHistory] (CvPlayer::doInstantYield adds to m_viInstantYieldsTotal, V:CvPlayer.cpp:27345) */
    fun addInstantYieldTotal(isTourism: Boolean, amount: Int) {
        if (!civ.gameInfo.ruleset.modOptions.constants.historicEventsLikeVp) return
        if (isTourism) instantTourismTotal += amount else instantCultureTotal += amount
    }

    /**
     *  CvPlayer::getYieldPerTurnHistory (V:CvPlayer.cpp:42520-42560): the culture or tourism of [turns] turns, at most the turns played. The last
     *  [YIELD_HISTORY_TURNS] turns are added up and scaled to [turns] (divided by [YIELD_HISTORY_TURNS] even when fewer have been kept), then the instant
     *  yields of the whole game on average over the turns played (plus one) are added for each of the [turns].
     */
    @Readonly
    fun getYieldPerTurnHistory(isTourism: Boolean, turns: Int): Int {
        val elapsed = civ.gameInfo.turns
        val window = min(turns, elapsed)
        if (window <= 0) return 0
        val history = if (isTourism) tourismHistory else cultureHistory
        val sum = history.sum() // never more than YIELD_HISTORY_TURNS are kept
        val instantTotal = if (isTourism) instantTourismTotal else instantCultureTotal
        return (window.toLong() * sum / YIELD_HISTORY_TURNS + window.toLong() * instantTotal / (elapsed + 1)).toInt()
    }

    /**
     *  The turns that the [HistoricEvent.turns] uniques give for [event]: those of the civilization, or those that apply in [city] (CvCity::GetLandTourismBonus)
     */
    @Readonly
    fun getHistoricEventTurns(event: HistoricEvent, city: City? = null): Int =
        (city?.getMatchingUniques(event.turns) ?: civ.getMatchingUniques(event.turns)).sumOf { it.params[0].toInt() }

    /**
     *  CvPlayer::GetHistoricEventTourism (V:CvPlayer.cpp:31252-31420): the turns that the uniques give for the kind of [event] ([getHistoricEventTurns]) say how many
     *  turns of culture (a fifth) and tourism ([getYieldPerTurnHistory]) make it, divided by the [HistoricEvent.divisor]; 0 without such turns
     */
    @Readonly
    fun getHistoricEventTourism(event: HistoricEvent, city: City? = null): Int {
        val turns = getHistoricEventTurns(event, city)
        if (turns <= 0) return 0
        return (getYieldPerTurnHistory(false, turns) / 5 + getYieldPerTurnHistory(true, turns)) / event.divisor
    }

    /**
     *  A historic event of a major civilization with [ModConstants.historicEventsLikeVp]: when it counts ([HistoricEvent.counted]) it is counted, an accomplishment
     *  as well (CvPlayer::ChangeNumHistoricEvents, V:CvPlayer.cpp:31117-31127), and its tourism ([getHistoricEventTourism], with the turns of [city]) goes to every
     *  civilization met with the modifiers towards each (CvPlayerCulture::AddTourismAllKnownCivsWithModifiers, V:CvCultureClasses.cpp:4835-4851).
     *  The end of a trade route to [destination] gives it all of it instead, and a third to each other civilization [destination] has met
     *  (CvPlayerCulture::AddTourismAllKnownCivsOtherCivWithModifiers, V:CvCultureClasses.cpp:4854-4874); a domestic route has us as [destination]
     */
    fun onHistoricEvent(event: HistoricEvent, city: City? = null, destination: Civilization? = null) {
        if (!civ.gameInfo.ruleset.modOptions.constants.historicEventsLikeVp || !civ.isMajorCiv) return
        val tourism = getHistoricEventTourism(event, city)
        val counts = when (event.counted) {
            HistoricEvent.Counted.Always -> true
            HistoricEvent.Counted.WithTurns -> getHistoricEventTurns(event, city) > 0
            HistoricEvent.Counted.WithTourism -> tourism > 0
        }
        if (!counts) return
        historicEvents++
        civ.completeAccomplishment(Accomplishment.HistoricEvent)
        civ.greatPeople.onHistoricEvent()
        if (tourism <= 0) return
        if (destination == null)
            for (other in getOtherMajorCivs()) addInfluence(other, getInstantInfluenceOn(other, tourism))
        else {
            if (destination != civ) addInfluence(destination, getInstantInfluenceOn(destination, tourism))
            for (other in getOtherMajorCivs())
                if (other != destination && destination.knows(other)) addInfluence(other, getInstantInfluenceOn(other, tourism / 3))
        }
        civ.addNotification("Our [${event.label}] made [$tourism] tourism", NotificationCategory.Diplomacy, "StatIcons/Culture")
    }

    /**
     *  CvPlayerTrade::MoveUnits (V:CvTradeClasses.cpp:2615-2696) with [ModConstants.historicEventDispatchLikeVp]: a trade route from [origin] to [destination] ended.
     *  A route to a major civilization, or a domestic one with [UniqueType.DomesticTradeRouteTourism], is a land or sea trade route event with the turns of [origin];
     *  a route to a City-State the event of trade with City-States
     */
    fun onTradeRouteEnded(isSea: Boolean, origin: City, destination: City) {
        if (!civ.gameInfo.ruleset.modOptions.constants.historicEventDispatchLikeVp) return
        val target = destination.civ
        if (target == civ && !civ.hasUnique(UniqueType.DomesticTradeRouteTourism)) return
        if (target.isCityState) onHistoricEvent(HistoricEvent.TradeCityState)
        else onHistoricEvent(if (isSea) HistoricEvent.TradeSea else HistoricEvent.TradeLand, origin, target)
    }
    //endregion

    companion object {
        /** What [getTurnsToInfluential] returns when it will not come to that */
        const val NEVER = 999
        /** The turns of culture and tourism kept for [getYieldPerTurnHistory] (VP's HISTORY_NUM_TURNS_TO_AVERAGE) */
        const val YIELD_HISTORY_TURNS = 10
        /** The key of the culture in [instantCultureHistory] */
        const val INSTANT_CULTURE = "Culture"
    }
}

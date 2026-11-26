package com.unciv.models

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.MultiFilter
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.INamed
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.Readonly

/** The city filter that says that a belief unique only works in the cities that follow the religion (City.matchesFilter, UniqueParameterType) */
private const val FOLLOWING_CITIES_FILTER = "in cities following this religion"

/** Data object for Religions */
class Religion() : INamed, IsPartOfGameInfoSerialization {

    override lateinit var name: String
    var displayName: String? = null
    var foundingTurn = 0

    private var founderBeliefs: HashSet<String> = hashSetOf()
    private var followerBeliefs: HashSet<String> = hashSetOf()

    /** The uniques of the Founder, Enhancer AND Reformation beliefs, which only the founding civilization reads (and, with
     *  [com.unciv.models.ModConstants.reformationBeliefsLikeVp] OFF, all of them). */
    @Transient
    var founderBeliefUniqueMap = UniqueMap()
    /** The uniques of the Reformation beliefs alone, for [com.unciv.models.ModConstants.reformationBeliefsLikeVp]: who reads them is
     *  [com.unciv.logic.civilization.managers.ReligionManager.reformationBeliefApplies], not whether the civilization founded the religion. */
    @Transient
    var reformationBeliefUniqueMap = UniqueMap()
    /** The uniques of the Founder and Enhancer beliefs, the part of [founderBeliefUniqueMap] that the founder reads in every case. */
    @Transient
    var founderWithoutReformationUniqueMap = UniqueMap()
    /** The Reformation uniques that are scoped to the cities that follow the religion (`in cities following this religion`, as the faith purchases of Jesuit Education and
     *  Religious Fervor are written): the city reads them in [com.unciv.logic.city.managers.CityReligionManager], like the uniques of the Follower beliefs, and
     *  [reformationCivUniqueMap] leaves them out so that a city does not get them twice (its lookups add the uniques of its civilization). */
    @Transient
    var reformationCityUniqueMap = UniqueMap()
    /** The Reformation uniques that [reformationCityUniqueMap] does not have: the ones that a civilization-wide lookup of the civilization that the belief applies to reads. */
    @Transient
    var reformationCivUniqueMap = UniqueMap()
    @Transient
    var followerBeliefUniqueMap = UniqueMap()

    @Transient
    lateinit var gameInfo: GameInfo
    
    lateinit var foundingCivName: String
        private set
    @Transient
    @Cache
    private lateinit var _foundingCiv: Civilization
    @get:Readonly
    val foundingCiv: Civilization get() {
        if (!::_foundingCiv.isInitialized) _foundingCiv = gameInfo.getCivilization(foundingCivName)
        return _foundingCiv
    }

    @delegate:Transient
    val buildingsPurchasableByBeliefs by lazy {
        unlockedBuildingsPurchasable()
    }
    
    constructor(name: String, gameInfo: GameInfo, foundingCiv: Civilization, foundingTurn: Int): this() {
        this.name = name
        this.foundingTurn = foundingTurn
        this.gameInfo = gameInfo
        this._foundingCiv = foundingCiv
        this.foundingCivName = foundingCiv.civID
    }

    constructor(name: String, gameInfo: GameInfo, foundingCivName: String, foundingTurn: Int) : this() {
        this.name = name
        this.foundingTurn = foundingTurn
        this.foundingCivName = foundingCivName
        this.gameInfo = gameInfo
    }

    fun clone(): Religion {
        val toReturn = Religion(name, gameInfo, foundingCivName, foundingTurn)
        toReturn.displayName = displayName
        toReturn.founderBeliefs.addAll(founderBeliefs)
        toReturn.followerBeliefs.addAll(followerBeliefs)
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        updateUniqueMaps()
    }

    private fun updateUniqueMaps(){
        followerBeliefUniqueMap = UniqueMap(mapToExistingBeliefs(followerBeliefs).flatMap { it.uniqueObjects })
        founderBeliefUniqueMap = UniqueMap(mapToExistingBeliefs(founderBeliefs).flatMap { it.uniqueObjects })
        val reformationUniques = mapToExistingBeliefs(founderBeliefs).filter { it.type == BeliefType.Reformation }.flatMap { it.uniqueObjects }.toList()
        reformationBeliefUniqueMap = UniqueMap(reformationUniques.asSequence())
        reformationCityUniqueMap = UniqueMap(reformationUniques.asSequence().filter { isScopedToFollowingCities(it) })
        reformationCivUniqueMap = UniqueMap(reformationUniques.asSequence().filterNot { isScopedToFollowingCities(it) })
        founderWithoutReformationUniqueMap = UniqueMap(mapToExistingBeliefs(founderBeliefs).filter { it.type != BeliefType.Reformation }.flatMap { it.uniqueObjects })
    }

    /** Whether [unique] says that it only works in the cities that follow the religion, as a parameter or as the filter of a conditional (`in cities following this religion`) */
    private fun isScopedToFollowingCities(unique: Unique): Boolean =
        FOLLOWING_CITIES_FILTER in unique.params || unique.modifiers.any { FOLLOWING_CITIES_FILTER in it.params }

    fun addBeliefs(beliefs: Iterable<Belief>){
        for (belief in beliefs){
            when (belief.type){
                BeliefType.Founder, BeliefType.Enhancer, BeliefType.Reformation -> founderBeliefs.add(belief.name)
                BeliefType.Pantheon, BeliefType.Follower -> followerBeliefs.add(belief.name)
                else -> continue // 'None' and 'Any' are not valid for beliefs, they're used for internal purposes
            }
        }
        updateUniqueMaps()
    }

    fun addBelief(belief: Belief) = addBeliefs(listOf(belief))
    fun addBelief(beliefName: String) = gameInfo.ruleset.beliefs[beliefName]?.let { addBelief(it) }

    @Readonly
    fun getIconName() =
        if (isPantheon()) "Pantheon"
        else name

    @Readonly
    fun getReligionDisplayName() =
        if (displayName != null) displayName!!
        else name

    @Readonly
    private fun mapToExistingBeliefs(beliefs: Set<String>): Sequence<Belief> {
        val rulesetBeliefs = gameInfo.ruleset.beliefs
        return beliefs.asSequence().mapNotNull { rulesetBeliefs[it] }
    }

    /** The Founder, Enhancer and Reformation beliefs: the ones [founderBeliefUniqueMap] is made of */
    @Readonly
    fun getFounderSideBeliefs(): Sequence<Belief> = mapToExistingBeliefs(founderBeliefs)

    @Readonly
    fun getBeliefs(beliefType: BeliefType): Sequence<Belief> {
        if (beliefType == BeliefType.Any)
            return mapToExistingBeliefs((founderBeliefs + followerBeliefs).toHashSet())

        val beliefs =
            when {
                beliefType.isFollower -> followerBeliefs
                beliefType.isFounder -> founderBeliefs
                else -> null!! // This is fine...
            }

        return mapToExistingBeliefs(beliefs)
            .filter { it.type == beliefType }
    }

    @Readonly fun getAllBeliefsOrdered(): Sequence<Belief> {
        return mapToExistingBeliefs(followerBeliefs).filter { it.type == BeliefType.Pantheon } +
            mapToExistingBeliefs(founderBeliefs).filter { it.type == BeliefType.Founder } +
            mapToExistingBeliefs(followerBeliefs).filter { it.type == BeliefType.Follower } +
            mapToExistingBeliefs(founderBeliefs).filter { it.type == BeliefType.Enhancer } +
            mapToExistingBeliefs(founderBeliefs).filter { it.type == BeliefType.Reformation }
    }

    @Readonly fun hasBelief(belief: String) = followerBeliefs.contains(belief) || founderBeliefs.contains(belief)

    @Readonly fun isPantheon() = getBeliefs(BeliefType.Pantheon).any() && !isMajorReligion()
    @Readonly fun isMajorReligion() = getBeliefs(BeliefType.Founder).any()
    @Readonly fun isEnhancedReligion() = getBeliefs(BeliefType.Enhancer).any()

    @Readonly
    fun matchesFilter(filter: String, state: GameContext = GameContext.IgnoreConditionals, civ: Civilization? = null): Boolean {
        return MultiFilter.multiFilter(filter, { matchesSingleFilter(it, state, civ) })
    }

    @Readonly
    private fun matchesSingleFilter(filter: String, state: GameContext = GameContext.IgnoreConditionals, civ: Civilization? = null): Boolean {
        val foundingCiv = foundingCiv
        when (filter) {
            "any" -> return true
            "major" -> return isMajorReligion()
            "enhanced" -> return isEnhancedReligion()
            "your" -> return civ == foundingCiv
            "foreign" -> return civ != null && civ != foundingCiv
            "enemy" -> {
                if (civ == null) return false
                return civ.knows(foundingCiv) && civ.isAtWarWith(foundingCiv)
            }
            else -> {
                if (filter == name) return true
                if (filter in getBeliefs(BeliefType.Any).map { it.name }) return true
                if (founderBeliefUniqueMap.hasMatchingTagUnique(filter, state)) return true
                if (followerBeliefUniqueMap.hasMatchingTagUnique(filter, state)) return true
                return false
            }
        }
    }

    @Readonly
    private fun unlockedBuildingsPurchasable(): List<String> {
        return getAllBeliefsOrdered().flatMap { belief ->
            belief.getMatchingUniques(UniqueType.BuyBuildingsWithStat).map { it.params[0] } +
            belief.getMatchingUniques(UniqueType.BuyBuildingsForAmountStat).map { it.params[0] } +
            belief.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost).map { it.params[0] }
        }.filter { gameInfo.ruleset.buildings.containsKey(it) }.toList()
    }
}

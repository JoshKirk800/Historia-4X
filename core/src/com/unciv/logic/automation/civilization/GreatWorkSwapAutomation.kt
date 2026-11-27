package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.CityDangerAi
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.ThemingBonus
import java.util.IdentityHashMap

/**
 * How the AI arranges its Great Works each turn (CvPlayerCulture::DoSwapGreatWorks and what it calls, SDK:CvCultureClasses.cpp:941-1749):
 * per kind of slot (writing, art and artifacts, music), the buildings in the order of the AI priority of their first theming bonus are
 * themed with whole matching sets of works, undamaged cities first; then those still one work short try to swap with another civilization;
 * then the first work left of each kind is marked as the one to swap; then the leftovers fill the unthemed buildings.
 * With the ModConstants `greatWorkThemingLikeVp`, Vox Populi's choice of buildings and their order: no works into a city that might fall,
 * undamaged puppets between the other undamaged cities and the endangered ones, and the theming bonuses of a building from the highest;
 * the works other civilizations offer are in the lists from the start and are swapped in when a set takes one, with no later pass for a missing work.
 *
 * Swaps between civilizations (CvGameCulture::SwapGreatWorks): both works must be the ones their owners marked as swappable. As in the DLL
 * the work wanted is looked up among the works of its creator, so only works still owned by the civilization that made them can be taken.
 * Humans have no way to mark a work in Unciv: where the DLL would take a human's marked work, the AI proposes the same one-for-one exchange
 * as a trade request instead, which the human accepting stands in for the marking. With `greatWorkThemingLikeVp` there are no such requests: a
 * human offers a work by Swap & Optimize in the culture overview ([arrangeWorksForHuman]), as in Vox Populi.
 */
object GreatWorkSwapAutomation {

    /** CvGreatWorkBuildingInMyEmpire */
    private class EmpireBuilding(val city: City, val building: Building, val endangered: Boolean, val puppet: Boolean) {
        var themed = false
        val slots get() = building.greatWorkSlotTypes.size
    }

    /**
     * What one call works with: the civilization, whether a human asked for it and whether works may be swapped with other civilizations, and the
     * cities whose works were moved, whose stats are updated once at the end
     */
    private class Context(val civ: Civilization, val human: Boolean, val swapWithOthers: Boolean) {
        val ruleset = civ.gameInfo.ruleset
        val likeVp = ruleset.modOptions.constants.greatWorkThemingLikeVp
        val touchedCities = LinkedHashSet<City>()
        /** Vox Populi: the works in the lists that another civilization holds (CvGreatWorkAvailableForUse::m_eOwnedByPlayer), as the lists see it */
        val heldBy = IdentityHashMap<GreatWorkInstance, Civilization>()
        fun typeOf(work: GreatWorkInstance): GreatWorkType? = ruleset.greatWorks[work.work]?.type
        fun isOurs(work: GreatWorkInstance) = !heldBy.containsKey(work)
    }

    /**
     * CvPlayerCulture::DoSwapGreatWorks (CvEconomicAI::DoTurn, AI players only). With `greatWorkThemingLikeVp` also for City-States, which swap
     * with no one, and right after each new work of the AI (V:CvUnit.cpp:9694-9697 and the same in CvCity::processBuilding and
     * CvPlayerCulture::DoArchaeologyChoice)
     */
    fun doSwapGreatWorks(civ: Civilization) = arrangeWorks(Context(civ, human = false, swapWithOthers = true))

    /**
     * Vox Populi's DoSwapGreatWorksHuman (V:CvCultureClasses.cpp:1134-1246), the Optimize and Swap & Optimize buttons of the culture overview: as the
     * AI, but any damage endangers a city other than the capital, puppets are not set apart, and only with [swapWithOthers] are the works others offer
     * swapped in and the first of ours left offered; without, nothing of ours is offered any more
     */
    fun arrangeWorksForHuman(civ: Civilization, swapWithOthers: Boolean) = arrangeWorks(Context(civ, human = true, swapWithOthers))

    private fun arrangeWorks(context: Context) {
        val civ = context.civ
        val buildingOrder = HashMap<String, Int>()
        for ((index, name) in context.ruleset.buildings.keys.withIndex()) buildingOrder[name] = index

        val writingBuildings = ArrayList<EmpireBuilding>()
        val artBuildings = ArrayList<EmpireBuilding>()
        val musicBuildings = ArrayList<EmpireBuilding>()
        val writings = ArrayList<GreatWorkInstance>()
        val art = ArrayList<GreatWorkInstance>()
        val artifacts = ArrayList<GreatWorkInstance>()
        val music = ArrayList<GreatWorkInstance>()

        // Cities in order, their buildings in the order of the ruleset (the DLL loops over building classes), slots in order
        val likeVp = context.likeVp
        for (city in civ.cities) {
            // Vox Populi (CvPlayerCulture::DoSwapGreatWorks, V:CvCultureClasses.cpp:1285-1304): a city that might fall soon gives its works but takes none;
            // the capital is never endangered, another city is when it has lost more than half its health or is razed below 3 citizens; puppets come
            // after the other cities. For a human (V:CvCultureClasses.cpp:1165-1182) any damage endangers a city other than the capital, and puppets are as
            // the other cities
            val destination = !likeVp || !CityDangerAi.isInDangerOfFalling(city, true)
            val endangered = when {
                !likeVp -> city.health < city.getMaxHealth()
                city.isCapital() -> false
                context.human -> city.health < city.getMaxHealth()
                else -> city.getMaxHealth() - city.health > city.getMaxHealth() / 2 || city.isBeingRazed && city.population.population < 3
            }
            val puppet = likeVp && !context.human && city.isPuppet
            for (building in city.cityConstructions.getBuiltBuildings().sortedBy { buildingOrder[it.name] ?: Int.MAX_VALUE }) {
                val types = building.greatWorkSlotTypes
                if (types.isEmpty()) continue
                if (destination) {
                    val entry = EmpireBuilding(city, building, endangered, puppet)
                    when (types[0]) {
                        GreatWorkSlotType.Literature -> writingBuildings.add(entry)
                        GreatWorkSlotType.ArtArtifact -> artBuildings.add(entry)
                        GreatWorkSlotType.Music -> musicBuildings.add(entry)
                    }
                }
                for (slot in city.greatWorks.getSlots(building)) {
                    val work = slot.getWork() ?: continue
                    when (context.typeOf(work)) {
                        GreatWorkType.Literature -> writings.add(work)
                        GreatWorkType.Art -> art.add(work)
                        GreatWorkType.Artifact -> artifacts.add(work)
                        GreatWorkType.Music -> music.add(work)
                        null -> {}
                    }
                }
            }
        }

        moveWorks(context, GreatWorkSlotType.Literature, writingBuildings, writings, ArrayList())
        moveWorks(context, GreatWorkSlotType.ArtArtifact, artBuildings, art, artifacts)
        moveWorks(context, GreatWorkSlotType.Music, musicBuildings, music, ArrayList())
        for (city in context.touchedCities) city.cityStats.update()
    }

    /** CvPlayerCulture::MoveWorks */
    private fun moveWorks(context: Context, type: GreatWorkSlotType, unsorted: List<EmpireBuilding>,
                          works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        // SortThemingBonus: by the AI priority of the first theming bonus, buildings without one last
        val buildings = unsorted.sortedByDescending { it.building.themingBonuses.firstOrNull()?.aiPriority ?: Int.MIN_VALUE }
        if (context.likeVp) {
            moveWorksLikeVp(context, type, buildings, works1, works2)
            return
        }

        for (building in buildings) if (!building.endangered)
            building.themed = themeBuilding(context, building, works1, works2, considerOtherPlayers = false)
        for (building in buildings) if (building.endangered)
            building.themed = themeBuilding(context, building, works1, works2, considerOtherPlayers = false)
        if (type != GreatWorkSlotType.Music)
            for (building in buildings) if (!building.endangered && !building.themed
                && themeBuilding(context, building, works1, works2, considerOtherPlayers = true)) building.themed = true

        // The first work left that is not themed is the one we are willing to trade
        val culture = context.civ.culture
        when (type) {
            GreatWorkSlotType.Literature -> culture.swappableWriting = works1.firstOrNull()?.id ?: ""
            GreatWorkSlotType.ArtArtifact -> {
                culture.swappableArt = works1.firstOrNull()?.id ?: ""
                culture.swappableArtifact = works2.firstOrNull()?.id ?: ""
            }
            GreatWorkSlotType.Music -> {}
        }

        for (building in buildings) if (!building.endangered && !building.themed) fillBuilding(context, building, works1, works2)
        for (building in buildings) if (building.endangered && !building.themed) fillBuilding(context, building, works1, works2)
    }

    /**
     * Vox Populi's MoveWorks (V:CvCultureClasses.cpp:1394-1597): the work each other civilization we have met and are at peace with offers joins the
     * lists first (not for music); then undamaged cities, undamaged puppets and endangered cities are themed, a work offered being swapped in when a
     * set takes it; the first of our works left is the one we offer; the leftovers fill the same tiers, with our works only.
     */
    private fun moveWorksLikeVp(context: Context, type: GreatWorkSlotType, buildings: List<EmpireBuilding>,
                                works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        // Nothing to move, and the works offered stay as they were; music, City-States and the human's Optimize swap with no one (V:CvCultureClasses.cpp:1397-1408)
        if (works1.isEmpty() && works2.isEmpty()) return
        val civ = context.civ
        val swapWithOthers = context.swapWithOthers && type != GreatWorkSlotType.Music && civ.isMajorCiv
        if (swapWithOthers) for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other) || civ.isAtWarWith(other)) continue
            fun offer(id: String, into: ArrayList<GreatWorkInstance>) {
                val work = findSlotById(other, id)?.getWork() ?: return
                context.heldBy[work] = other
                into.add(work)
            }
            if (type == GreatWorkSlotType.Literature) offer(other.culture.swappableWriting, works1)
            else {
                offer(other.culture.swappableArt, works1)
                offer(other.culture.swappableArtifact, works2)
            }
        }

        // Undamaged cities, then undamaged puppets, then endangered cities (V:CvCultureClasses.cpp:1476-1518)
        for (building in buildings) if (!building.endangered && !building.puppet) building.themed = themeBuildingLikeVp(context, building, works1, works2)
        for (building in buildings) if (!building.endangered && building.puppet) building.themed = themeBuildingLikeVp(context, building, works1, works2)
        for (building in buildings) if (building.endangered) building.themed = themeBuildingLikeVp(context, building, works1, works2)

        // The first of our works left is the one we are willing to trade; none when not swapping (V:CvCultureClasses.cpp:1522-1594)
        val culture = civ.culture
        fun firstOfOurs(works: List<GreatWorkInstance>) = if (swapWithOthers) works.firstOrNull { context.isOurs(it) }?.id ?: "" else ""
        when (type) {
            GreatWorkSlotType.Literature -> culture.swappableWriting = firstOfOurs(works1)
            GreatWorkSlotType.ArtArtifact -> {
                culture.swappableArt = firstOfOurs(works1)
                culture.swappableArtifact = firstOfOurs(works2)
            }
            GreatWorkSlotType.Music -> {}
        }

        // The same order for the leftovers (CvPlayerCulture::MoveSingleWorks, V:CvCultureClasses.cpp:2105-2180; all Vox Populi's slot buildings
        // yield culture, so its split by the focused yield changes nothing)
        for (building in buildings) if (!building.endangered && !building.puppet && !building.themed) fillBuildingLikeVp(context, building, works1, works2)
        for (building in buildings) if (!building.endangered && building.puppet && !building.themed) fillBuildingLikeVp(context, building, works1, works2)
        for (building in buildings) if (building.endangered && !building.themed) fillBuildingLikeVp(context, building, works1, works2)
    }

    /**
     * Vox Populi's ThemeBuilding (V:CvCultureClasses.cpp:1629-1880): the bonuses from the highest; with no kind required, our works come before those
     * offered. A set counts only if as many of our works as there are slots come up to where the search for it stopped, counting from the first work
     * tried. The chosen works go into the slots in order, a slot being skipped when an offered work can not be had
     */
    private fun themeBuildingLikeVp(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>): Boolean {
        val building = entry.building
        val numSlots = entry.slots
        if (numSlots < 2) return false
        val owner = context.civ.civName
        val oursFirst = { works1.filter { context.isOurs(it) } + works2.filter { context.isOurs(it) } +
            works1.filter { !context.isOurs(it) } + works2.filter { !context.isOurs(it) } }

        // The bonuses from the highest, those of one value in order (V:CvCultureClasses.cpp:1648-1657)
        for ((bonusIndex, bonus) in building.themingBonuses.withIndex().sortedByDescending { it.value.bonus }) {
            val worksToConsider: List<GreatWorkInstance> = when {
                bonus.mustBeArt -> ArrayList(works1)
                bonus.mustBeArtifact -> ArrayList(works2)
                bonus.mustBeEqualArtArtifact -> {
                    if (themeEqualArtArtifactLikeVp(context, entry, bonusIndex, numSlots, works1, works2)) return true
                    continue
                }
                else -> oursFirst()
            }
            if (worksToConsider.size < numSlots) continue

            var ownedWorks = 0
            for ((starterIndex, starter) in worksToConsider.withIndex()) {
                if (context.isOurs(starter)) ownedWorks++
                if (bonus.requiresOwner && starter.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

                val chosen = arrayListOf(starter)
                val players = arrayListOf(starter.creatorCiv)
                val eras = arrayListOf(starter.era)
                var extraOwnedWorks = 0
                for (index in starterIndex + 1 until worksToConsider.size) {
                    if (chosen.size >= numSlots) break
                    val work = worksToConsider[index]
                    if (context.isOurs(work)) extraOwnedWorks++
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }
                if (ownedWorks + extraOwnedWorks < numSlots) continue
                if (chosen.size != numSlots || getThemingBonusIndex(context, owner, building, chosen) != bonusIndex) continue

                val toIgnore = ArrayList<GreatWorkInstance>()
                var slot = 0
                for (work in chosen) if (moveWorkIntoSlotLikeVp(context, work, entry, slot, works1, works2, toIgnore)) {
                    slot++
                    toIgnore.add(work)
                }
                // What is left, ours before those of others (whose they are as the moves left it)
                for (list in listOf(works1, works2)) {
                    val left = list.filter { work -> chosen.none { it === work } }
                    list.clear()
                    list.addAll(left.filter { context.isOurs(it) } + left.filter { !context.isOurs(it) })
                }
                return true
            }
        }
        return false
    }

    /**
     * Vox Populi's ThemeEqualArtArtifact (V:CvCultureClasses.cpp:1883-2087): no looking to other civilizations for a missing piece; a set counts only
     * with enough of our works up to where its search stopped. As in the source, the count for the art restarts at 0 and adds the starting artifact
     * for every piece of art tried, and the count of artifacts goes on from there.
     */
    private fun themeEqualArtArtifactLikeVp(context: Context, entry: EmpireBuilding, bonusIndex: Int, numSlots: Int,
                                            works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>): Boolean {
        val worksInHalf = numSlots / 2
        if (worksInHalf % 2 != 0 || works1.size < worksInHalf || works2.size < worksInHalf) return false
        val building = entry.building
        if (building.greatWorkSlotTypes.size < 2) return false
        val bonus = building.themingBonuses[bonusIndex]
        val owner = context.civ.civName

        var ownedWorks = 0
        val artifacts = ArrayList(works2)
        val arts = ArrayList(works1)
        for ((starterIndex, starter) in artifacts.withIndex()) {
            if (context.isOurs(starter)) ownedWorks++
            if (bonus.requiresOwner && starter.creatorCiv != owner) continue
            if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

            val artifactsChosen = arrayListOf(starter)
            val artifactPlayers = arrayListOf(starter.creatorCiv)
            val artifactEras = arrayListOf(starter.era)
            var extraOwnedWorks = 0
            for (index in starterIndex + 1 until artifacts.size) {
                if (artifactsChosen.size >= worksInHalf) break
                val work = artifacts[index]
                if (context.isOurs(work)) extraOwnedWorks++
                if (isValidForThemingBonus(bonus, work.era, artifactEras, work.creatorCiv, artifactPlayers, owner)) {
                    artifactsChosen.add(work)
                    artifactPlayers.add(work.creatorCiv)
                    artifactEras.add(work.era)
                }
            }
            if (ownedWorks + extraOwnedWorks < worksInHalf) continue
            if (artifactsChosen.size != worksInHalf) continue

            ownedWorks = 0
            var chosen = ArrayList<GreatWorkInstance>()
            for ((artIndex, art) in arts.withIndex()) {
                if (chosen.size >= numSlots) break
                if (context.isOurs(starter)) ownedWorks++
                if (bonus.requiresOwner && art.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && art.creatorCiv == owner) continue
                if (bonus.sameEra && art.era != artifactEras[0]) continue
                if ((bonus.uniqueEras || bonus.consecutiveEras) && art.era in artifactEras) continue

                chosen = ArrayList(artifactsChosen)
                val players = ArrayList(artifactPlayers)
                val eras = ArrayList(artifactEras)
                chosen.add(art)
                players.add(art.creatorCiv)
                eras.add(art.era)
                extraOwnedWorks = 0
                for (index in artIndex + 1 until arts.size) {
                    if (chosen.size >= numSlots) break
                    val work = arts[index]
                    if (context.isOurs(work)) extraOwnedWorks++
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }
                if (ownedWorks + extraOwnedWorks < worksInHalf) continue
                if (chosen.size != numSlots || getThemingBonusIndex(context, owner, building, chosen) != bonusIndex) continue

                val toIgnore = ArrayList<GreatWorkInstance>()
                var slot = 0
                for (work in chosen) for (list in listOf(works1, works2)) {
                    if (list.none { it === work }) continue
                    if (moveWorkIntoSlotLikeVp(context, work, entry, slot, works1, works2, toIgnore)) {
                        slot++
                        toIgnore.add(work)
                    }
                }
                works1.removeAll { work -> chosen.any { it === work } }
                works2.removeAll { work -> chosen.any { it === work } }
                return true
            }
        }
        return false
    }

    /** Vox Populi's FillBuilding (V:CvCultureClasses.cpp:2215-2228): any of our works, in the order of the lists; the works of others are left out */
    private fun fillBuildingLikeVp(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        if (entry.slots < 1) return
        val chosen = (works1 + works2).filter { context.isOurs(it) }.take(entry.slots)
        for ((slotIndex, work) in chosen.withIndex()) moveWorkIntoSlotLikeVp(context, work, entry, slotIndex, works1, works2, emptyList())
        works1.removeAll { work -> chosen.any { it === work } }
        works2.removeAll { work -> chosen.any { it === work } }
    }

    /**
     * Vox Populi's MoveWorkIntoSlot (V:CvCultureClasses.cpp:2270-2490): one of our works moves as in [moveWorkIntoSlot]. A work another civilization
     * offered is had for the first of our works of its list (art and writing from the first list, artifacts from the second) not in [toIgnore], which
     * becomes the one we offer: the two are swapped, then the work we got moves into the slot and its old owner is told. False only when we have no
     * work to give. As in the source, the lists take the swap as done even if [swapGreatWorksLikeVp] refused it
     */
    private fun moveWorkIntoSlotLikeVp(context: Context, work: GreatWorkInstance, entry: EmpireBuilding, slotIndex: Int,
                                       works1: List<GreatWorkInstance>, works2: List<GreatWorkInstance>, toIgnore: List<GreatWorkInstance>): Boolean {
        val holder = context.heldBy[work]
        if (holder == null) {
            moveWorkIntoSlot(context, work, entry, slotIndex)
            return true
        }
        val type = context.typeOf(work) ?: return false
        val giveFrom = if (type == GreatWorkType.Artifact) works2 else works1
        val toGive = giveFrom.firstOrNull { candidate -> context.isOurs(candidate) && toIgnore.none { it === candidate } } ?: return false
        setSwappable(context.civ, type, toGive.id)
        swapGreatWorksLikeVp(context, context.civ, toGive, holder, work)
        context.heldBy.remove(work)
        context.heldBy[toGive] = holder
        moveWorkIntoSlot(context, work, entry, slotIndex)
        holder.addNotification("[${context.civ.civName}] has swapped your Great Work [${work.work}] for the Great Work [${toGive.work}]",
            NotificationCategory.General, "StatIcons/Culture")
        return true
    }

    /**
     * Vox Populi's CvGameCulture::SwapGreatWorks (V:CvCultureClasses.cpp:540-775): not between civilizations at war; both works must be of one kind and be
     * what their owners marked as swappable. Works are exchanged slot for slot; [first] then offers nothing and [second] offers the work it got.
     */
    private fun swapGreatWorksLikeVp(context: Context, first: Civilization, firstWork: GreatWorkInstance, second: Civilization, secondWork: GreatWorkInstance) {
        if (!first.isAlive() || !second.isAlive() || first.isAtWarWith(second)) return
        val type = context.typeOf(firstWork) ?: return
        if (type != context.typeOf(secondWork)) return
        if (getSwappable(first, type) != firstWork.id || getSwappable(second, type) != secondWork.id) return
        val firstSlot = findSlot(first, firstWork) ?: return
        val secondSlot = findSlot(second, secondWork) ?: return
        firstSlot.city.greatWorks.place(secondWork, firstSlot)
        secondSlot.city.greatWorks.place(firstWork, secondSlot)
        setSwappable(first, type, "")
        setSwappable(second, type, firstWork.id)
        context.touchedCities.add(firstSlot.city)
        secondSlot.city.cityStats.update()
    }

    /** CvPlayerCulture::ThemeBuilding: fills [entry] with works that make one of its theming bonuses, trying the bonuses in order */
    private fun themeBuilding(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>,
                              considerOtherPlayers: Boolean): Boolean {
        val building = entry.building
        val numSlots = entry.slots
        if (numSlots < 2) return false
        val owner = context.civ.civName

        for ((bonusIndex, bonus) in building.themingBonuses.withIndex()) {
            val worksToConsider: List<GreatWorkInstance> = when {
                bonus.mustBeArt -> ArrayList(works1)
                bonus.mustBeArtifact -> ArrayList(works2)
                bonus.mustBeEqualArtArtifact -> {
                    if (themeEqualArtArtifact(context, entry, bonusIndex, numSlots, works1, works2)) return true
                    // The DLL goes on with the list of the previous bonus (empty for the first). Every building with this bonus in BNW (the Louvre)
                    // has no other, so that list is always empty there
                    continue
                }
                else -> works1 + works2
            }
            if (worksToConsider.size < numSlots) continue

            for ((starterIndex, starter) in worksToConsider.withIndex()) {
                if (bonus.requiresOwner && starter.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

                val chosen = arrayListOf(starter)
                val players = arrayListOf(starter.creatorCiv)
                val eras = arrayListOf(starter.era)
                for (index in starterIndex + 1 until worksToConsider.size) {
                    if (chosen.size >= numSlots) break
                    val work = worksToConsider[index]
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }

                var themedProperly = false
                if (chosen.size == numSlots && getThemingBonusIndex(context, owner, building, chosen) == bonusIndex)
                    themedProperly = true
                else if (considerOtherPlayers && chosen.size == numSlots - 1 && building.greatWorkSlotTypes[0] != GreatWorkSlotType.Music) {
                    val artSlots = building.greatWorkSlotTypes[0] == GreatWorkSlotType.ArtArtifact
                    // What we take from another civilization and what we give for it
                    val wantedType = when {
                        !artSlots -> GreatWorkType.Literature
                        bonus.mustBeArt -> GreatWorkType.Art
                        bonus.mustBeArtifact -> GreatWorkType.Artifact
                        else -> null
                    }
                    val giveFrom = if (wantedType == GreatWorkType.Artifact) works2 else works1
                    if (wantedType != null)
                        themedProperly = acquireLastWork(context, building, bonusIndex, bonus, wantedType, chosen, eras, players, giveFrom)
                }

                if (themedProperly) {
                    // The works are put in the building in the order they were chosen; one that was just acquired is not among those
                    // considered and stays where it arrived (as in the DLL)
                    for ((slotIndex, work) in chosen.withIndex())
                        if (worksToConsider.any { it === work }) moveWorkIntoSlot(context, work, entry, slotIndex)
                    works1.removeAll { work -> chosen.any { it === work } }
                    works2.removeAll { work -> chosen.any { it === work } }
                    return true
                }
            }
        }
        return false
    }

    /**
     * CvPlayerCulture::ThemeEqualArtArtifact: half the slots artifacts, half art. Like the DLL this looks to other civilizations for a last piece of art
     * whether or not the pass considers other players.
     */
    private fun themeEqualArtArtifact(context: Context, entry: EmpireBuilding, bonusIndex: Int, numSlots: Int,
                                      works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>): Boolean {
        val worksInHalf = numSlots / 2
        if (worksInHalf % 2 != 0 || works1.size < worksInHalf || works2.size < worksInHalf) return false
        val building = entry.building
        if (building.greatWorkSlotTypes.size < 2) return false
        val bonus = building.themingBonuses[bonusIndex]
        val owner = context.civ.civName

        val artifactsSnapshot = ArrayList(works2)
        for ((starterIndex, starter) in artifactsSnapshot.withIndex()) {
            if (bonus.requiresOwner && starter.creatorCiv != owner) continue
            if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

            val artifactsChosen = arrayListOf(starter)
            val artifactPlayers = arrayListOf(starter.creatorCiv)
            val artifactEras = arrayListOf(starter.era)
            for (index in starterIndex + 1 until artifactsSnapshot.size) {
                if (artifactsChosen.size >= worksInHalf) break
                val work = artifactsSnapshot[index]
                if (isValidForThemingBonus(bonus, work.era, artifactEras, work.creatorCiv, artifactPlayers, owner)) {
                    artifactsChosen.add(work)
                    artifactPlayers.add(work.creatorCiv)
                    artifactEras.add(work.era)
                }
            }
            if (artifactsChosen.size != worksInHalf) continue

            // Now art for the other half
            var chosen = ArrayList<GreatWorkInstance>()
            var artIndex = 0
            while (artIndex < works1.size && chosen.size < numSlots) {
                val art = works1[artIndex]
                artIndex++
                if (bonus.requiresOwner && art.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && art.creatorCiv == owner) continue
                if (bonus.sameEra && art.era != artifactEras[0]) continue
                if ((bonus.uniqueEras || bonus.consecutiveEras) && art.era in artifactEras) continue

                chosen = ArrayList(artifactsChosen)
                val players = ArrayList(artifactPlayers)
                val eras = ArrayList(artifactEras)
                chosen.add(art)
                players.add(art.creatorCiv)
                eras.add(art.era)
                for (index in artIndex until works1.size) {
                    if (chosen.size >= numSlots) break
                    val work = works1[index]
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }

                var themedProperly = false
                if (chosen.size == numSlots && getThemingBonusIndex(context, owner, building, chosen) == bonusIndex)
                    themedProperly = true
                else if (chosen.size == numSlots - 1)
                    themedProperly = acquireLastWork(context, building, bonusIndex, bonus, GreatWorkType.Art, chosen, eras, players, works1)

                if (themedProperly) {
                    for ((slotIndex, work) in chosen.withIndex()) {
                        works1.firstOrNull { it === work }?.let { moveWorkIntoSlot(context, it, entry, slotIndex) }
                        works2.firstOrNull { it === work }?.let { moveWorkIntoSlot(context, it, entry, slotIndex) }
                    }
                    works1.removeAll { work -> chosen.any { it === work } }
                    works2.removeAll { work -> chosen.any { it === work } }
                    return true
                }
            }
        }
        return false
    }

    /**
     * The "one work short, let's look to other players" part of ThemeBuilding and ThemeEqualArtArtifact: each other civilization in turn offers
     * the work of [wantedType] it marked as swappable, which we take for the first of [giveFrom] not [chosen]. On success the work we gave
     * is replaced in [giveFrom] by the one we got, and that is added to [chosen].
     */
    private fun acquireLastWork(context: Context, building: Building, bonusIndex: Int, bonus: ThemingBonus, wantedType: GreatWorkType,
                                chosen: ArrayList<GreatWorkInstance>, eras: List<String>, players: List<String>,
                                giveFrom: ArrayList<GreatWorkInstance>): Boolean {
        val civ = context.civ
        for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other)) continue
            val toGive = giveFrom.firstOrNull { work -> chosen.none { it === work } } ?: continue
            if (other.isHuman()) {
                if (proposeSwapToHuman(context, other, building, bonusIndex, bonus, wantedType, chosen, eras, players, toGive)) return false
                continue
            }
            val wantedId = getSwappable(other, wantedType)
            if (wantedId.isEmpty()) continue
            val wantedSlot = GreatWorkFunctions.findTradeSlot(other, wantedId) ?: continue
            val wanted = wantedSlot.getWork() ?: continue
            if (!isValidForThemingBonus(bonus, wanted.era, eras, wanted.creatorCiv, players, civ.civName)) continue
            chosen.add(wanted) // The DLL keeps it in the list even if the bonus turns out wrong, so later civilizations can not complete the set
            if (getThemingBonusIndex(context, civ.civName, building, chosen) != bonusIndex) continue
            val creator = civ.gameInfo.civilizations.firstOrNull { it.civName == wanted.creatorCiv }
            if (!swapGreatWorks(context, civ, toGive, creator, wanted)) continue
            val index = giveFrom.indexOfFirst { it === toGive }
            giveFrom[index] = wanted
            creator!!.addNotification("[${civ.civName}] has swapped your Great Work [${wanted.work}] for the Great Work [${toGive.work}]",
                NotificationCategory.General, "StatIcons/Culture")
            return true
        }
        return false
    }

    /**
     * Where the DLL would take the work a human marked as swappable: the AI asks for the first work of [wantedType] of the human that completes
     * the set, offering [toGive], which must be the work it marked itself. Not while a request of ours is pending or after a recent refusal.
     * @return whether a request was made
     */
    private fun proposeSwapToHuman(context: Context, human: Civilization, building: Building, bonusIndex: Int, bonus: ThemingBonus,
                                   wantedType: GreatWorkType, chosen: List<GreatWorkInstance>, eras: List<String>, players: List<String>,
                                   toGive: GreatWorkInstance): Boolean {
        val civ = context.civ
        val toGiveType = context.typeOf(toGive) ?: return false
        if (getSwappable(civ, toGiveType) != toGive.id) return false
        if (human.tradeRequests.any { it.requestingCiv == civ.civID }) return false
        if (civ.getDiplomacyManager(human)?.hasFlag(DiplomacyFlags.DeclinedGreatWorkExchange) != false) return false
        for (city in human.cities) for (slot in city.greatWorks.getSlots()) {
            val wanted = slot.getWork() ?: continue
            if (context.typeOf(wanted) != wantedType || wanted.creatorCiv != human.civName) continue
            if (!isValidForThemingBonus(bonus, wanted.era, eras, wanted.creatorCiv, players, civ.civName)) continue
            if (getThemingBonusIndex(context, civ.civName, building, chosen + wanted) != bonusIndex) continue
            val trade = Trade()
            trade.ourOffers.add(TradeOffer(toGive.id, TradeOfferType.GreatWork, speed = civ.gameInfo.speed))
            trade.theirOffers.add(TradeOffer(wanted.id, TradeOfferType.GreatWork, speed = civ.gameInfo.speed))
            if (!TradeEvaluation().isTradeValid(trade, civ, human)) return false
            human.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
            return true
        }
        return false
    }

    /** CvPlayerCulture::FillBuilding: any works, in the order of the lists */
    private fun fillBuilding(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        if (entry.slots < 1) return
        val worksToConsider = works1 + works2
        val chosen = worksToConsider.take(entry.slots)
        for ((slotIndex, work) in chosen.withIndex()) moveWorkIntoSlot(context, work, entry, slotIndex)
        works1.removeAll { work -> chosen.any { it === work } }
        works2.removeAll { work -> chosen.any { it === work } }
    }

    /** CvPlayerCulture::MoveWorkIntoSlot: [work] goes to the slot, what was there goes where [work] was */
    private fun moveWorkIntoSlot(context: Context, work: GreatWorkInstance, entry: EmpireBuilding, slotIndex: Int) {
        val from = findSlot(context.civ, work) ?: return
        val to = GreatWorkSlot(entry.city, entry.building, slotIndex)
        if (from == to) return
        val other = to.getWork()
        from.city.greatWorks.take(from)
        to.city.greatWorks.take(to)
        to.city.greatWorks.place(work, to)
        if (other != null) from.city.greatWorks.place(other, from)
        context.touchedCities.add(from.city)
        context.touchedCities.add(to.city)
    }

    private fun findSlot(civ: Civilization, work: GreatWorkInstance): GreatWorkSlot? {
        for (city in civ.cities) for (slot in city.greatWorks.getSlots()) if (slot.getWork() === work) return slot
        return null
    }

    /** Where [civ] keeps the work with this [id], none for an empty one */
    private fun findSlotById(civ: Civilization, id: String): GreatWorkSlot? {
        if (id.isEmpty()) return null
        for (city in civ.cities) for (slot in city.greatWorks.getSlots()) if (slot.getWork()?.id == id) return slot
        return null
    }

    /**
     * CvGameCulture::SwapGreatWorks: both works must be of one kind and be what their owners marked as swappable. The marking of [first] is used up
     * before the one of [second] is checked, as in the DLL. Works are exchanged slot for slot.
     */
    private fun swapGreatWorks(context: Context, first: Civilization, firstWork: GreatWorkInstance, second: Civilization?, secondWork: GreatWorkInstance): Boolean {
        if (second == null || !first.isAlive() || !second.isAlive()) return false
        val type = context.typeOf(firstWork) ?: return false
        if (type != context.typeOf(secondWork)) return false
        if (getSwappable(first, type) != firstWork.id) return false
        setSwappable(first, type, "")
        if (getSwappable(second, type) != secondWork.id) return false
        setSwappable(second, type, "")
        val firstSlot = findSlot(first, firstWork) ?: return false
        val secondSlot = findSlot(second, secondWork) ?: return false
        firstSlot.city.greatWorks.place(secondWork, firstSlot)
        secondSlot.city.greatWorks.place(firstWork, secondSlot)
        context.touchedCities.add(firstSlot.city)
        secondSlot.city.cityStats.update()
        return true
    }

    /** CvDiplomacyAI::IsPlayerValid: another living major civilization with cities that we have met */
    private fun isPlayerValid(civ: Civilization, other: Civilization): Boolean =
        other != civ && other.isMajorCiv && other.isAlive() && other.cities.isNotEmpty() && civ.knows(other)

    private fun getSwappable(civ: Civilization, type: GreatWorkType): String = when (type) {
        GreatWorkType.Literature -> civ.culture.swappableWriting
        GreatWorkType.Art -> civ.culture.swappableArt
        GreatWorkType.Artifact -> civ.culture.swappableArtifact
        GreatWorkType.Music -> civ.culture.swappableMusic
    }

    private fun setSwappable(civ: Civilization, type: GreatWorkType, id: String) {
        when (type) {
            GreatWorkType.Literature -> civ.culture.swappableWriting = id
            GreatWorkType.Art -> civ.culture.swappableArt = id
            GreatWorkType.Artifact -> civ.culture.swappableArtifact = id
            GreatWorkType.Music -> civ.culture.swappableMusic = id
        }
    }

    /** CultureHelpers::IsValidForThemingBonus: whether a work can join a set with [eras] and [players] seen so far */
    private fun isValidForThemingBonus(bonus: ThemingBonus, era: String, eras: List<String>, player: String, players: List<String>, owner: String): Boolean {
        if (bonus.sameEra && era != eras[0]) return false
        // IsUniqueEras is also true for ConsecutiveEras (V:CvBuildingClasses.h:55)
        if ((bonus.uniqueEras || bonus.consecutiveEras) && era in eras) return false
        if (bonus.requiresOwner && player != owner) return false
        if (bonus.requiresAnyButOwner && player == owner) return false
        if (bonus.requiresSamePlayer && player != players[0]) return false
        if (bonus.requiresUniquePlayers && player in players) return false
        return true
    }

    /** CultureHelpers::GetThemingBonusIndex: the first theming bonus of [building] that [works] make, -1 if none or not one work per slot */
    private fun getThemingBonusIndex(context: Context, owner: String, building: Building, works: List<GreatWorkInstance>): Int {
        if (works.size != building.greatWorkSlotTypes.size) return -1
        val types = works.map { context.typeOf(it) ?: return -1 }
        return building.themingBonuses.indexOfFirst { it.isSatisfiedBy(works, types, owner, context.ruleset) }
    }
}

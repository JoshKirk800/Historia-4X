package com.unciv.logic.automation.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.ThemingBonus
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayerCulture::DoSwapGreatWorks / MoveWorks / ThemeBuilding / FillBuilding and CvGameCulture::SwapGreatWorks on the BNW buildings */
@RunWith(GdxTestRunner::class)
class GreatWorkSwapAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var third: Civilization
    private lateinit var city: City
    private lateinit var secondCity: City

    /** The Museum, the Hermitage and the Amphitheater of the original (Brave New World's database): the ruleset has Vox Populi's buildings instead (no building
     *  of these names, and none with these theming bonuses), the swapping works on any building with slots and theming bonuses, so the tests add these three */
    private fun building(name: String): Building = game.ruleset.buildings.getOrPut(name) { original(name) }

    private fun bonus(name: String, bonus: Int, priority: Int, init: ThemingBonus.() -> Unit) =
        ThemingBonus().apply { this.name = name; this.bonus = bonus; aiPriority = priority; init() }

    private fun original(name: String): Building = Building().apply {
        this.name = name
        ruleset = game.ruleset
        when (name) {
            "Museum" -> {
                uniques.add("Has [2] [Art/Artifact] Great Work slots")
                themingBonuses.addAll(listOf(
                    bonus("Museum of [era] [civ] Art", 2, 2) { sameEra = true; mustBeArt = true; requiresOwner = true },
                    bonus("Museum of [era] [civ] Art", 2, 2) { sameEra = true; mustBeArt = true; requiresAnyButOwner = true },
                    bonus("Museum of [era] [civ] Warfare", 2, 2) { sameEra = true; mustBeArtifact = true; requiresOwner = true },
                    bonus("Museum of [era] [civ] Warfare", 2, 2) { sameEra = true; mustBeArtifact = true; requiresAnyButOwner = true },
                    bonus("Museum of [era] Art", 1, 1) { sameEra = true; mustBeArt = true },
                    bonus("Museum of [era] Warfare", 1, 1) { sameEra = true; mustBeArtifact = true },
                    bonus("[civ] Museum of the [era] Era", 1, 1) { sameEra = true; requiresOwner = true },
                    bonus("World Museum of the [era] Era", 1, 1) { sameEra = true; requiresAnyButOwner = true },
                    bonus("Collection of [civ] Art", 1, 1) { mustBeArt = true; requiresOwner = true },
                    bonus("Collection of [civ] Art", 1, 1) { mustBeArt = true; requiresAnyButOwner = true },
                    bonus("Collection of [civ] Warfare", 1, 1) { mustBeArtifact = true; requiresOwner = true },
                    bonus("Collection of [civ] Warfare", 1, 1) { mustBeArtifact = true; requiresAnyButOwner = true },
                ))
            }
            "Hermitage" -> {
                uniques.add("Has [3] [Art/Artifact] Great Work slots")
                themingBonuses.add(bonus("Museum of World Art", 3, 4) { uniqueEras = true; mustBeArt = true; requiresUniquePlayers = true })
            }
            "Amphitheater" -> uniques.add("Has [1] [Literature] Great Work slots")
            else -> throw IllegalArgumentException(name)
        }
    }

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        civ = game.addCiv()
        other = game.addCiv()
        third = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        secondCity = game.addCity(civ, game.tileMap[0, 5])
        game.addCity(other, game.tileMap[5, 0], replacePalace = true)
        game.addCity(third, game.tileMap[-5, 0], replacePalace = true)
        for (a in listOf(civ, other, third)) for (b in listOf(civ, other, third)) if (a != b) a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    private fun art(era: String, by: Civilization, type: GreatWorkType = GreatWorkType.Art) =
        GreatWorkInstance(game.createGreatWork(type).name, by.civName, era, 1)

    private fun put(where: City, building: Building, slot: Int, work: GreatWorkInstance) =
        where.greatWorks.place(work, GreatWorkSlot(where, building, slot))

    private fun worksIn(where: City, building: Building) = where.greatWorks.getSlots(building).map { it.getWork() }

    @Test
    fun `the building with the higher AI priority is themed first with a whole set`() {
        // Hermitage (priority 4, art of 3 eras and 3 civilizations) before the Museum (priority 2, art of one era by the owner)
        val museum = building("Museum")
        val hermitage = building("Hermitage")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(hermitage)
        val ownAncient = art("Ancient era", civ)
        val ownAncientToo = art("Ancient era", civ)
        val classical = art("Classical era", other)
        val medieval = art("Medieval era", third)
        put(city, museum, 0, ownAncient)
        put(city, museum, 1, classical)
        put(secondCity, hermitage, 0, ownAncientToo)
        put(secondCity, hermitage, 1, medieval)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Starter is the first art in empire order (the Museum's first work), then each that keeps eras and civilizations unique
        assertEquals(listOf(ownAncient, classical, medieval), worksIn(secondCity, hermitage))
        assertEquals(3, secondCity.greatWorks.getThemingBonus(hermitage))
        // The Museum only gets what is left; theming it first (the two Ancient works of the owner) would have left the Hermitage unthemed
        assertEquals(listOf(ownAncientToo, null), worksIn(city, museum))
    }

    @Test
    fun `a theme that needs several works moved at once is completed`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(building("Hermitage"))
        val first = art("Renaissance era", civ)
        val second = art("Renaissance era", civ)
        put(city, museum, 0, art("Ancient era", other))
        put(city, museum, 1, art("Medieval era", third))
        val hermitage = building("Hermitage")
        put(secondCity, hermitage, 0, first)
        put(secondCity, hermitage, 1, second)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Hermitage needs 3 art of distinct eras and civilizations: Ancient(other), Medieval(third), Renaissance(civ) - all three positions change
        assertEquals(3, secondCity.greatWorks.getThemingBonus(hermitage))
        assertEquals(1, worksIn(city, museum).filterNotNull().size)
    }

    @Test
    fun `a damaged city is themed after the undamaged ones`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(museum)
        city.health = 100 // damaged, and first in empire order
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(city, museum, 0, a)
        put(city, museum, 1, b)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertEquals(listOf(a, b), worksIn(secondCity, museum))
        assertEquals(2, secondCity.greatWorks.getThemingBonus(museum))
        assertEquals(listOf(null, null), worksIn(city, museum))
    }

    @Test
    fun `the first unthemed work of each kind is designated as swappable`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(building("Amphitheater"))
        val themedA = art("Ancient era", civ)
        val themedB = art("Ancient era", civ)
        val leftover = art("Medieval era", other)
        val artifact = art("Classical era", other, GreatWorkType.Artifact)
        val writing = art("Classical era", civ, GreatWorkType.Literature)
        put(city, museum, 0, leftover)
        put(city, museum, 1, themedA)
        secondCity.cityConstructions.addBuilding(museum)
        put(secondCity, museum, 0, themedB)
        put(secondCity, museum, 1, artifact)
        put(secondCity, building("Amphitheater"), 0, writing)
        civ.culture.swappableMusic = "kept"

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertEquals(leftover.id, civ.culture.swappableArt)
        assertEquals(artifact.id, civ.culture.swappableArtifact)
        assertEquals(writing.id, civ.culture.swappableWriting)
        assertEquals("music is never designated", "kept", civ.culture.swappableMusic)
        assertEquals(2, city.greatWorks.getThemingBonus(museum))
    }

    private fun setUpSwap(): Triple<Building, GreatWorkInstance, GreatWorkInstance> {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        val ours = art("Ancient era", civ)
        // By a third civilization, so the two works make no theme of the Museum without a swap
        val toGive = art("Medieval era", third)
        put(city, museum, 0, ours)
        put(city, museum, 1, toGive)
        val otherCity = other.cities.first()
        val otherMuseum = building("Museum")
        otherCity.cityConstructions.addBuilding(otherMuseum)
        val wanted = art("Ancient era", other)
        put(otherCity, otherMuseum, 0, wanted)
        return Triple(museum, toGive, wanted)
    }

    @Test
    fun `one work short, the designated works of two AIs are swapped to complete the theme`() {
        val (museum, toGive, wanted) = setUpSwap()
        civ.culture.swappableArt = toGive.id
        other.culture.swappableArt = wanted.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Ancient art of the owner plus Ancient art of another civ: "Museum of [era] Art" (sameEra, mustBeArt), bonus 1
        assertTrue(worksIn(city, museum).any { it === wanted })
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === toGive })
        assertEquals(1, city.greatWorks.getThemingBonus(museum))
        assertEquals("the other's designation is used up", "", other.culture.swappableArt)
    }

    @Test
    fun `no swap unless both works are the designated ones`() {
        val (museum, toGive, wanted) = setUpSwap()
        other.culture.swappableArt = wanted.id
        civ.culture.swappableArt = "" // we did not designate the work we would give

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === wanted })
        assertEquals(0, city.greatWorks.getThemingBonus(museum))

        civ.culture.swappableArt = toGive.id
        other.culture.swappableArt = ""
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === wanted })
    }

    //region Vox Populi
    private fun likeVp() {
        game.ruleset.modOptions.constants.greatWorkThemingLikeVp = true
    }

    /** A building with [slots] art slots and [bonuses], in their order */
    private fun gallery(name: String, vararg bonuses: ThemingBonus, slots: Int = 2): Building = Building().apply {
        this.name = name
        ruleset = game.ruleset
        uniques.add("Has [$slots] [Art/Artifact] Great Work slots")
        themingBonuses.addAll(bonuses)
        game.ruleset.buildings[name] = this
    }

    @Test
    fun `like Vox Populi a city that might fall takes no works but gives its own`() {
        likeVp()
        val museum = building("Museum")
        val hermitage = building("Hermitage")
        city.cityConstructions.addBuilding(hermitage)
        secondCity.cityConstructions.addBuilding(museum)
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(secondCity, museum, 0, a)
        put(secondCity, museum, 1, b)
        // Lost a little, and as much last turn as it has left (CvCity::isInDangerOfFalling): not endangered, but it might fall
        secondCity.health -= 10
        secondCity.damageTakenLastTurn = secondCity.health

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // The pair would theme the Museum where it is; it goes into the Hermitage of the capital instead
        assertEquals(listOf(a, b, null), worksIn(city, hermitage))
        assertEquals(listOf(null, null), worksIn(secondCity, museum))
    }

    @Test
    fun `like Vox Populi the capital is never endangered`() {
        likeVp()
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(museum)
        city.health = 20
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(city, museum, 0, a)
        put(city, museum, 1, b)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertEquals(listOf(a, b), worksIn(city, museum))
        assertEquals(listOf(null, null), worksIn(secondCity, museum))
    }

    @Test
    fun `like Vox Populi another city is endangered past half its health or razed below 3 citizens`() {
        likeVp()
        val museum = building("Museum")
        val thirdCity = game.addCity(civ, game.tileMap[0, -5])
        secondCity.cityConstructions.addBuilding(museum)
        thirdCity.cityConstructions.addBuilding(museum)
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(thirdCity, museum, 0, a)
        put(thirdCity, museum, 1, b)
        val inSecond = { worksIn(secondCity, museum) == listOf(a, b) }

        val max = secondCity.health // a new city has all its health
        secondCity.health = max / 2 - 1 // lost more than half: the Museum of the third city is themed first, in place
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertFalse(inSecond())

        secondCity.health = max / 2 // lost half: not endangered, and first in empire order
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(inSecond())

        secondCity.greatWorks.takeAll(museum)
        put(thirdCity, museum, 0, a)
        put(thirdCity, museum, 1, b)
        secondCity.health = max
        secondCity.isBeingRazed = true
        secondCity.population.setPopulation(2)
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertFalse(inSecond())

        secondCity.population.setPopulation(3)
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(inSecond())
    }

    @Test
    fun `like Vox Populi puppets are themed and filled after the other cities`() {
        likeVp()
        val museum = building("Museum")
        val thirdCity = game.addCity(civ, game.tileMap[0, -5])
        secondCity.cityConstructions.addBuilding(museum)
        thirdCity.cityConstructions.addBuilding(museum)
        secondCity.isPuppet = true
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(secondCity, museum, 0, a)
        put(secondCity, museum, 1, b)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertEquals(listOf(a, b), worksIn(thirdCity, museum))

        // One work alone themes nothing and fills the city before the puppet
        thirdCity.greatWorks.takeAll(museum)
        put(secondCity, museum, 1, a)
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertEquals(listOf(a, null), worksIn(thirdCity, museum))
    }

    @Test
    fun `like Vox Populi the theming bonuses of a building are tried from the highest`() {
        val gallery = gallery("Gallery",
            bonus("Our art", 1, 1) { mustBeArt = true; requiresOwner = true },
            bonus("Their art", 3, 1) { mustBeArt = true; requiresAnyButOwner = true })
        val hermitage = building("Hermitage")
        city.cityConstructions.addBuilding(gallery)
        secondCity.cityConstructions.addBuilding(hermitage)
        val ours = listOf(art("Ancient era", civ), art("Ancient era", civ))
        val theirs = listOf(art("Ancient era", other), art("Ancient era", other))
        put(city, gallery, 0, ours[0])
        put(city, gallery, 1, ours[1])
        put(secondCity, hermitage, 0, theirs[0])
        put(secondCity, hermitage, 1, theirs[1])

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertEquals(ours, worksIn(city, gallery))

        likeVp()
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertEquals(theirs, worksIn(city, gallery))
        assertEquals(3, city.greatWorks.getThemingBonus(gallery))
    }

    @Test
    fun `like Vox Populi theming percentages are added up and truncated once`() {
        val gallery = gallery("Gallery", bonus("Any art", 1, 1) { mustBeArt = true })
        val patron = game.addCiv("[+50]% Theming bonuses [in all cities]", "[+50]% Theming bonuses [in all cities]",
            "[+100]% Theming bonuses [in puppeted cities]")
        val patronCity = game.addCity(patron, game.tileMap[5, 5])
        patronCity.cityConstructions.addBuilding(gallery)
        put(patronCity, gallery, 0, art("Ancient era", patron))
        put(patronCity, gallery, 1, art("Ancient era", patron))

        // One after the other: 1 x 150% = 1, and again 1
        assertEquals(1, patronCity.greatWorks.getThemingBonus(gallery))
        likeVp()
        // 1 x (100 + 50 + 50)% = 2; the puppets' percentage is not for this city
        assertEquals(2, patronCity.greatWorks.getThemingBonus(gallery))
    }

    @Test
    fun `like Vox Populi an offered work completes a set for the first of our works not in it, which the other then offers`() {
        likeVp()
        val (museum, toGive, wanted) = setUpSwap()
        other.culture.swappableArt = wanted.id
        civ.culture.swappableArt = "" // Vox Populi marks the work it gives when it gives it

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // "Museum of [era] Art": our Ancient art and theirs
        assertEquals(wanted, worksIn(city, museum)[1])
        assertEquals(1, city.greatWorks.getThemingBonus(museum))
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === toGive })
        assertEquals("what the other got is what it offers now", toGive.id, other.culture.swappableArt)
        assertEquals("none of our art is left to offer", "", civ.culture.swappableArt)
    }

    @Test
    fun `like Vox Populi nothing is offered by a civilization at war with us`() {
        likeVp()
        val (museum, toGive, wanted) = setUpSwap()
        other.culture.swappableArt = wanted.id
        civ.getDiplomacyManager(other)!!.declareWar()

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertTrue(other.cities.first().greatWorks.getWorks().any { it === wanted })
        assertTrue(worksIn(city, museum).any { it === toGive })
        assertEquals("the first of our works is the one we offer", worksIn(city, museum)[0]!!.id, civ.culture.swappableArt)
    }

    @Test
    fun `like Vox Populi a set of offered works needs as many of our works as slots`() {
        likeVp()
        val gallery = gallery("Gallery", bonus("Their art", 3, 1) { mustBeArt = true; requiresAnyButOwner = true })
        city.cityConstructions.addBuilding(gallery)
        val ours = art("Ancient era", civ)
        put(city, gallery, 0, ours)
        val fromOther = art("Ancient era", other)
        val fromThird = art("Ancient era", third)
        val otherCity = other.cities.first()
        val thirdCity = third.cities.first()
        val otherGallery = building("Museum")
        otherCity.cityConstructions.addBuilding(otherGallery)
        thirdCity.cityConstructions.addBuilding(otherGallery)
        put(otherCity, otherGallery, 0, fromOther)
        put(thirdCity, otherGallery, 0, fromThird)
        other.culture.swappableArt = fromOther.id
        third.culture.swappableArt = fromThird.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Both offers make the set, but we hold one work for two slots: no swap, our work fills the gallery
        assertEquals(listOf(ours, null), worksIn(city, gallery))
        assertEquals(listOf(fromOther, null), worksIn(otherCity, otherGallery))
        assertEquals(listOf(fromThird, null), worksIn(thirdCity, otherGallery))
    }

    @Test
    fun `like Vox Populi the leftovers fill with our works only`() {
        likeVp()
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(museum)
        val ours = art("Medieval era", civ)
        put(secondCity, museum, 1, ours)
        val (otherCity, wanted) = other.cities.first() to art("Ancient era", other)
        otherCity.cityConstructions.addBuilding(museum)
        put(otherCity, museum, 0, wanted)
        other.culture.swappableArt = wanted.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // The capital's Museum comes first: our work moves there, the offer is not swapped in beside it
        assertEquals(listOf(ours, null), worksIn(city, museum))
        assertEquals(listOf(wanted, null), worksIn(otherCity, museum))
    }

    @Test
    fun `like Vox Populi the Louvre counts our works from its first artifact, and leaves a slot when nothing is left to give`() {
        likeVp()
        val louvre = gallery("Louvre", bonus("Their art and artifacts", 8, 5) { mustBeEqualArtArtifact = true; requiresAnyButOwner = true }, slots = 4)
        city.cityConstructions.addBuilding(louvre)
        secondCity.cityConstructions.addBuilding(building("Museum"))
        val artifact1 = art("Ancient era", third, GreatWorkType.Artifact)
        val artifact2 = art("Ancient era", third, GreatWorkType.Artifact)
        val ourArt = art("Ancient era", civ)
        put(city, louvre, 0, ourArt)
        put(secondCity, building("Museum"), 0, artifact1)
        put(secondCity, building("Museum"), 1, artifact2)
        val fromOther = art("Ancient era", other)
        val fromThird = art("Ancient era", third)
        val otherCity = other.cities.first()
        val thirdCity = third.cities.first()
        otherCity.cityConstructions.addBuilding(building("Museum"))
        thirdCity.cityConstructions.addBuilding(building("Museum"))
        put(otherCity, building("Museum"), 0, fromOther)
        put(thirdCity, building("Museum"), 0, fromThird)
        other.culture.swappableArt = fromOther.id
        third.culture.swappableArt = fromThird.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // The art count adds the first artifact (ours) once per piece of art tried: 2 by the second, enough. Our art goes for the first offer;
        // for the second nothing of ours is left, so its slot is skipped
        assertEquals(listOf(artifact1, artifact2, fromOther, null), worksIn(city, louvre))
        assertTrue(otherCity.greatWorks.getWorks().any { it === ourArt })
        assertTrue(thirdCity.greatWorks.getWorks().any { it === fromThird })
    }

    /** Artifacts of a third civilization in our [where]'s Museum, and [offers] made by [other], each in its Museum */
    private fun ownArtifactsAndOffers(where: City, era: List<String>, vararg offers: GreatWorkInstance): List<GreatWorkInstance> {
        val museum = building("Museum")
        where.cityConstructions.addBuilding(museum)
        val artifacts = era.map { art(it, third, GreatWorkType.Artifact) }
        for ((slot, artifact) in artifacts.withIndex()) put(where, museum, slot, artifact)
        val otherCity = other.cities.first()
        otherCity.cityConstructions.addBuilding(museum)
        for ((slot, offer) in offers.withIndex()) put(otherCity, museum, slot, offer)
        return artifacts
    }

    @Test
    fun `like Vox Populi with no particular kind our works are tried before those offered`() {
        likeVp()
        val gallery = gallery("Gallery", bonus("Their era", 3, 5) { sameEra = true; requiresAnyButOwner = true }, slots = 3)
        city.cityConstructions.addBuilding(gallery)
        val ourArt = art("Medieval era", civ)
        val ancientArtifact = art("Ancient era", third, GreatWorkType.Artifact)
        put(city, gallery, 0, ancientArtifact)
        put(city, gallery, 1, ourArt)
        val offeredArt = art("Medieval era", other)
        val offeredArtifact = art("Ancient era", other, GreatWorkType.Artifact)
        val (medieval1, medieval2) = ownArtifactsAndOffers(secondCity, listOf("Medieval era", "Medieval era"), offeredArt, offeredArtifact)
        other.culture.swappableArt = offeredArt.id
        other.culture.swappableArtifact = offeredArtifact.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Our Medieval artifacts start the set and the offered art completes it, for our art; trying the offer first would put it first
        assertEquals(listOf(medieval1, medieval2, offeredArt), worksIn(city, gallery))
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === ourArt })
    }

    @Test
    fun `like Vox Populi a slot stays for the next work when an offered one can not be had`() {
        likeVp()
        val gallery = gallery("Gallery", bonus("Their era", 3, 5) { sameEra = true; requiresAnyButOwner = true }, slots = 3)
        city.cityConstructions.addBuilding(gallery)
        val ancientArtifact = art("Ancient era", third, GreatWorkType.Artifact)
        put(city, gallery, 0, ancientArtifact)
        val offeredArt = art("Ancient era", other)
        val offeredArtifact = art("Ancient era", other, GreatWorkType.Artifact)
        val (medieval1, _) = ownArtifactsAndOffers(secondCity, listOf("Medieval era", "Medieval era"), offeredArt, offeredArtifact)
        other.culture.swappableArt = offeredArt.id
        other.culture.swappableArtifact = offeredArtifact.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // We have no art to give for the offered art; the offered artifact, had for our first Medieval artifact, takes the slot it left
        assertEquals(listOf(ancientArtifact, offeredArtifact, null), worksIn(city, gallery))
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === medieval1 })
    }

    @Test
    fun `like Vox Populi the Louvre counts our works for the art up to where the search stopped`() {
        likeVp()
        val louvre = gallery("Louvre", bonus("Art and artifacts", 8, 5) { mustBeEqualArtArtifact = true }, slots = 4)
        city.cityConstructions.addBuilding(louvre)
        val ourArt = art("Ancient era", civ)
        put(city, louvre, 0, ourArt)
        val fromOther = art("Ancient era", other)
        ownArtifactsAndOffers(secondCity, listOf("Ancient era", "Ancient era"), fromOther)
        other.culture.swappableArt = fromOther.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // With our art first the set takes the offer, but only the first artifact is counted for it, 1 of 2: no theme. Our artifacts then theme
        // the Museum, and our art alone fills the Louvre
        assertEquals(listOf(ourArt, null, null, null), worksIn(city, louvre))
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === fromOther })
    }
    //endregion
}

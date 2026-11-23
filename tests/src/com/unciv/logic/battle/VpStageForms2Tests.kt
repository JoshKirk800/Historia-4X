package com.unciv.logic.battle

import com.unciv.logic.automation.unit.tactical.PillageRules
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  The forms of the Vox Populi promotion and unit columns without a unique so far (StageForms2), each behind its ModConstants switch:
 *  - UnitPromotions.ExtraFlankPower: CvUnit::GetFlankPower, CvPlot::GetNumEnemyUnitsAdjacent (bConsiderFlanking), GetEffectiveFlankingBonus
 *  - UnitPromotions.BarbarianCombatBonus: CvUnit::GetGenericMeleeStrengthModifier, GetMaxRangedCombatStrength (the existing `vs [Barbarian] units`)
 *  - UnitPromotions_Terrains/_Features IgnoreTerrainCostIn, HalfMove, ExtraMove: CvUnitMovement::GetCostsForMove,
 *    GetMovementCostMultiplierFromPromotions, GetMovementCostAdderFromPromotions
 *  - UnitPromotions.GainsXPFromScouting: CvPlot::setRevealed, CvUnit::setXY, CvUnit::changeExperienceTimes100
 *  - UnitPromotions.XPFromPillaging: CvUnit::pillage, CvUnit::shouldPillage
 *  - UnitPromotions.LostOnGifting: CvUnit::convert (bIsGift)
 *  - Units.MinorCivGift / NoMinorCivGift: CvGame::GetRandomUniqueUnitType, CvPlayer::canTrainUnit
 */
@RunWith(BaseTestRunner::class)
class VpStageForms2Tests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun setExperiencePercent(game: TestGame, percent: Int) {
        game.gameInfo.speed.javaClass.getMethod("setExperiencePercent", Int::class.javaPrimitiveType).invoke(game.gameInfo.speed, percent)
    }

    private fun newGame(radius: Int = 4): TestGame {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(radius)
        setExperiencePercent(game, 100) // the speed object is shared between tests, and the speed test below changes it
        return game
    }

    /** A Sword-type unit of 2 movement and strength 8 (a Warrior) with the given uniques, which are therefore on the unit from its creation */
    private fun unitWith(game: TestGame, civ: Civilization, tile: Tile, vararg uniques: String): MapUnit {
        val base = game.createBaseUnit("Sword", *uniques)
        base.movement = 2
        base.strength = 8
        return game.addUnit(base.name, civ, tile)
    }

    // ---------------------------------------------------------------- ExtraFlankPower

    private class Flank(val game: TestGame, val attacker: MapUnitCombatant, val defender: MapUnitCombatant, val helperTile: Tile, val ours: Civilization)

    /** A Warrior (strength 8) at (0,0) attacks a Warrior at (1,0); (2,0) is next to the defender and not to the attacker */
    private fun flankSetup(flag: Boolean): Flank {
        val game = newGame()
        setFlag(game, "flankPowerLikeVp", flag)
        val ours = game.addCiv(isPlayer = true)
        val theirs = game.addCiv(isPlayer = true)
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.getDiplomacyManager(theirs)!!.declareWar()
        val attacker = MapUnitCombatant(game.addUnit("Warrior", ours, game.getTile(0, 0)))
        val defender = MapUnitCombatant(game.addUnit("Warrior", theirs, game.getTile(1, 0)))
        val helperTile = game.getTile(2, 0)
        assertTrue(game.getTile(1, 0).neighbors.contains(helperTile))
        return Flank(game, attacker, defender, helperTile, ours)
    }

    private fun Flank.strength() = BattleDamage.getAttackingStrength(attacker, defender, attacker.getTile())

    @Test
    fun `a plain flank supporter counts as one unit with the switch on`() {
        val s = flankSetup(true)
        // GetNumEnemyUnitsAdjacent: the flanking bonus is BONUS_PER_ADJACENT_FRIEND 10 for each counted unit; one plain helper counts 1: 8 x (1 + 10%) = 8.8
        unitWith(s.game, s.ours, s.helperTile)
        assertEquals(8.8f, s.strength(), 0.001f)
    }

    @Test
    fun `extra flanking power from a promotion adds to the count`() {
        val s = flankSetup(true)
        val helper = unitWith(s.game, s.ours, s.helperTile)
        val promotion = s.game.createUnitPromotion("[1] extra flanking power")
        helper.promotions.addPromotion(promotion.name, isFree = true)
        // CvUnit::GetFlankPower = 1 + 1 = 2 units: 8 x (1 + 20%) = 9.6
        assertEquals(9.6f, s.strength(), 0.001f)
        val stronger = s.game.createUnitPromotion("[2] extra flanking power")
        helper.promotions.addPromotion(stronger.name, isFree = true)
        // 1 + 1 + 2 = 4 units: 8 x (1 + 40%) = 11.2
        assertEquals(11.2f, s.strength(), 0.001f)
    }

    @Test
    fun `extra flanking power is not read with the switch off`() {
        val s = flankSetup(false)
        unitWith(s.game, s.ours, s.helperTile, "[3] extra flanking power")
        assertEquals(8.8f, s.strength(), 0.001f)
    }

    // ---------------------------------------------------------------- BarbarianCombatBonus

    @Test
    fun `barbarian combat bonus of a promotion is a strength bonus against barbarian units`() {
        val game = newGame()
        val ours = game.addCiv(isPlayer = true)
        val theirs = game.addCiv(isPlayer = true)
        val barbarian = game.addBarbarianCiv()
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.getDiplomacyManager(theirs)!!.declareWar()
        val plain = MapUnitCombatant(unitWith(game, ours, game.getTile(0, 0)))
        val brute = MapUnitCombatant(unitWith(game, ours, game.getTile(0, 1), "[+25]% Strength <vs [Barbarian] units>"))
        val barbarianUnit = MapUnitCombatant(game.addUnit("Brute", barbarian, game.getTile(1, 0)))
        val otherUnit = MapUnitCombatant(game.addUnit("Warrior", theirs, game.getTile(-1, 0)))
        // CvUnit::GetGenericMeleeStrengthModifier adds GetBarbarianCombatBonus when the other unit is a barbarian: 8 x 25% = 2 more strength
        // (the flanking of the neighbouring units is left out, it is not what is measured)
        val difference = BattleDamage.getAttackingStrength(brute, barbarianUnit, brute.getTile(), ignoreFlanking = true) -
            BattleDamage.getAttackingStrength(plain, barbarianUnit, plain.getTile(), ignoreFlanking = true)
        assertEquals(2f, difference, 0.001f)
        // and none against anyone else
        assertEquals(BattleDamage.getAttackingStrength(plain, otherUnit, plain.getTile(), ignoreFlanking = true),
            BattleDamage.getAttackingStrength(brute, otherUnit, brute.getTile(), ignoreFlanking = true), 0.001f)
    }

    // ---------------------------------------------------------------- terrain movement promotions

    private class Movement(val game: TestGame, val civ: Civilization, val from: Tile, val to: Tile)

    private fun movementSetup(flag: Boolean, terrain: String = "Plains", vararg features: String): Movement {
        val game = newGame()
        setFlag(game, "terrainMovementPromotionsLikeVp", flag)
        val civ = game.addCiv(isPlayer = true)
        game.setTileTerrain(game.getTile(0, 0).position, "Plains")
        game.setTileTerrainAndFeatures(game.getTile(1, 0).position, terrain, *features)
        return Movement(game, civ, game.getTile(0, 0), game.getTile(1, 0))
    }

    private fun Movement.cost(vararg uniques: String): Float {
        val unit = unitWith(game, civ, from, *uniques)
        val cost = MovementCost.getMovementCostBetweenAdjacentTiles(unit, from, to, false)
        unit.destroy() // the next call puts another military unit on the tile
        return cost
    }

    @Test
    fun `ignoring the terrain cost of a tile makes it cost 1`() {
        val m = movementSetup(true, "Plains", "Hill")
        assertEquals(2f, m.cost(), 0f) // a Hill costs 2
        assertEquals(1f, m.cost("Ignores terrain cost in [Hill] tiles"), 0f)
        // the Amphibious promotion: a Marsh costs 3
        val marsh = movementSetup(true, "Plains", "Marsh")
        assertEquals(3f, marsh.cost(), 0f)
        assertEquals(1f, marsh.cost("Ignores terrain cost in [Marsh] tiles"), 0f)
        assertEquals(3f, marsh.cost("Ignores terrain cost in [Forest] tiles"), 0f)
    }

    @Test
    fun `half movement doubles the cost and extra movement cost adds to it after the other changes`() {
        val m = movementSetup(true, "Plains", "Hill")
        assertEquals(4f, m.cost("Half movement in [Hill] tiles"), 0f)    // 2 x 2
        assertEquals(3f, m.cost("[1] extra movement cost in [Hill] tiles"), 0f) // 2 + 1
        // GetCostsForMove: ignored cost 1, multiplied by 2, plus 1
        assertEquals(3f, m.cost("Ignores terrain cost in [Hill] tiles", "Half movement in [Hill] tiles", "[1] extra movement cost in [Hill] tiles"), 0f)
        // the double movement of a unit that also ignores the cost is 1 x 1/2
        assertEquals(0.5f, m.cost("Ignores terrain cost in [Hill] tiles", "Double movement in [Hill]"), 0f)
        assertEquals(1f, m.cost("Double movement in [Hill]"), 0f)
    }

    @Test
    fun `only the most specific kind of tile counts for the extra movement cost`() {
        // GetMovementCostAdderFromPromotions: mountain, else hill, else terrain, else feature. A forest on a hill costs the forest's 2 plus
        // HILLS_EXTRA_MOVEMENT 1 (the BNW ModOptions featureOnHillsExtraMovement), 3, then the one extra cost: the hill's, not the forest's as well
        val hillForest = movementSetup(true, "Plains", "Hill", "Forest")
        assertEquals(3f, hillForest.cost(), 0f)
        assertEquals(4f, hillForest.cost("[1] extra movement cost in [Hill] tiles", "[1] extra movement cost in [Forest] tiles"), 0f)
        assertEquals(4f, hillForest.cost("[1] extra movement cost in [Forest] tiles"), 0f)
        // a forest on plains: the base terrain is more specific than the feature
        val forest = movementSetup(true, "Plains", "Forest")
        assertEquals(4f, forest.cost("[1] extra movement cost in [Forest] tiles", "[2] extra movement cost in [Plains] tiles"), 0f)
        // two uniques of one kind add: 2 + 1 + 1
        assertEquals(4f, forest.cost("[1] extra movement cost in [Forest] tiles", "[1] extra movement cost in [Forest] tiles"), 0f)
    }

    @Test
    fun `a city always costs one`() {
        val m = movementSetup(true, "Plains")
        m.game.addCity(m.civ, m.to)
        assertEquals(1f, m.cost("Half movement in [Plains] tiles", "[1] extra movement cost in [Plains] tiles"), 0f)
    }

    @Test
    fun `terrain movement promotions are not read with the switch off`() {
        val m = movementSetup(false, "Plains", "Hill")
        assertEquals(2f, m.cost("Ignores terrain cost in [Hill] tiles"), 0f)
        assertEquals(2f, m.cost("Half movement in [Hill] tiles"), 0f)
        assertEquals(2f, m.cost("[1] extra movement cost in [Hill] tiles"), 0f)
    }

    @Test
    fun `the path search uses the same cost`() {
        // a Warrior has 2 movement: two hills in a row cost 2 + 2, so the second is out of reach this turn, but 1 + 1 with Ignores terrain cost
        for ((uniques, reachesSecond) in listOf(emptyList<String>() to false, listOf("Ignores terrain cost in [Hill] tiles") to true,
            listOf("Half movement in [Hill] tiles") to false)) {
            val m = movementSetup(true, "Plains", "Hill")
            val second = m.game.setTileTerrainAndFeatures(m.game.getTile(2, 0).position, "Plains", "Hill")
            val unit = unitWith(m.game, m.civ, m.from, *uniques.toTypedArray())
            val reachable = unit.movement.getDistanceToTiles()
            assertEquals(uniques.toString(), reachesSecond, reachable.containsKey(second))
            assertTrue(reachable.containsKey(m.to))
        }
    }

    // ---------------------------------------------------------------- GainsXPFromScouting

    /** A unit at (0,0), the tiles it sees explored, then 1 or 2 straight steps to (dx,dy), (2dx,2dy); each step shows the 2 x 2 + 1 = 5 tiles of the next ring of its sight of 2 */
    private fun scoutSetup(flag: Boolean, civilization: (TestGame) -> Civilization = { it.addCiv() }): Pair<TestGame, MapUnit> {
        val game = newGame(6)
        setFlag(game, "scoutingXpLikeVp", flag)
        val civ = civilization(game)
        val unit = unitWith(game, civ, game.getTile(0, 0), "Gains XP from scouting")
        civ.cache.updateViewableTiles()
        unit.promotions.XP = 0
        unit.promotions.xpHundredths = 0
        return game to unit
    }

    @Test
    fun `revealing five plain tiles gives half an experience point and ten of them give one`() {
        val (game, unit) = scoutSetup(true)
        val before = unit.viewableTiles.toSet()
        unit.movement.moveToTile(game.getTile(1, 0))
        assertEquals(5, (unit.viewableTiles - before).size)
        // CvUnit::setXY: 5 tiles x 100 / BALANCE_SCOUT_XP_DENOMINATOR 10 = 50 hundredths
        assertEquals(0, unit.promotions.XP)
        assertEquals(50, unit.promotions.xpHundredths)
        unit.movement.moveToTile(game.getTile(2, 0))
        assertEquals(1, unit.promotions.XP)
        assertEquals(0, unit.promotions.xpHundredths)
    }

    @Test
    fun `a natural wonder, ruins and a resource are worth more`() {
        val (game, unit) = scoutSetup(true)
        val destination = game.getTile(1, 0)
        val startView = unit.viewableTiles.toSet()
        val newTiles = destination.getTilesInDistance(2).filter { it !in startView }.toList()
        assertEquals(5, newTiles.size)
        newTiles[0].naturalWonder = "Krakatoa"                       // BALANCE_SCOUT_XP_RANDOM_VALUE 12
        newTiles[1].setImprovementBasic("Ancient ruins")               // 12 / 3 = 4
        newTiles[2].setTileResource(game.ruleset.tileResources["Wheat"]!!) // 12 / 4 = 3, a bonus resource that needs no technology
        unit.movement.moveToTile(destination)
        // 12 + 4 + 3 + 1 + 1 = 21 points: 210 hundredths
        assertEquals(2, unit.promotions.XP)
        assertEquals(10, unit.promotions.xpHundredths)
    }

    @Test
    fun `scouting experience is scaled by the game speed`() {
        val (game, unit) = scoutSetup(true)
        setExperiencePercent(game, 50)
        try {
            unit.movement.moveToTile(game.getTile(1, 0))
            // CvUnit::changeExperienceTimes100: 50 x 50 / 100
            assertEquals(0, unit.promotions.XP)
            assertEquals(25, unit.promotions.xpHundredths)
        } finally {
            setExperiencePercent(game, 100)
        }
    }

    @Test
    fun `scouting experience needs the switch and not a barbarian`() {
        val (offGame, offUnit) = scoutSetup(false)
        offUnit.movement.moveToTile(offGame.getTile(1, 0))
        assertEquals(0, offUnit.promotions.XP)
        assertEquals(0, offUnit.promotions.xpHundredths)
        val (barbarianGame, barbarianUnit) = scoutSetup(true) { it.addBarbarianCiv() }
        barbarianUnit.movement.moveToTile(barbarianGame.getTile(1, 0))
        assertEquals(0, barbarianUnit.promotions.XP)
        assertEquals(0, barbarianUnit.promotions.xpHundredths)
    }

    // ---------------------------------------------------------------- XPFromPillaging

    private fun pillager(flag: Boolean): Pair<MapUnit, Tile> {
        val game = newGame()
        setFlag(game, "pillageXpLikeVp", flag)
        val civ = game.addCiv(isPlayer = true)
        val tile = game.getTile(1, 0)
        tile.setImprovementBasic("Farm")
        val unit = unitWith(game, civ, tile, "[5] XP when pillaging")
        return unit to tile
    }

    @Test
    fun `pillaging an improvement gives the experience`() {
        val (unit, tile) = pillager(true)
        assertTrue(PillageRules.pillage(unit))
        assertTrue(tile.isPillaged())
        assertEquals(5, unit.promotions.XP)
        assertEquals(0, unit.promotions.xpHundredths)
    }

    @Test
    fun `pillaging gives no experience with the switch off`() {
        val (unit, tile) = pillager(false)
        assertTrue(PillageRules.pillage(unit))
        assertTrue(tile.isPillaged())
        assertEquals(0, unit.promotions.XP)
    }

    @Test
    fun `a unit that gets experience from pillaging pillages although undamaged`() {
        val (unit, tile) = pillager(true)
        assertTrue(PillageRules.shouldPillage(unit, tile, ignoreMovement = true))
        val (plain, plainTile) = pillager(false)
        assertFalse(PillageRules.shouldPillage(plain, plainTile, ignoreMovement = true))
    }

    // ---------------------------------------------------------------- LostOnGifting

    private class Gift(val unit: MapUnit, val receiver: Civilization, val lost: String, val kept: String)

    private fun giftedUnit(flag: Boolean, freeFromUnit: Boolean): Gift {
        val game = newGame()
        setFlag(game, "giftPromotionLossLikeVp", flag)
        val giver = game.addCiv(isPlayer = true)
        val receiver = game.addCiv()
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = 8
        val lost = game.createUnitPromotion("Lost when the unit is gifted")
        val kept = game.createUnitPromotion("[+10]% Strength")
        if (freeFromUnit) base.promotions.add(lost.name)
        val unit = game.addUnit(base.name, giver, game.getTile(0, 0))
        unit.promotions.addPromotion(lost.name, isFree = true)
        unit.promotions.addPromotion(kept.name, isFree = true)
        assertEquals(setOf(lost.name, kept.name), unit.promotions.promotions)
        unit.gift(receiver, keepsPromotions = false)
        return Gift(unit, receiver, lost.name, kept.name)
    }

    @Test
    fun `a gifted unit loses the promotions that are lost on gifting`() {
        val gift = giftedUnit(true, freeFromUnit = false)
        assertEquals(gift.receiver, gift.unit.civ)
        assertEquals(setOf(gift.kept), gift.unit.promotions.promotions)
    }

    @Test
    fun `a gifted unit keeps the promotion its type gives for free and every promotion with the switch off`() {
        val free = giftedUnit(true, freeFromUnit = true)
        assertEquals(setOf(free.lost, free.kept), free.unit.promotions.promotions)
        val off = giftedUnit(false, freeFromUnit = false)
        assertEquals(setOf(off.lost, off.kept), off.unit.promotions.promotions)
    }

    @Test
    fun `a unit that is not gifted by the player keeps its promotions`() {
        val game = newGame()
        setFlag(game, "giftPromotionLossLikeVp", true)
        val giver = game.addCiv(isPlayer = true)
        val receiver = game.addCiv()
        val unit = unitWith(game, giver, game.getTile(0, 0))
        val lost = game.createUnitPromotion("Lost when the unit is gifted")
        unit.promotions.addPromotion(lost.name, isFree = true)
        unit.gift(receiver) // a city-state's buyout or marriage is not a gift of CvUnit::gift
        assertEquals(setOf(lost.name), unit.promotions.promotions)
    }

    // ---------------------------------------------------------------- MinorCivGift / NoMinorCivGift

    /** Whether the one candidate with [uniques] (of a civilization that is not in the game if [uniqueToOther], else of none) becomes the unique unit of a militaristic city-state */
    private fun becomesUniqueUnit(flag: Boolean, uniqueToOther: Boolean, vararg uniques: String): Boolean {
        val game = newGame(2)
        setFlag(game, "cityStateGiftUnitFlagsLikeVp", flag)
        val owner = game.addCiv()
        val unit = game.createBaseUnit("Sword", *uniques)
        unit.strength = 10
        unit.movement = 2
        unit.requiredTech = "Rifling"
        if (uniqueToOther) unit.uniqueTo = owner.civName
        val used = game.ruleset.nations.values.filter { it.isMajorCiv && (!uniqueToOther || it.name != owner.civName) }
        val cityState = game.addCiv(cityStateType = "Militaristic")
        cityState.cityStateUniqueUnit = null
        cityState.cityStateFunctions.initCityState(game.ruleset, "Ancient era", used.asSequence(), Random(1))
        return cityState.cityStateUniqueUnit == unit.name
    }

    @Test
    fun `a gift unit is a possible unique unit of a militaristic city-state without a civilization`() {
        assertTrue(becomesUniqueUnit(true, uniqueToOther = false, "Can be gifted by city-states"))
        assertFalse(becomesUniqueUnit(false, uniqueToOther = false, "Can be gifted by city-states"))
    }

    @Test
    fun `a unit that cannot be gifted is never a unique unit of a city-state`() {
        assertTrue(becomesUniqueUnit(false, uniqueToOther = true, "Cannot be gifted by city-states"))
        assertFalse(becomesUniqueUnit(true, uniqueToOther = true, "Cannot be gifted by city-states"))
    }

    @Test
    fun `a gift unit cannot be trained`() {
        val game = newGame(2)
        setFlag(game, "cityStateGiftUnitFlagsLikeVp", true)
        val civ = game.addCiv()
        val unit = game.createBaseUnit("Sword", "Can be gifted by city-states")
        assertTrue(unit.getRejectionReasons(civ).any { it.type == RejectionReasonType.Unbuildable })
        setFlag(game, "cityStateGiftUnitFlagsLikeVp", false)
        assertFalse(unit.getRejectionReasons(civ).any { it.type == RejectionReasonType.Unbuildable })
    }
}

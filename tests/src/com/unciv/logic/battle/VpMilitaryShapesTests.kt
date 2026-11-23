package com.unciv.logic.battle

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** VP 5.4.8 CvUnitCombat::ResolveMeleeCombat and CvUnit::baseMoves. */
@RunWith(BaseTestRunner::class)
class VpMilitaryShapesTests {
    private fun setFlag(game: TestGame, name: String, value: Boolean) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Boolean::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }

    private fun setInt(game: TestGame, name: String, value: Int) {
        game.ruleset.modOptions.constants.javaClass.getMethod("set" + name.replaceFirstChar { it.uppercase() }, Int::class.javaPrimitiveType)
            .invoke(game.ruleset.modOptions.constants, value)
    }
    @Test
    fun `only first attack earns attacker experience when enabled but defence still earns experience`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val attackerCiv = game.addCiv(isPlayer = true)
        val defenderCiv = game.addCiv(isPlayer = true)
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
        val attacker = game.addUnit("Warrior", attackerCiv, game.getTile(0, 0))
        val defender = game.addUnit("Warrior", defenderCiv, game.getTile(1, 0))
        setFlag(game, "combatXpFirstAttackOnly", true)
        attacker.attacksThisTurn = 1
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))
        assertEquals(0, attacker.promotions.XP)
        assertEquals(4, defender.promotions.XP)
    }

    @Test
    fun `support fire kill earns ranged attack XP rather than melee XP`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val a = game.addCiv(isPlayer = true)
        val b = game.addCiv(isPlayer = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.getDiplomacyManager(b)!!.declareWar()
        val impi = game.addUnit("Impi", a, game.getTile(0, 0))
        val target = game.addUnit("Warrior", b, game.getTile(1, 0))
        target.health = 1
        setFlag(game, "combatXpFirstAttackOnly", true)
        Battle.attack(MapUnitCombatant(impi), MapUnitCombatant(target))
        assertEquals(2, impi.promotions.XP)
    }

    @Test
    fun `barbarian cap clamps an award when VP caps are enabled`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val player = game.addCiv(isPlayer = true)
        val barbarian = game.addBarbarianCiv()
        val unit = game.addUnit("Warrior", player, game.getTile(0, 0))
        val enemy = game.addUnit("Brute", barbarian, game.getTile(1, 0))
        setFlag(game, "combatXpCapsLikeVp", true)
        game.ruleset.modOptions.constants.maxXPfromBarbarians = 45
        unit.promotions.XP = 44
        Battle.addXp(MapUnitCombatant(unit), 5, MapUnitCombatant(enemy))
        assertEquals(45, unit.promotions.XP)
    }

    @Test
    fun `barbarian XP cap scales with game speed`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val player = game.addCiv(isPlayer = true)
        val barbarian = game.addBarbarianCiv()
        val unit = game.addUnit("Warrior", player, game.getTile(0, 0))
        val enemy = game.addUnit("Brute", barbarian, game.getTile(1, 0))
        setFlag(game, "combatXpCapsLikeVp", true)
        game.ruleset.modOptions.constants.maxXPfromBarbarians = 45
        game.gameInfo.speed.javaClass.getMethod("setExperiencePercent", Int::class.javaPrimitiveType).invoke(game.gameInfo.speed, 50)
        unit.promotions.XP = 21
        Battle.addXp(MapUnitCombatant(unit), 5, MapUnitCombatant(enemy))
        assertEquals(22, unit.promotions.XP)
    }

    @Test
    fun `healing inputs respect territory and preserve resource shortage`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val civ = game.addCiv(isPlayer = true)
        val enemy = game.addCiv(isPlayer = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        val city = game.addCity(enemy, game.getTile(1, 0))
        val tile = game.getTile(0, 0)
        game.addTileToCity(city, tile)
        tile.setOwningCity(city)
        val warrior = game.addUnit("Warrior", civ, tile)
        warrior.health = 50
        setFlag(game, "unitHealingLikeVp", true)
        setInt(game, "enemyUnitHeal", 5)
        assertEquals(5, warrior.getHealAmountForCurrentTile())
        val swordsman = game.addUnit("Swordsman", civ, game.getTile(-1, 0))
        swordsman.health = 50
        game.ruleset.modOptions.constants.resourceShortageLikeVp = true
        civ.cache.updateCivResources()
        assertEquals(0, swordsman.getHealAmountForCurrentTile())
        assertEquals(0, swordsman.healingAmountForTile(swordsman.getTile()))
    }

    @Test
    fun `healing at hypothetical tile uses nearby same domain medic`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val civ = game.addCiv(isPlayer = true)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        val medic = game.addUnit("Warrior", civ, game.getTile(2, 1))
        val promotion = com.unciv.models.ruleset.unit.Promotion().apply {
            name = "Test medic"
            unitTypes = listOf(medic.type.name)
            uniques = arrayListOf("All adjacent units heal [+5] HP when healing")
        }
        game.ruleset.unitPromotions[promotion.name] = promotion
        medic.promotions.addPromotion(promotion.name, isFree = true)
        setFlag(game, "unitHealingLikeVp", true)
        assertEquals(15, unit.healingAmountForTile(game.getTile(2, 0), noTerrainDamage = true))
    }

    @Test
    fun `state religion belief heals in own territory when capital exists`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(0, 0), true)
        val tile = game.getTile(1, 0)
        city.expansion.takeOwnership(tile)
        val unit = game.addUnit("Warrior", civ, tile)
        val belief = com.unciv.models.ruleset.Belief().apply {
            name = "Test healing belief"
            type = com.unciv.models.ruleset.BeliefType.Follower
            uniques = arrayListOf("[Military] Units adjacent to this city heal [+9] HP per turn when healing")
        }
        val founderBelief = com.unciv.models.ruleset.Belief().apply {
            name = "Test founder belief"
            type = com.unciv.models.ruleset.BeliefType.Founder
        }
        game.ruleset.beliefs[belief.name] = belief
        game.ruleset.beliefs[founderBelief.name] = founderBelief
        val religion = game.addReligion(civ)
        religion.addBelief(founderBelief)
        religion.addBelief(belief)
        civ.religionManager.religion = religion
        city.religion.religionThisIsTheHolyCityOf = religion.name
        city.religion.addPressure(religion.name, 100) // IsBeliefValid: a follower belief needs the holy city to follow the religion
        setFlag(game, "unitHealingLikeVp", true)
        setFlag(game, "stateReligionHealingLikeVp", true)
        // Own territory heals at the friendly rate of the ruleset (15 in Vox Populi, as set in ModOptions.json), plus the +9 of the belief
        assertEquals(game.ruleset.modOptions.constants.friendlyUnitHeal + 9, unit.healingAmountForTile(tile, noTerrainDamage = true))
    }

    @Test
    fun `territory heal aura reaches hypothetical tile within range`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        val civ = game.addCiv(isPlayer = true)
        val patient = game.addUnit("Warrior", civ, game.getTile(-2, 0))
        val medic = game.addUnit("Warrior", civ, game.getTile(2, 0))
        val promotion = com.unciv.models.ruleset.unit.Promotion().apply {
            name = "Territory medic"
            unitTypes = listOf(medic.type.name)
            uniques = arrayListOf("[+7] HP healing in neutral territory for [Military] units on [All] tiles within [2] tiles")
        }
        game.ruleset.unitPromotions[promotion.name] = promotion
        medic.promotions.addPromotion(promotion.name, isFree = true)
        setFlag(game, "unitHealingLikeVp", true)
        assertEquals(17, patient.healingAmountForTile(game.getTile(0, 0), noTerrainDamage = true))
        assertEquals(10, patient.healingAmountForTile(patient.getTile(), noTerrainDamage = true))
    }

    @Test
    fun `embarked movement is configurable without changing the default`() {
        val game = TestGame("Enables embarkation for land units", baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val civ = game.addCiv()
        val tile = game.getTile(0, 0)
        tile.baseTerrain = "Coast"
        tile.setTerrainTransients()
        val unit = game.addUnit("Warrior", civ, tile)
        // The Brave New World ruleset sets Vox Populi's 3: the default of ModConstants is the original's 2
        setInt(game, "embarkedUnitMovement", com.unciv.models.ModConstants().embarkedUnitMovement)
        assertEquals(2, unit.getMaxMovement())
        setInt(game, "embarkedUnitMovement", 3)
        assertEquals(3, unit.getMaxMovement())
    }

    @Test
    fun `city and garrison share damage in preview and execution`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val attackerCiv = game.addCiv(isPlayer = true)
        val defenderCiv = game.addCiv(isPlayer = true)
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
        val city = game.addCity(defenderCiv, game.getTile(1, 0))
        val garrison = game.addUnit("Warrior", defenderCiv, city.getCenterTile())
        val attacker = MapUnitCombatant(game.addUnit("Warrior", attackerCiv, game.getTile(0, 0)))
        val defender = CityCombatant(city)
        setFlag(game, "cityCombatLikeVp", true)
        val cityDamage = BattleDamage.calculateDamageToDefender(attacker, defender)
        val cityBefore = city.health
        val garrisonBefore = garrison.health
        Battle.takeDamage(attacker, defender)
        val garrisonDamage = garrisonBefore - garrison.health
        assertEquals(cityDamage, cityBefore - city.health)
        assertEquals((cityDamage + garrisonDamage) * 200 / (defender.getMaxHealth() + 200), garrisonDamage)
    }

    @Test
    fun `ranged defence uses hypothetical health without wounded penalty`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val a = game.addCiv(isPlayer = true)
        val b = game.addCiv(isPlayer = true)
        val archer = MapUnitCombatant(game.addUnit("Archer", a, game.getTile(0, 0)))
        val melee = MapUnitCombatant(game.addUnit("Warrior", a, game.getTile(-1, 0)))
        val unit = game.addUnit("Warrior", b, game.getTile(1, 0))
        val full = MapUnitCombatant(unit)
        val wounded = object : MapUnitCombatant(unit) { override fun getHealth() = 40 }
        setFlag(game, "combatHealthStrengthLikeVp", true)
        assertEquals(BattleDamage.getDefendingStrength(archer, full, archer.getTile()),
            BattleDamage.getDefendingStrength(archer, wounded, archer.getTile()), 0.001f)
        val fullMelee = BattleDamage.getDefendingStrength(melee, full, melee.getTile())
        val woundedMelee = BattleDamage.getDefendingStrength(melee, wounded, melee.getTile())
        org.junit.Assert.assertTrue(woundedMelee < fullMelee)
    }

    @Test
    fun `stronger when wounded uses hypothetical health even against ranged attacks`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val a = game.addCiv(isPlayer = true)
        val b = game.addCiv("Stronger when damaged", isPlayer = true)
        val archer = MapUnitCombatant(game.addUnit("Archer", a, game.getTile(0, 0)))
        val unit = game.addUnit("Warrior", b, game.getTile(1, 0))
        val full = MapUnitCombatant(unit)
        val wounded = object : MapUnitCombatant(unit) { override fun getHealth() = 40 }
        setFlag(game, "combatHealthStrengthLikeVp", true)
        org.junit.Assert.assertTrue(BattleDamage.getDefendingStrength(archer, wounded, archer.getTile()) >
            BattleDamage.getDefendingStrength(archer, full, archer.getTile()))
    }

    @Test
    fun `enemy aura reaches hypothetical attack tile and does not stack`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        val ours = game.addCiv(isPlayer = true)
        val theirs = game.addCiv("[-20]% Strength for enemy [Military] units within [2] tiles", isPlayer = true)
        ours.diplomacyFunctions.makeCivilizationsMeet(theirs)
        ours.getDiplomacyManager(theirs)!!.declareWar()
        val unit = MapUnitCombatant(game.addUnit("Warrior", ours, game.getTile(-2, 0)))
        val defender = MapUnitCombatant(game.addUnit("Warrior", theirs, game.getTile(2, 0)))
        setFlag(game, "enemyCombatAurasLikeVp", true)
        val distant = BattleDamage.getAttackModifiers(unit, defender, unit.getTile())
        val nearby = BattleDamage.getAttackModifiers(unit, defender, game.getTile(0, 0))
        assertEquals(0, distant["Nearby enemy units"])
        assertEquals(-20, nearby["Nearby enemy units"])
    }

    @Test
    fun `wounded player modifier is a flat strength bonus`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val civ = game.addCiv("[-25]% wounded unit damage penalty", isPlayer = true)
        val enemy = game.addCiv(isPlayer = true)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        val defender = MapUnitCombatant(game.addUnit("Warrior", enemy, game.getTile(1, 0)))
        val full = MapUnitCombatant(unit)
        val wounded = object : MapUnitCombatant(unit) { override fun getHealth() = 99 }
        setFlag(game, "combatHealthStrengthLikeVp", true)
        val fullStrength = BattleDamage.getAttackingStrength(full, defender, full.getTile())
        val woundedStrength = BattleDamage.getAttackingStrength(wounded, defender, wounded.getTile())
        assertEquals(fullStrength * 1.25f, woundedStrength, 0.01f)
    }

    @Test
    fun `damage taken modifier affects ranged attack prediction`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val attacker = MapUnitCombatant(game.addUnit("Archer", game.addCiv(isPlayer = true), game.getTile(0, 0)))
        val plain = MapUnitCombatant(game.addUnit("Warrior", game.addCiv(isPlayer = true), game.getTile(1, 0)))
        val vulnerable = MapUnitCombatant(game.addUnit("Warrior", game.addCiv("[+50]% damage taken", isPlayer = true), game.getTile(0, 1)))
        setFlag(game, "combatHealthStrengthLikeVp", true)
        val base = BattleDamage.calculateDamageToDefender(attacker, plain, attacker.getTile(), 0.5f)
        val raised = BattleDamage.calculateDamageToDefender(attacker, vulnerable, attacker.getTile(), 0.5f)
        org.junit.Assert.assertTrue(raised > base)
    }

    @Test
    fun `naval ranged defenders use ranged strength when VP combat is enabled`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        game.setTileTerrain(game.getTile(0, 0).position, com.unciv.Constants.ocean)
        game.setTileTerrain(game.getTile(1, 0).position, com.unciv.Constants.ocean)
        val attacker = MapUnitCombatant(game.addUnit("Frigate", game.addCiv(), game.getTile(0, 0)))
        val ship = game.addUnit("Frigate", game.addCiv(), game.getTile(1, 0))
        setFlag(game, "combatHealthStrengthLikeVp", true)
        assertEquals(ship.baseUnit.rangedStrength, MapUnitCombatant(ship).getDefendingStrength(attacker))
    }

    @Test
    fun `melee overkill reduces damage dealt by the dying defender`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val attacker = MapUnitCombatant(game.addUnit("Warrior", game.addCiv(), game.getTile(0, 0)))
        val defenderUnit = game.addUnit("Warrior", game.addCiv(), game.getTile(1, 0))
        defenderUnit.health = 30
        val defender = MapUnitCombatant(defenderUnit)
        setFlag(game, "combatHealthStrengthLikeVp", true)
        val (toDefender, toAttacker) = BattleDamage.resolveMeleeDeaths(attacker, defender, 40, 30)
        assertEquals(40, toDefender)
        assertEquals(22, toAttacker)
    }

    @Test
    fun `promotion requires mandatory prerequisite in addition to alternatives`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val unit = game.addUnit("Warrior", game.addCiv(), game.getTile(0, 0))
        val promotion = com.unciv.models.ruleset.unit.Promotion().apply {
            name = "Staged prerequisite"
            unitTypes = listOf(unit.type.name)
            prerequisites = listOf("Shock I")
            uniques = arrayListOf("Requires [Drill I] promotion")
        }
        game.ruleset.unitPromotions[promotion.name] = promotion
        unit.promotions.addPromotion("Shock I", isFree = true)
        org.junit.Assert.assertFalse(unit.promotions.getAvailablePromotions().any { it.name == promotion.name })
        unit.promotions.addPromotion("Drill I", isFree = true)
        org.junit.Assert.assertTrue(unit.promotions.getAvailablePromotions().any { it.name == promotion.name })
    }

    @Test
    fun `unit remembers attacks separately by civilization`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val first = game.addCiv(isPlayer = true)
        val second = game.addCiv(isPlayer = true)
        val target = game.addUnit("Warrior", game.addCiv(isPlayer = true), game.getTile(0, 0))
        val shooter = game.addUnit("Archer", first, game.getTile(1, 0))
        setFlag(game, "cityCombatLikeVp", true)
        Battle.takeDamage(MapUnitCombatant(shooter), MapUnitCombatant(target))
        val getter = target.javaClass.getMethod("getTimesAttackedBy", String::class.java)
        assertEquals(1, getter.invoke(target, first.civName))
        assertEquals(0, getter.invoke(target, second.civName))
    }

    @Test
    fun `civilian promotion may name its specific unit`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val civ = game.addCiv(isPlayer = true)
        val worker = game.addUnit("Worker", civ, game.getTile(0, 0))
        val promotion = com.unciv.models.ruleset.unit.Promotion().apply {
            name = "Worker training"
            unitTypes = listOf("Worker")
        }
        game.ruleset.unitPromotions[promotion.name] = promotion
        setFlag(game, "civilianPromotionApplicabilityLikeVp", false) // the Brave New World ruleset switches it on; the default of ModConstants is off
        org.junit.Assert.assertFalse(worker.promotions.getAvailablePromotions().any { it.name == promotion.name })
        setFlag(game, "civilianPromotionApplicabilityLikeVp", true)
        org.junit.Assert.assertTrue(worker.promotions.getAvailablePromotions().any { it.name == promotion.name })
    }

    @Test
    fun `unit specific feature restriction blocks movement`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val civ = game.addCiv("Cannot enter [Forest] tiles", isPlayer = true)
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        val forest = game.setTileFeatures(game.getTile(1, 0).position, "Forest")
        org.junit.Assert.assertFalse(unit.movement.canPassThrough(forest))
        org.junit.Assert.assertTrue(unit.movement.canPassThrough(game.getTile(0, 1)))
    }

    @Test
    fun `default allows experience on later attacks`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        val attackerCiv = game.addCiv(isPlayer = true)
        val defenderCiv = game.addCiv(isPlayer = true)
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
        val attacker = game.addUnit("Warrior", attackerCiv, game.getTile(0, 0))
        val defender = game.addUnit("Warrior", defenderCiv, game.getTile(1, 0))
        setFlag(game, "combatXpFirstAttackOnly", false) // the Brave New World ruleset switches it on; the default of ModConstants is off
        attacker.attacksThisTurn = 1
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))
        assertEquals(5, attacker.promotions.XP)
    }
}

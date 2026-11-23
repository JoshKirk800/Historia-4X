package com.unciv.logic.battle

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ModConstants
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import yairm210.purity.annotations.Readonly
import kotlin.math.min
import kotlin.random.Random

object BattleUnitCapture {

    fun tryCaptureMilitaryUnit(attacker: ICombatant, defender: ICombatant, attackedTile: Tile): Boolean {
        // https://forums.civfanatics.com/threads/prize-ships-for-land-units.650196/
        // https://civilization.fandom.com/wiki/Module:Data/Civ5/GK/Defines\
        // There are 3 ways of capturing a unit, we separate them for cleaner code but we also need to ensure a unit isn't captured twice

        if (defender !is MapUnitCombatant || attacker !is MapUnitCombatant) return false
        if (defender.hasUnique(UniqueType.Uncapturable, GameContext(unit = defender.unit,
                ourCombatant = defender, theirCombatant = attacker, attackedTile = attackedTile)))
            return false

        if (!defender.isDefeated() || defender.unit.isCivilian()) return false

        // Due to the way OR operators short-circuit, calling just A() || B() means B isn't called if A is true.
        // Therefore we run all functions before checking if one is true.
        val wasUnitCaptured = listOf(
            unitCapturedPrizeShipsUnique(attacker, defender),
            unitGainFromDefeatingUnit(attacker, defender)
        ).any { it }

        if (!wasUnitCaptured) return false

        // This is called after takeDamage and so the defeated defender is already destroyed and
        // thus removed from the tile - but MapUnit.destroy() will not clear the unit's currentTile.
        // Therefore placeUnitNearTile _will_ place the new unit exactly where the defender was
        return spawnCapturedUnit(defender, attacker)
    }

    
    @Readonly
    private fun unitCapturedPrizeShipsUnique(attacker: MapUnitCombatant, defender: MapUnitCombatant): Boolean {
        if (attacker.unit.getMatchingUniques(UniqueType.KillUnitCapture)
                .none { defender.matchesFilter(it.params[0]) }
        ) return false

        val captureChance = min(
            0.8f,
            0.1f + attacker.getAttackingStrength(defender).toFloat() / defender.getDefendingStrength(attacker)
                .toFloat() * 0.4f
        )
        /** Between 0 and 1.  Defaults to turn and location-based random to avoid save scumming */
        val random = Random((attacker.getCivInfo().gameInfo.turns * defender.getTile().position.toVector2().hashCode()).toLong())
        return random.nextFloat() <= captureChance
    }


    private fun unitGainFromDefeatingUnit(attacker: MapUnitCombatant, defender: MapUnitCombatant): Boolean {
        if (!attacker.isMelee()) return false
        var unitCaptured = false
        val state = GameContext(attacker.getCivInfo(), ourCombatant = attacker, theirCombatant = defender)
        for (unique in attacker.getMatchingUniques(UniqueType.GainFromDefeatingUnit, state, true)) {
            if (defender.unit.matchesFilter(unique.params[0])) {
                attacker.getCivInfo().addGold(unique.params[1].toInt())
                unitCaptured = true
            }
        }
        return unitCaptured
    }

    /**
     *  The health a captured military unit has with `conquestLikeVp` (CvUnit::createCaptureUnit): `maxHitPoints * COMBAT_CAPTURE_HEALTH / 100`, rounded down,
     *  is passed to CvUnit::setDamage (which limits it to 0 until the maximum): VP's source makes it the unit's damage, so it keeps what is left of its hit points
     *  ([ModConstants.ConquestConstants.combatCaptureHealthIsDamage]; with 75: a quarter of them). With the constant false it is the health.
     */
    @Readonly
    fun capturedUnitHealthLikeVp(maxHealth: Int, constants: ModConstants.ConquestConstants): Int {
        val share = (maxHealth * constants.combatCaptureHealthPercent / 100).coerceIn(0, maxHealth)
        return (if (constants.combatCaptureHealthIsDamage) maxHealth - share else share).coerceAtLeast(1) // a unit with no hit points left would be dead
    }

    /** Places a [unitName] unit near [tile] after being attacked by [attacker].
     * Adds a notification to [attacker]'s civInfo and returns whether the captured unit could be placed */
    private fun spawnCapturedUnit(defender: MapUnitCombatant, attacker: MapUnitCombatant): Boolean {
        val defenderTile = defender.getTile()
        val addedUnit = attacker.getCivInfo().units.placeUnitNearTile(defenderTile.position, defender.getName()) ?: return false
        addedUnit.currentMovement = 0f
        // CvUnit::createCaptureUnit: the captured unit's health is a share of its own GetMaxHitPoints (COMBAT_CAPTURE_HEALTH, 50 here)
        addedUnit.health = addedUnit.getMaxHealth() * 50 / 100
        val constants = attacker.getCivInfo().gameInfo.ruleset.modOptions.constants
        if (constants.conquestLikeVp) {
            addedUnit.health = capturedUnitHealthLikeVp(addedUnit.getMaxHealth(), constants.conquestConstants)
            if (constants.conquestConstants.capturedUnitMovesButCannotAttack) {
                addedUnit.currentMovement = addedUnit.getMaxMovement().toFloat()
                addedUnit.attacksThisTurn = addedUnit.maxAttacksPerTurn()
            }
        }
        attacker.getCivInfo().addNotification("An enemy [${defender.getName()}] has joined us!", MapUnitAction(addedUnit), NotificationCategory.War, defender.getName())

        defender.getCivInfo().addNotification(
            "An enemy [${attacker.getName()}] has captured our [${defender.getName()}]",
            defender.getTile().position, NotificationCategory.War, attacker.getName(),
            NotificationIcon.War, defender.getName()
        )

        val civilianUnit = defenderTile.civilianUnit
        // placeUnitNearTile might not have spawned the unit in exactly this tile, in which case no capture would have happened on this tile. So we need to do that here.
        if (addedUnit.getTile() != defenderTile && civilianUnit != null) {
            captureCivilianUnit(attacker, MapUnitCombatant(civilianUnit))
        }
        return true
    }


    /**
     * @throws IllegalArgumentException if the [attacker] and [defender] belong to the same civ.
     */
    fun captureCivilianUnit(attacker: ICombatant, defender: MapUnitCombatant, checkDefeat: Boolean = true) {
        require(attacker.getCivInfo() != defender.getCivInfo()) {
            "Can't capture our own unit!"
        }

        // need to save this because if the unit is captured its owner will be overwritten
        val defenderCiv = defender.getCivInfo()
        // CvUnit::kill (Vox Populi): a lost civilian counts in both sides' war progress
        com.unciv.logic.automation.diplomacy.WarProgressAi.onCivilianLost(defenderCiv, attacker.getCivInfo(),
            defender.unit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals))

        val capturedUnit = defender.unit
        // Stop current action
        capturedUnit.action = null
        capturedUnit.automated = false

        val capturedUnitTile = capturedUnit.getTile()
        val originalOwner = capturedUnit.originalOwningCiv

        var wasDestroyedInstead = false
        when {
            // Uncapturable units are destroyed
            defender.unit.hasUnique(UniqueType.Uncapturable) -> {
                capturedUnit.destroy()
                wasDestroyedInstead = true
            }
            // City states can never capture settlers at all
            // Same with puppet city sttlers
             attacker.getCivInfo().isCityState && capturedUnitFoundsCity(capturedUnit, attacker.getCivInfo()) -> {
                capturedUnit.destroy()
                wasDestroyedInstead = true
            }
            // Is it our old unit?
            attacker.getCivInfo() == originalOwner -> {
                // Then it is recaptured without converting settlers to workers
                capturedUnit.capturedBy(attacker.getCivInfo())
            }
            // Return captured civilian to its original owner?
            defender.getCivInfo().isBarbarian
                && originalOwner != null
                && !originalOwner.isBarbarian
                && attacker.getCivInfo() != originalOwner
                && attacker.getCivInfo().knows(originalOwner)
                && originalOwner.isAlive()
                && !attacker.getCivInfo().isAtWarWith(originalOwner)
                && attacker.getCivInfo().playerType == PlayerType.Human // Only humans get the choice
            -> {
                capturedUnit.capturedBy(attacker.getCivInfo())
                attacker.getCivInfo().popupAlerts.add(
                    PopupAlert(
                        AlertType.RecapturedCivilian,
                        capturedUnit.currentTile.position.toPrettyString()
                    )
                )
            }

            else ->
                if (captureOrConvertToWorker(capturedUnit, attacker.getCivInfo()) == null)
                    wasDestroyedInstead = true
        }

        if (!wasDestroyedInstead)
            defenderCiv.addNotification(
                "An enemy [${attacker.getName()}] has captured our [${defender.getName()}]",
                defender.getTile().position, NotificationCategory.War, attacker.getName(),
                NotificationIcon.War, defender.getName()
            )
        else {
            defenderCiv.addNotification(
                "An enemy [${attacker.getName()}] has destroyed our [${defender.getName()}]",
                defender.getTile().position, NotificationCategory.War, attacker.getName(),
                NotificationIcon.War, defender.getName()
            )
            Battle.triggerDefeatUniques(defender, attacker, capturedUnitTile)
        }

        if (checkDefeat)
            Battle.destroyIfDefeated(defenderCiv, attacker.getCivInfo())
        capturedUnit.updateVisibleTiles()
    }

    /**
     *  Capture wrapper that also implements the rule that non-barbarians get a Worker as replacement for a captured Settler.
     *  @return position the captured unit is in afterwards - can rarely be a different tile if the unit is no longer allowed where it originated.
     *          Returns `null` if there is no Worker replacement for a Settler in the ruleset or placeUnitNearTile couldn't place it.
     *  @see MapUnit.capturedBy
     */
    fun captureOrConvertToWorker(capturedUnit: MapUnit, capturingCiv: Civilization): HexCoord? {
        val captureClassUnit = captureClassUnit(capturedUnit, capturingCiv)
        if (captureClassUnit != null) {
            // CvUnit::createCaptureUnit makes a new unit of the capture class; a unit that already is that type keeps its state here
            if (captureClassUnit.name == capturedUnit.name) {
                capturedUnit.capturedBy(capturingCiv)
                return capturedUnit.currentTile.position
            }
            return convertCapturedUnit(capturedUnit, capturingCiv, captureClassUnit)
        }

        // Captured settlers are converted to workers unless captured by barbarians (so they can be returned later).
        if (!capturedUnit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals) || capturingCiv.isBarbarian) {
            capturedUnit.capturedBy(capturingCiv)
            return capturedUnit.currentTile.position // if capturedBy has moved the unit, this is updated
        }

        val workerTypeUnit = capturingCiv.gameInfo.ruleset.units.values
            .firstOrNull { it.isCivilian() && it.getMatchingUniques(UniqueType.BuildImprovements, GameContext.IgnoreConditionals)
            .any { unique -> unique.params[0] == "Land" } }
        return convertCapturedUnit(capturedUnit, capturingCiv, workerTypeUnit)
    }

    /** Destroys [capturedUnit] and places a unit of type [replacement] for [capturingCiv] near its tile, keeping its id; `null` if there is no [replacement] or no place for it. */
    private fun convertCapturedUnit(capturedUnit: MapUnit, capturingCiv: Civilization, replacement: BaseUnit?): HexCoord? {
        capturedUnit.destroy()
        // This is so that future checks which check if a unit has been captured are caught give the right answer
        //  For example, in postBattleMoveToAttackedTile
        capturedUnit.civ = capturingCiv
        capturedUnit.cache.state = GameContext(capturedUnit)

        if (replacement == null) return null
        return capturingCiv.units.placeUnitNearTile(capturedUnit.currentTile.position, replacement, capturedUnit.id)
            ?.currentTile?.position
    }

    /**
     *  The unit [capturingCiv] gets for [capturedUnit] under the switch `captureClassLikeVp`: CvUnit::getCaptureDefinition and CvUnit::getCaptureUnitType.
     *  Barbarians involved (as owner or capturer, and the unit can be captured at all): the unit type stays as it is.
     *  Otherwise the capturer's own unit of the capture class (`GetSpecificUnitType`): the default unit named by the unique,
     *  or the capturer's unique replacement of it ([Civilization.getEquivalentUnit]).
     *  `null`: switch off, no [UniqueType.BecomesWhenCaptured] on the unit (those units follow the old rules), or the named unit is not in the ruleset.
     */
    private fun captureClassUnit(capturedUnit: MapUnit, capturingCiv: Civilization): BaseUnit? {
        val ruleset = capturingCiv.gameInfo.ruleset
        if (!ruleset.modOptions.constants.captureClassLikeVp) return null
        val unique = capturedUnit.baseUnit.getMatchingUniques(UniqueType.BecomesWhenCaptured, GameContext.IgnoreConditionals).firstOrNull()
            ?: return null
        val captureClass = ruleset.units[unique.params[0]] ?: return null
        if (capturedUnit.civ.isBarbarian || capturingCiv.isBarbarian) return capturedUnit.baseUnit
        return capturingCiv.getEquivalentUnit(captureClass)
    }

    /** CvUnit::createCaptureUnit: a city-state kills a captured unit that founds cities - judged on the unit it would get ([captureClassUnit]) when the switch gives one. */
    private fun capturedUnitFoundsCity(capturedUnit: MapUnit, capturingCiv: Civilization): Boolean {
        val captureClassUnit = captureClassUnit(capturedUnit, capturingCiv)
        if (captureClassUnit != null)
            return captureClassUnit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals) ||
                captureClassUnit.hasUnique(UniqueType.FoundPuppetCity, GameContext.IgnoreConditionals)
        return capturedUnit.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals) ||
            capturedUnit.hasUnique(UniqueType.FoundPuppetCity, GameContext.IgnoreConditionals)
    }

}

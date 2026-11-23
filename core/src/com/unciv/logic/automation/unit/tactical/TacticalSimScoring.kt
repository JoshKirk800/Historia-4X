package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  How good a step of a plan is: the scores of CvTacticalAI.cpp's ScoreAttackDamage, ScoreCombatUnitTurnEnd, ScorePlotForCombatUnitMove,
 *  ScorePlotForRangedAttack, ScorePlotForMeleeAttack and ScorePlotForPillageMove. The numbers are the original's; so are the quirks that are
 *  not plainly wrong (an attack on a unit gets the damage it does twice, as bonus and damage; the aggression table is indexed by
 *  [AggressionLevel.ordinal], which makes [AggressionLevel.Braveheart] limit 0 rather than the -5 the comment above the table seems to say:
 *  the C array is `{70,40,20,-5}` and None comes first).
 *
 *  Not scored (the units and rules they are about are not in the simulation): support units and embarked units, the bonus of a General
 *  or siege tower, splash damage, plague, experience and yield bonuses for kills, restoring moves in an improvement, healing at a fixed rate
 *  over movement, units that always heal or heal by a flat amount.
 */
class SimScoring(private val w: SimWorld) {
    private val civ = w.civ
    private val averageExperience100: Int by lazy {
        val units = civ.units.getCivUnits().filter { it.isMilitary() }.toList()
        if (units.isEmpty()) 0 else units.sumOf { it.promotions.XP } * 100 / units.size
    }
    private val cityDistance = HashMap<Int, Int>()

    private fun aiTypeOf(unit: MapUnit) = UnitAiTypes.current(unit)

    /** CvPlayer::GetCityDistanceInPlots: how far the nearest city of ours is */
    private fun cityDistanceOf(tile: Tile): Int = cityDistance.getOrPut(tile.zeroBasedIndex) {
        civ.cities.minOfOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: 10
    }

    private fun domainOf(unit: Int) = w.units[unit].domain

    private fun attackEndsTurn(unit: Int, attacksLeft: Int): Boolean {
        val simUnit = w.units[unit]
        return !simUnit.canMoveAfterAttacking && !simUnit.unit.hasUnique(UniqueType.ExtraRangedAttack) && attacksLeft < 2
    }

    //region ScoreAttackDamage
    /**
     *  ScoreAttackDamage: how good it is for [unit], standing on plot [from] with what it has done so far, to attack the enemy on [enemyPlot];
     *  fills in [result] (type, damages, score). Not acceptable if the unit would take more than the aggression level allows.
     *  @return whether the attack kills (a unit, or takes a city)
     */
    fun scoreAttackDamage(pos: SimPosition, unit: SimUnitStats, from: Int, enemyPlot: Int, result: SimAssignment, overlay: DamageOverlay): Boolean {
        val simUnit = w.units[unit.index]
        val mu = simUnit.unit
        val aggression = w.aggression
        var aggressionBias = pos.aggressionBias
        val ranged = simUnit.ranged
        val attackerTile = w.plots[from]
        val enemyTile = w.plots[enemyPlot]
        val attackerHealth = mu.health - unit.selfDamage
        var cityDamageDealt = 0
        val unitDamageDealt = HashMap<Int, Int>()
        val previousUnitHitPoints = HashMap<Int, Int>()
        var damageReceived = 0
        var bonus = 0
        var previousCityHitPoints = 0
        var scoreReduction = false
        val canCapture = !mu.hasUnique(UniqueType.CannotCaptureCities, checkCivInfoUniques = true)

        val cityIndex = pos.enemyCityAt(enemyPlot)
        val enemyIndex = pos.enemyUnitAt(enemyPlot)
        if (cityIndex >= 0) {
            val sim = w.cities[cityIndex]
            val city = sim.city
            val previousCityDamage = overlay.city[cityIndex]
            result.cityIndex = cityIndex
            val previousGarrisonDamage = overlay.garrison[cityIndex]
            val key = w.cachedAttack(unit.index, from, -1, cityIndex, unit.selfDamage, previousGarrisonDamage, previousCityDamage) {
                val (dealt, taken, garrison) = w.damageOnCity(mu, attackerTile, attackerHealth, city, previousGarrisonDamage)
                intArrayOf(dealt, taken, garrison)
            }
            cityDamageDealt = key[0]
            damageReceived = key[1]
            result.garrisonDamage = key[2]
            previousCityHitPoints = (sim.health - previousCityDamage).coerceAtLeast(1)
            // A city that is surrounded cannot heal
            val blockaded = pos.adjacentFriendlies(enemyPlot, SimDomain.Both, -1) == w.passableNeighbors(enemyPlot)

            // But we do not want our melee units to die: take into account self damage and counterattacks
            if (!ranged) {
                if (cityDamageDealt >= previousCityHitPoints) {
                    // The unit that takes the city survives it with 1 HP (Battle.takeDamage)
                    damageReceived = min(damageReceived, attackerHealth - 1).coerceAtLeast(0)
                } else {
                    // With several units around the city take the damage of last turn into account: ideally ranged units start and melee joins in later
                    var remainingTurnsOnCity = previousCityHitPoints / (max(cityDamageDealt * aggressionBias, city.damageTakenLastTurn.toFloat()) + 1)
                    if (blockaded) remainingTurnsOnCity = max(0f, remainingTurnsOnCity - 1)
                    // Other units around can soak damage
                    val counterattack = if (city.canBombard()) w.cityStrike(city, mu, attackerTile, attackerHealth) else 0
                    val scaledCounterattack = counterattack / aggressionBias
                    val remainingTurnsOnAttacker = attackerHealth / (damageReceived + scaledCounterattack + 1)
                    // No attack if it is too early yet
                    val goodFirstAttack = mu.getMaxHealth() - mu.health < 13 && cityDamageDealt > damageReceived && damageReceived < 23
                    if (remainingTurnsOnAttacker < remainingTurnsOnCity && !goodFirstAttack) {
                        result.setImpossible()
                        return false
                    }
                }
            }
        } else if (enemyIndex >= 0) {
            val enemy = w.enemies[enemyIndex].unit
            val previousDamage = overlay.enemy[enemyIndex]
            val previousHitPoints = enemy.health - previousDamage
            previousUnitHitPoints[enemyIndex] = previousHitPoints
            val numbers = w.cachedAttack(unit.index, from, enemyIndex, -1, unit.selfDamage, previousDamage, 0) {
                // The quick and dirty method: the flanking of hypothetical units is added below
                val (dealt, taken) = w.damageOnUnit(mu, attackerTile, attackerHealth, enemy, enemyTile, previousHitPoints, ignoreFlanking = true)
                intArrayOf(dealt, taken)
            }
            var dealt = numbers[0]
            damageReceived = numbers[1]
            // Flanking affects strength, not damage, so the effect is not linear: just assume 10% per adjacent unit, and it works both ways
            if (!ranged) {
                val domain = domainOf(unit.index)
                val delta = pos.adjacentFriendlies(enemyPlot, domain, from) - pos.adjacentEnemies(from, domain)
                dealt += delta * dealt / 10
                damageReceived -= delta * damageReceived / 10
                if (mu.civ.gameInfo.ruleset.modOptions.constants.combatHealthStrengthLikeVp) {
                    val attackerCombatant = SimCombatant(mu, attackerTile, attackerHealth.coerceAtLeast(1))
                    val defenderCombatant = SimCombatant(enemy, enemyTile, previousHitPoints.coerceAtLeast(1))
                    val corrected = com.unciv.logic.battle.BattleDamage.resolveMeleeDeaths(attackerCombatant, defenderCombatant, dealt, damageReceived)
                    dealt = corrected.first
                    damageReceived = corrected.second
                }
            }
            // Do not be as aggressive when attacking embarked units
            if (!TacticalAttacks.canAttack(enemy) || enemy.isEmbarked()) aggressionBias /= 2
            // Do not be distracted by barbarians or melee units in another domain when there are real enemies around
            if (enemy.civ.isBarbarian || !enemy.baseUnit.isRanged() && w.enemies[enemyIndex].domain != domainOf(unit.index)) scoreReduction = true
            unitDamageDealt[enemyIndex] = dealt
        } else {
            result.setImpossible()
            return false
        }

        var totalUnitDamageDealt = 0
        val actualDamageDealt = HashMap<Int, Int>()
        var totalActualUnitDamageDealt = 0
        var cityKill = false
        var unitKill = false

        // A bonus for a kill; do not be too precise because of the randomness in the damage, if it does not work out we can try again
        if (cityIndex >= 0 && cityDamageDealt >= previousCityHitPoints) {
            scoreReduction = false
            // Only melee can capture a city
            if (!ranged && canCapture) cityKill = true
            else {
                if (previousCityHitPoints > 1) bonus += min(cityDamageDealt - previousCityHitPoints + 1, 3)
                cityDamageDealt = min(cityDamageDealt, previousCityHitPoints - 1)
            }
        }
        for ((index, damage) in unitDamageDealt) {
            val previousHitPoints = previousUnitHitPoints.getValue(index)
            if (damage > previousHitPoints) bonus += min(damage - previousHitPoints, 10)
            actualDamageDealt[index] = min(previousHitPoints, damage)
            totalUnitDamageDealt += damage
            totalActualUnitDamageDealt += min(previousHitPoints, damage)
            if (previousHitPoints > 0 && damage >= previousHitPoints) {
                if (enemyIndex == index) unitKill = true
                scoreReduction = false
                // A unit that heals by killing
                var heal = 0
                mu.forEachMatchingUnique(UniqueType.HealsAfterKilling, checkCivInfoUniques = true) { heal += it.params[0].toInt() }
                if (heal > 0) damageReceived = max(damageReceived - heal, -(mu.getMaxHealth() - mu.health))
            }
        }

        result.type = when {
            cityKill -> AssignmentType.MeleeKill
            unitKill -> if (ranged) AssignmentType.RangeKill
                else if (w.isOurCitadel(attackerTile) || previousCityHitPoints > 0) AssignmentType.MeleeKillNoAdvance else AssignmentType.MeleeKill
            else -> if (ranged) AssignmentType.RangeAttack else AssignmentType.MeleeAttack
        }
        result.selfDamage = damageReceived
        result.unitDamage = unitDamageDealt
        result.cityDamage = cityDamageDealt

        // For melee units check whether the damage received is worth it
        if (damageReceived > 0 && w.safePlotCount[unit.index] > 0) {
            var factor = DAMAGE_WEIGHT
            when (aggression) {
                AggressionLevel.Low -> factor *= 0.7f // want to do more damage than we take
                AggressionLevel.Medium -> factor *= 1.1f // accept slightly more damage than we deal
                AggressionLevel.High -> factor *= 2.3f // we check whether we can survive a counterattack in scoreCombatUnitTurnEnd
                AggressionLevel.Braveheart -> factor *= 4.2f // basically suicide
                AggressionLevel.None -> {
                    result.setImpossible()
                    return false
                }
            }
            // Danger as well: with many enemies around we need our melee units as shields, do not waste hit points on attacks
            val tileAfterAttack = if (result.type == AssignmentType.MeleeKill) enemyTile else attackerTile
            val danger = w.danger(unit.index, tileAfterAttack, unit.selfDamage + damageReceived, pos.damageWith(result))
            var belowHpLimit = danger > 100
            // Self damage of previous attacks is not considered here, as in the original
            if (mu.health - damageReceived < HP_LIMIT[aggression.ordinal]) belowHpLimit = true
            // The bias depends on the ratio of friendly to enemy units
            val scaledDamage = ((totalUnitDamageDealt + cityDamageDealt) * aggressionBias * factor + 0.5f).toInt()
            val damageDelta = scaledDamage - damageReceived
            val voluntaryCancel = belowHpLimit && damageDelta < 0 && result.type != AssignmentType.MeleeKill && result.type != AssignmentType.MeleeKillNoAdvance
            // In the real event there will be some randomness
            val suicideCancel = mu.health - damageReceived < 3 && aggression != AggressionLevel.Braveheart
            if (voluntaryCancel || suicideCancel) {
                result.setImpossible()
                return false
            }
        }

        // Finally the score: add the previous damage again and again to make concentrated fire attractive
        val actualCityDamage = min(cityDamageDealt, previousCityHitPoints + if (ranged) 2 else 10)
        var actualDamageTaken = min(damageReceived, attackerHealth)
        if (actualDamageTaken < 0) actualDamageTaken = max(actualDamageTaken, -(mu.getMaxHealth() - mu.health + unit.selfDamage))

        // Focus on low hit point units and cities
        val totalActualDamage = actualCityDamage + totalActualUnitDamageDealt
        if (totalActualDamage > 0) {
            val previousHitPoints = if (enemyIndex >= 0) previousUnitHitPoints.getValue(enemyIndex) else 0
            var hitPointsAfter = if (previousHitPoints > 0) previousHitPoints - actualDamageDealt.getValue(enemyIndex) else -1
            if (hitPointsAfter == -1 || hitPointsAfter > previousCityHitPoints - actualCityDamage) hitPointsAfter = previousCityHitPoints - actualCityDamage
            bonus += min(max(30 - hitPointsAfter, 0), totalActualDamage)
        }
        if (scoreReduction) bonus -= 10
        if (cityKill) bonus += 100 else if (unitKill) bonus += 15

        result.setScore(0, bonus, actualCityDamage + totalActualUnitDamageDealt + result.garrisonDamage - actualDamageTaken)
        return cityKill || unitKill
    }
    //endregion

    //region ScoreCombatUnitTurnEnd
    /**
     *  ScoreCombatUnitTurnEnd: how good it is for [unit] to end its turn on [plot], given the [danger] there: danger, friends around, citadels and
     *  cities, defense. [Int.MAX_VALUE] if it must not stay there (a death trap, too exposed for a ranged unit, too weak for the danger).
     *  @param relaxed assume friends that may catch up with the unit later; @param onlyCheckImpossible only decide whether it may stay, return 1 if it may
     */
    fun scoreCombatUnitTurnEnd(
        pos: SimPosition, unit: SimUnitStats, lastAssignment: AssignmentType, plot: Int, danger0: Int, domain: SimDomain, selfDamage: Int,
        mode: EvalMode, relaxed: Boolean, onlyCheckImpossible: Boolean,
    ): Int {
        var result = 0
        var danger = danger0
        val simUnit = w.units[unit.index]
        val mu = simUnit.unit
        val tile = w.plots[plot]
        // The danger value reflects the defensive terrain but is not very useful here: zones of control are unclear during the plan and freshly revealed enemies are not considered
        var adjacentFriendlies = if (mode == EvalMode.Final) pos.adjacentFriendliesEndTurn(plot, domain) else pos.adjacentFriendlies(plot, domain, -1)
        if (relaxed) adjacentFriendlies++
        val frontlineCitadelOrCity = (w.isOurCitadel(tile) || tile.isCityCenter()) && simUnit.domain == SimDomain.Land && pos.enemyDistance(plot) < 3

        // Do not do it if it is a death trap (unless there is no other choice)
        val adjacentEnemies = pos.adjacentEnemies(plot, SimDomain.Both)
        if (adjacentEnemies > 3 || adjacentEnemies == 3 && pos.aggressionBias < 1)
            if (!frontlineCitadelOrCity) return Int.MAX_VALUE

        val maxHitPoints = 100
        val currentHitPoints = mu.health - selfDamage

        // Unseen enemies might be hiding behind the edge, so assume danger there (unless it is a city)
        if (w.isEdgePlot(plot) && !tile.isCityCenter()) {
            // Siege units (with limited visibility) should not move there unless covered
            if (mu.getVisibilityRange() < 2 && pos.adjacentFriendlies(plot, simUnit.domain, -1) < 2) return Int.MAX_VALUE
            danger = max(maxHitPoints / 2, danger)
        }
        if (!onlyCheckImpossible) {
            // Check for cover and assume this would help us
            if (danger > 0 && (danger > maxHitPoints || !w.isEdgePlot(plot)) && pos.hasCoverFromOtherUnits(plot)) danger /= 2
        }

        val aiType = aiTypeOf(mu)
        val fragile = aiType == UnitAiType.CITY_BOMBARD || aiType == UnitAiType.CARRIER_SEA
        if (danger > 0) {
            // Avoid extreme danger, except in citadels
            if (!frontlineCitadelOrCity && w.aggression != AggressionLevel.Braveheart) {
                // Extra careful with ranged and siege units and carriers
                if ((aiType == UnitAiType.RANGED || fragile) && danger > currentHitPoints) return Int.MAX_VALUE
                // The minimum of hit points we want a standalone unit to have for the expected counterattacks
                val forKill = lastAssignment.isKill
                val couldFlee = w.safePlotCount[unit.index] > 0
                var magicNumber = if (forKill || !couldFlee) 23 else 42
                // If this is the only enemy
                if (pos.numEnemies == 1 && forKill) magicNumber = 12
                // If there is nothing we would cover or that covers us or we are low on health, do not do it
                if (currentHitPoints * currentHitPoints < magicNumber * danger) return Int.MAX_VALUE
            }
            if (!onlyCheckImpossible) {
                // Make it relative to the current hit points
                var scaledDanger = danger * DANGER_WEIGHT / max(1, currentHitPoints)
                // Danger values can get very high with many enemies around: normalize a bit, this maps 225 to 225 and flattens the higher values
                if (scaledDanger > 225) scaledDanger = 15 * sqrt(scaledDanger.toFloat()).toInt()
                // Be more careful with highly promoted units
                scaledDanger += (mu.promotions.XP * 100 - averageExperience100) / 200
                // No reason to run into the enemy alone
                if (adjacentFriendlies == 0) scaledDanger *= 2
                // A penalty for high danger plots
                result -= scaledDanger
            }
        }
        if (onlyCheckImpossible) return 1

        // Try to stay together, in pairs at least
        if (adjacentFriendlies > 0) result += if (fragile) adjacentFriendlies * 11 else 7 + adjacentFriendlies

        // Try to occupy enemy citadels
        val improvementDamage = w.enemyImprovementDamage(tile)
        if (improvementDamage > 0) result += improvementDamage / 3

        // Also occupy our own citadels
        if (frontlineCitadelOrCity) {
            result += if (mu.getRange() > 1 || pos.adjacentFriendlies(plot, SimDomain.Land, -1) == 0 || pos.adjacentEnemies(plot, SimDomain.Land) > 0) CITADEL_BONUS
                else CITADEL_BONUS / 2
        }

        // Try not to be a sitting duck
        if (simUnit.domain != (if (tile.isWater) SimDomain.Sea else SimDomain.Land)) result -= 3

        // Sometimes danger is zero, but maybe we are wrong, so look at the defense of the plot too
        result += (tile.getDefensiveBonus(unit = mu) * 100).toInt() / 5
        return result
    }
    //endregion

    //region Moves
    /** ScorePlotForMove: where a unit may end its move is only judged for combat units, the only ones simulated */
    fun scorePlotForMove(pos: SimPosition, unit: SimUnitStats, testPlot: Int, mode: EvalMode, overlay: DamageOverlay): SimAssignment =
        scorePlotForCombatUnitMove(pos, unit, testPlot, mode, overlay)

    /** ScorePlotForCombatUnitMove: the score of [unit] being on [testPlot], what the movement there means for it (distance to the enemy, capturing, danger, healing) */
    fun scorePlotForCombatUnitMove(pos: SimPosition, unit: SimUnitStats, testPlot: Int, mode: EvalMode, overlay: DamageOverlay): SimAssignment {
        val result = SimAssignment(AssignmentType.Move, unit.index, unit.plot, testPlot, unit.moves, unit.strategy, pos.previousPlotScore(unit.index))
        val simUnit = w.units[unit.index]
        val mu = simUnit.unit
        val tile = w.plots[testPlot]
        var dangerScore: Int
        var plotScore = 0
        var bonus = 0
        var damageDelta = 0
        var selfDamage = 0
        val currentHealth = mu.health - unit.selfDamage
        val moving = unit.plot != testPlot
        if (!moving) result.type = AssignmentType.FinishTemp
        // Ranged attacks are cross-domain
        val relevantDomain = if (simUnit.ranged) SimDomain.Both else if (tile.isWater) SimDomain.Sea else SimDomain.Land

        // Skirmishers go to the front line when they can attack
        var strategy = unit.strategy
        val skirmisher = unit.attacks > 0 && mu.getRange() == 1 && (unit.moves > 1f || !simUnit.canMoveAfterAttacking && unit.moves > 0f)
        if (skirmisher) strategy = MoveStrategy.FirstLine

        // The desirability by unit strategy and enemy distance, even for intermediate plots, so as not to bias against them
        if (pos.haveEnemies) {
            val ignoreLineOfSight = mu.hasUnique(UniqueType.IndirectFire, checkCivInfoUniques = true)
            val enemyDistance = if (!simUnit.ranged || ignoreLineOfSight || mu.getRange() < 2) pos.enemyDistance(testPlot, relevantDomain)
                else pos.rangedEnemyDistance(testPlot, relevantDomain)
            plotScore = (if (simUnit.domain != SimDomain.Sea) LAND_ATTACK else SEA_ATTACK)[strategy.ordinal][enemyDistance.coerceIn(0, MAX_DISTANCE)]
            if (tile.isCityCenter() && tile.getOwner() == civ) plotScore = 12
            // Wounded units and scouts can go wherever as long as they do not die (danger is checked later)
            else if (currentHealth < w.minHpForTactsim || UnitAiTypes.defaultOf(mu.baseUnit, w.rules) == UnitAiType.EXPLORE) plotScore = 12
            // Move skirmishers in to fire
            if (skirmisher && enemyDistance == 1) plotScore += 3
            // If we are not in a good spot, try moving
            if (plotScore <= 6 && tile == mu.getTile() && mu.getMaxHealth() - mu.health + unit.selfDamage < 10) plotScore -= 2
            // Move melee units in to capture cities
            if (!simUnit.ranged && pos.enemyDistance(testPlot) == 1) {
                for (n in w.neighbors[testPlot]) {
                    val city = pos.enemyCityAt(n)
                    if (city >= 0 && w.cities[city].health - overlay.city[city] <= 5) { plotScore += 5; break }
                }
            }
            // If we made a kill, assume we are in a good plot: enemy distance changes and we might end up with -1
            if (unit.last.isKill) plotScore = max(6, plotScore)
        } else {
            // No enemies around: prepare for the unexpected, melee units screening far from the target, other units closer
            val targetDistance = min(w.target.aerialDistanceTo(tile), MAX_DISTANCE)
            plotScore += ESCORT[strategy.ordinal][targetDistance]
        }

        // An unescorted civilian
        if (moving && pos.isEnemyCivilian(testPlot) && (unit.moves > 0f || pos.adjacentEnemies(testPlot, SimDomain.Land) == 0)) {
            val civilian = tile.civilianUnit
            if (civilian != null) {
                // Workers are not so important
                bonus += if (UnitAiTypes.defaultOf(civilian.baseUnit, w.rules) == UnitAiType.WORKER) 20 else 150
                result.type = AssignmentType.Capture // important so that the next assignment can be a move again
            }
        }

        // An empty barbarian camp
        if (moving && pos.isCampOpen(testPlot) && pos.enemyUnitAt(testPlot) < 0) {
            if (pos.isEarlyFinish(true)) plotScore = 12
            bonus += 100
        }

        // A bonus for potential fortifying and healing
        if (result.type == AssignmentType.FinishTemp) {
            if (unit.moves >= simUnit.maxMoves - SimPosition.EPSILON) {
                if (mu.getMaxHealth() - mu.health + unit.selfDamage > 0 && !mu.hasUnique(UniqueType.HealOnlyByPillaging, checkCivInfoUniques = true) && !mu.isEmbarked()) {
                    var healRate = mu.healingAmountForTile(tile, noTerrainDamage = true)
                    if (healRate > 0) {
                        healRate = min(healRate, mu.getMaxHealth() - mu.health + unit.selfDamage)
                        damageDelta += healRate
                        selfDamage -= healRate
                        result.type = AssignmentType.Heal
                    }
                }
                if (mu.canFortify(ignoreAlreadyFortified = true) && unit.strategy == MoveStrategy.FirstLine) plotScore += 1
            }
            val citadelDamage = w.adjacentImprovementDamage(testPlot)
            if (citadelDamage > 0) {
                val taken = min(currentHealth, citadelDamage)
                damageDelta -= taken
                selfDamage += taken
            }
        }

        val danger = w.danger(unit.index, tile, unit.selfDamage + selfDamage, overlay)
        // Many considerations are only relevant if we end the turn here (critical for skirmishers that can move after attacking)
        if (mode != EvalMode.Intermediate) {
            result.type = if (mode == EvalMode.Final) AssignmentType.Finish else AssignmentType.Initial
            dangerScore = scoreCombatUnitTurnEnd(pos, unit, unit.last, testPlot, danger, relevantDomain, unit.selfDamage + selfDamage, mode, false, false)
            if (dangerScore == Int.MAX_VALUE) {
                if (w.safePlotCount[unit.index] > 0) return result // do not do it
                // The unit has no safe plots and must end its turn somewhere: the worst possible arithmetic-safe value
                dangerScore = Short.MIN_VALUE.toInt()
            }
        } else {
            dangerScore = -danger / 2
            // Scale it up a bit to reduce the truncation error of the division
            val overkillPercent = 100 * danger / max(1, currentHealth)
            dangerScore -= overkillPercent / 20
            // A bonus for occupying a citadel even if it is just intermediate for now: units take turns soaking damage, so moving in is encouraged
            val frontlineCitadelOrCity = (w.isOurCitadel(tile) || tile.isCityCenter()) && simUnit.domain == SimDomain.Land && pos.enemyDistance(testPlot) < 3
            if (frontlineCitadelOrCity) {
                dangerScore += if (mu.getRange() > 1 || pos.adjacentFriendlies(testPlot, SimDomain.Land, unit.plot) == 0 || pos.adjacentEnemies(testPlot, SimDomain.Land) > 0)
                    CITADEL_BONUS else CITADEL_BONUS / 2
            } else if (w.enemyImprovementDamage(tile) >= 25) {
                // Citadels of the enemy: pillage them
                dangerScore += if (unit.moves > 0f) CITADEL_BONUS * 2 else CITADEL_BONUS
            }
        }

        result.selfDamage = selfDamage
        // A small bias for staying close to our cities, to have a way to retreat; and, with several identical units for a plot, prefer the one with most movement left
        var extra = max(10 - cityDistanceOf(tile), 0)
        extra += result.remainingMoves.toInt()
        result.setScore(plotScore * 10 + dangerScore + extra, bonus, damageDelta)
        return result
    }

    /** ScorePlotForRangedAttack: [unit] shooting at the enemy on [enemyPlot] from where it stands */
    fun scorePlotForRangedAttack(pos: SimPosition, unit: SimUnitStats, enemyPlot: Int, overlay: DamageOverlay): SimAssignment {
        val result = SimAssignment(AssignmentType.RangeAttack, unit.index, unit.plot, enemyPlot, unit.moves, unit.strategy, pos.previousPlotScore(unit.index))
        var bonus = 0
        // Received damage is zero here but the right unit number ratio is still used so as not to distort scores
        scoreAttackDamage(pos, unit, unit.plot, enemyPlot, result, overlay)
        if (!result.isAcceptable) return result

        // What happens next?
        if (attackEndsTurn(unit.index, unit.attacks)) result.remainingMoves = 0f
        else {
            result.remainingMoves = max(0f, result.remainingMoves - 1f)
            // A bonus the further we can disengage after attacking
            bonus += (result.remainingMoves * 2).toInt()
        }
        // A slight boost for attacking the real target
        if (enemyPlot == w.plotOf(w.target)) bonus += 2
        result.addScore(0, bonus, 0)
        return result
    }

    /** ScorePlotForMeleeAttack: [unit], on a plot next to the enemy on [enemyPlot], attacking it */
    fun scorePlotForMeleeAttack(pos: SimPosition, unit: SimUnitStats, enemyPlot: Int, overlay: DamageOverlay): SimAssignment {
        val result = SimAssignment(AssignmentType.MeleeAttack, unit.index, unit.plot, enemyPlot, 0f, unit.strategy, pos.previousPlotScore(unit.index))
        val simUnit = w.units[unit.index]
        val mu = simUnit.unit
        if (simUnit.ranged || !pos.isEnemyPlot(enemyPlot) || !w.canMeleeAttack(unit.index, w.plots[enemyPlot])) return result
        // How often we can attack this turn depends on the moves left
        val maxAttacks = max(0, min(kotlin.math.ceil(unit.moves - SimPosition.EPSILON).toInt(), unit.attacks))
        if (maxAttacks == 0) return result
        var bonus = 0

        scoreAttackDamage(pos, unit, unit.plot, enemyPlot, result, overlay)
        if (!result.isAcceptable) return result

        // What happens next? Capturing a city always ends the turn
        val enemyIsCity = pos.isEnemyCity(enemyPlot)
        val attackerTile = w.plots[unit.plot]
        val enemyTile = w.plots[enemyPlot]
        if (attackEndsTurn(unit.index, maxAttacks) || w.isSlowedByZoneOfControl(mu, attackerTile, enemyTile, pos.freed) ||
            enemyIsCity && result.type == AssignmentType.MeleeKill)
            // The cost of ending the turn will be checked in time, no need to panic yet
            result.remainingMoves = 0f
        else {
            val moveCost = MovementCost.getMovementCostBetweenAdjacentTiles(mu, attackerTile, enemyTile, false)
            result.remainingMoves = max(0f, unit.moves - max(moveCost, 1f))
        }

        // Do not break formation if there are many enemies around
        if (result.type == AssignmentType.MeleeKill && !enemyIsCity && pos.adjacentEnemies(enemyPlot, simUnit.domain) > 3 && result.remainingMoves <= 0f) {
            result.setImpossible()
            return result
        }

        // A slight boost for attacking the real target or a citadel
        val improvementDamage = w.enemyImprovementDamage(enemyTile)
        if (enemyPlot == w.plotOf(w.target)) bonus += 3
        else if (improvementDamage > 0) bonus += improvementDamage / 10

        // Combo bonus
        if (result.type == AssignmentType.MeleeKill && pos.isEnemyCivilian(enemyPlot)) {
            val civilian = enemyTile.civilianUnit
            if (civilian != null) bonus += if (UnitAiTypes.defaultOf(civilian.baseUnit, w.rules) == UnitAiType.WORKER) 20 else 150
        }
        // A barbarian camp
        if (result.type == AssignmentType.MeleeKill && pos.isCampOpen(enemyPlot)) bonus += 100

        result.addScore(0, bonus, 0)
        return result
    }

    /** ScorePlotForPillageMove: pillaging the improvement [unit] stands on; null if it cannot */
    fun scorePlotForPillage(pos: SimPosition, unit: SimUnitStats, testPlot: Int): SimAssignment? {
        val simUnit = w.units[unit.index]
        val mu = simUnit.unit
        val tile = w.plots[testPlot]
        if (unit.plot != testPlot || unit.moves <= 0f || !UnitActionsPillage.canPillage(mu, tile) || pos.hasPillageAssignment(testPlot))
            return null
        val result = SimAssignment(AssignmentType.Pillage, unit.index, unit.plot, testPlot, unit.moves, unit.strategy, pos.previousPlotScore(unit.index))
        var bonus = 0
        var damageDelta = 0
        var selfDamage = 0
        if (!mu.hasUnique(UniqueType.NoMovementToPillage, checkCivInfoUniques = true)) result.remainingMoves = max(0f, result.remainingMoves - 1f)
        val improvementDamage = w.enemyImprovementDamage(tile)
        if (improvementDamage > 0) bonus += improvementDamage
        val strategic = tile.tileResource?.resourceType == ResourceType.Strategic
        if (improvementDamage < 15 && strategic) bonus += 15
        else if (improvementDamage < 10 && tile.getOwner() != null && tile.roadStatus != RoadStatus.None && tile.isRoughTerrain()) bonus += 10
        if (tile.canPillageTileImprovement()) {
            val heal = min(25, mu.getMaxHealth() - mu.health + unit.selfDamage)
            if (heal > 0) {
                selfDamage -= heal
                damageDelta += heal
            }
        }
        result.selfDamage = selfDamage
        result.setScore(0, bonus, damageDelta)
        return result
    }
    //endregion

    companion object {
        /** COMBAT_AI_OFFENSE_DAMAGEWEIGHT / 100 and COMBAT_AI_OFFENSE_DANGERWEIGHT (both 100 in the Community Patch and Vox Populi) */
        private const val DAMAGE_WEIGHT = 1f
        private const val DANGER_WEIGHT = 100

        /** `int hpLimit[5] = {70,40,20,-5}` indexed by the aggression level, None first: Braveheart gets the zero a C array is filled up with */
        private val HP_LIMIT = intArrayOf(70, 40, 20, -5, 0)

        /** iPlotScoreForEnemyDistanceLandAttack: by move strategy (none, first, second, third line; the original's support row is not needed) and enemy distance 0 to 4 */
        private val LAND_ATTACK = arrayOf(
            intArrayOf(-1, -1, -1, -1, -1),
            intArrayOf(12, 12, 6, 1, -1), // first line (it is ok to evaluate the score in an enemy plot: a kill)
            intArrayOf(-1, 8, 12, 2, -1), // second line
            intArrayOf(1, 1, 8, 12, -1), // third line (ranged and damaged melee units)
        )
        private val SEA_ATTACK = arrayOf(
            intArrayOf(-1, -1, -1, -1, -1),
            intArrayOf(12, 8, 6, 1, -1),
            intArrayOf(-1, 8, 8, 6, -1),
            intArrayOf(1, 6, 8, 8, -1),
        )
        /** iPlotScoreForTargetDistanceEscort: by move strategy and target distance */
        private val ESCORT = arrayOf(
            intArrayOf(-1, -1, -1, -1, -1),
            intArrayOf(6, 6, 5, 3, 1), // first line can go anywhere but outside when in doubt
            intArrayOf(8, 8, 3, 2, 1), // second line prefers inside
            intArrayOf(10, 10, 2, 1, 1), // third line prefers inside even more
        )
    }
}

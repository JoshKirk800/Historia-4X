package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.automation.WaterBodies
import com.unciv.logic.automation.civilization.BeliefAiProfile
import com.unciv.logic.automation.civilization.DealAi
import com.unciv.logic.automation.civilization.MinorCivHelpers
import com.unciv.logic.automation.diplomacy.ApproachPart6Ai
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.PactPlanningAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.TraitCategory
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 *  Vox Populi's CvUnitProductionAI::CheckUnitBuildSanity (CvUnitProductionAI.cpp 172-1678) for the units a city chooses to train
 *  ([com.unciv.logic.automation.city.ConstructionAutomation]) and, with forPurchase, for the units a purchase chooser considers (bFree is
 *  false). [checkUnitBuildSanity] returns the SanityResult codes of [ProductionSanityAi] or the weight, in VP's order of tests.
 *
 *  A combat unit is one with a strength (CvUnitEntry::GetCombat / GetRangedCombat above 0); nuclear weapons have none in BNW's
 *  database, so they count as civilians here. Input VP has and BNW lacks counts as none: the unit columns of VP's database (ResourceType,
 *  CityAttackSupport, CultureBoost, GreatWorkUnit, NoSupply, BaseLandAirDefense, MoveAfterPurchase and the policy a unit unlocks,
 *  YieldOnCompletion, the Messenger unit), investments, the policy unit class production modifiers (no BNW policy has one), the
 *  AICITYSTRATEGY_ENOUGH_SETTLERS and NEW_CONTINENT_FEEDER city strategies, the settle plot quality (CvGame::GetCityQualityReference),
 *  the traits' expansion WLTKD and artifact yields, the archaeological dig tourism of policies, the German unit gifts, the trade route
 *  priorities of VP's trade route ranking ([com.unciv.logic.automation.unit.TradeRouteAutomation] is not VP's) and the
 *  MOD_AI_UNIT_PRODUCTION strategies (off in VP's
 *  database). The purchase terms are those of bForPurchase; VP's unit purchase cooldown and unit investments (MOD_BALANCE_UNIT_INVESTMENTS)
 *  have no BNW counterpart.
 */
object UnitProductionAi {

    /** A combat unit of VP: CvUnitEntry::GetCombat() > 0 or GetRangedCombat() > 0 (no nuclear weapon, whose BNW strength is 0) */
    fun isCombat(unit: BaseUnit): Boolean = unit.isMilitary && !unit.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals)

    /**
     *  CheckUnitBuildSanity for [unit] in [city]; [forOperation] when the unit fills an operation's slot; [flavorWeight] the flavor weight
     *  of the unit. A weight below 1 is SR_IMPOSSIBLE; the weight becomes sqrti(10 x weight) ([ProductionSanityAi.normalise]). In order:
     *  - puppets build no units; a city under siege trains combat units only; fewer than 2 buildings train none unless under siege; none whose
     *    upgrade some city of ours can train;
     *  - unless under siege, a combat unit with upkeep: refused (SR_MAINTENANCE) when the average unit upkeep would bankrupt us within 9
     *    turns ([WarStateAi.turnsToBankruptcy]), the average x 25 off within 16;
     *  - a combat land or sea unit needs a passable tile of its domain without a combat unit within 2 tiles of the city when the city has a
     *    garrison (SR_IMPOSSIBLE);
     *  - unit supply (SR_UNITSUPPLY): no land combat unit (explorers aside) once the land combat units, ours and those our cities are training,
     *    less the explorers, reach the recommended army ([MilitaryAi.recommendedLandArmySize]); no sea combat unit at the recommended navy;
     *    no explorer at the recommended explorers ([MilitaryAi.recommendedExplorers]);
     *  - city-states, combat units: no more than max(4, (era + 3) x cities) units; ships and land units, below the recommended navy or army
     *    (else SR_UNITSUPPLY), get 100 (navy; +100 when a major bullied us recently, 300 less 100 per ship while at most 2) or 150 (army;
     *    +250 likewise, 800 less 100 per unit while at most 2) per unit missing;
     *  - a combat land unit outside an operation in a city on a landmass of at most 3 tiles that a land unit garrisons is SR_USELESS;
     *  - not for city-states: a Nuclear unit +100, a self-destructing unit with ranged strength -50 while we have a nuclear weapon else +25;
     *    carriers only for an operation or under the need for air carriers ([EconomicMilitaryStrategyTests.needAirCarriers], else
     *    SR_BALANCE); a land explorer 500 per explorer missing; a combat ship needs a port ([isConnectedToOcean], SR_USELESS), then the ships
     *    missing x the era x (the war value, below) x 30, and 5 times that under NEED_NAVAL_UNITS_CRITICAL, twice under NEED_NAVAL_UNITS;
     *    a combat land unit the same with the army missing; air units: bombers and fighters at most 3 to 2 (SR_BALANCE), 2000 + 100 per
     *    free slot in the city, other air units 1000; units that intercept (land units with an intercept range) 50 per air-needy neighbor or
     *    enemy (a city per missing anti-air unit, a city when their air force matches ours); 50 for a sword unit's conquest strategy
     *    (GS_CONQUEST); spaceship parts SR_STRATEGY for an AI major, else 10000 under GS_SPACESHIP else 1000, 10000 when close to the
     *    victory else 2500, the city's spaceship production modifier x 100; a combat unit gets the faith it earns from kills over 5 (attack
     *    units), the enemy-city strength of the state religion's beliefs (attack, ranged, siege units) and the friendly-city strength
     *    (defense, counter units); trade units: SR_USELESS in a city of at most 4 citizens, -100 at war, +700 (+750 for a diplomat leader);
     *    +100 each (+100 for the matching leader traits) for units that start a golden age, hurry research, hurry culture, trade with
     *    city-states, buy city-states, hurry production and generals and admirals;
     *  - settlers: refused in a city that grows next turn or starves (CvCity::getFoodTurnsLeft at most 1), under ENOUGH_EXPANSION (SR_STRATEGY),
     *    in a city under the city strategy ENOUGH_SETTLERS (SR_BALANCE), with a settler in the city; else (EXPANSION of personality and grand
     *    strategy + the quality of the best settle plot when above 0 ([SettleAreas.settlePlotQualityMeasure]) + 100 in the early expansion
     *    phase + 50 for a NEW_CONTINENT_FEEDER city - 10 in an unhappy empire; a capital of a civilization with more than 2 cities - 25 +
     *    its settler production modifier) times 23, +10 per time settlers were skipped over ([MilitaryAi.numberOfTimesSettlerBuildSkippedOver]);
     *    at most 0 is SR_STRATEGY (VP's expansionist-trait WLTKD bonus has no counterpart);
     *  - archaeologists: SR_BALANCE without NEED_ARCHAEOLOGISTS; +5000 with none, +5000 going for a culture victory;
     *  - work boats: SR_STRATEGY unless our naval tactical zone is friendly; the boats needed (resources in our water tiles that need a boat,
     *    within 12 steps by water of the city, less the boats there and those other cities train) x (population + era) x 1000, SR_USELESS
     *    when none are needed; workers: SR_STRATEGY unless our land zone is friendly, SR_USELESS with a worker in the city or under
     *    ENOUGH_TILE_IMPROVERS; the workers missing (cities needing terrain improvements + 1 - workers) x 200 (WANT_TILE_IMPROVERS) and x 500
     *    (NEED_TILE_IMPROVERS), x 3 when more than 2 are missing;
     *  - for combat units: 100 per enemy while the war is of its domain's type, the city's threat at war (civilians: minus), 100 per time
     *    the operation units were skipped over; with more than one city 25 x cities in the best city for the unit type and again in the
     *    best city for its domain ([MilitaryCities], [UnitSanityCache]); +10 per free promotion of the civilization's uniques for its type (+100
     *    at most); 100 for a unique unit; 500 for an operation; its ranged strength; 200 when we have few of its unit AI ([UnitSanityCache.isUnderrepresented]);
     *    25 for a land unit in a city without a land garrison;
     *  - with at most 2 land units per city a land combat unit +300, other land units -300; at most as many ships as effective coastal
     *    cities likewise; under siege combat +500 else -500; ERADICATE_BARBARIANS +250 (_CRITICAL +500) for combat units; combat units: +100
     *    (+50 outside war) for what the city is already building, -100 when it builds another combat unit;
     *  - [forPurchase] (bForPurchase): first SR_IMPOSSIBLE unless the city may buy the unit for gold now (CvCity::IsCanPurchase: puppet,
     *    resistance, no room, not trainable, no price, too little gold); no puppet or fewer-than-2-buildings refusal; the city's current
     *    construction is neither taken out of the unit counts nor weighed; the work boat count includes the city itself and the bonus uses the
     *    capital's population less the steps to the nearest boat tile; a combat unit in a city under siege +250;
     *  - at the end a negative bonus is SR_UNKNOWN, else the weight plus the bonus, a fifth of it in an occupied city without a courthouse
     *    (a conquered, not puppeted, city), at least 1.
     */
    fun checkUnitBuildSanity(city: City, unit: BaseUnit, flavorWeight: Int, forOperation: Boolean, forPurchase: Boolean = false): Int {
        if (flavorWeight < 1) return ProductionSanityAi.SR_IMPOSSIBLE
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        val military = civ.ai.military
        military.ensureData(civ)
        var weight = ProductionSanityAi.normalise(flavorWeight)
        var bonus = 0

        val combat = isCombat(unit)
        val unitAi = UnitAiTypes.defaultOf(unit, rules)
        val minor = civ.isCityState
        val domainLand = unit.isLandUnit
        val domainSea = unit.isWaterUnit

        if (forPurchase) {
            // IsCanPurchase(bTestPurchaseCost, bTestTrainable, unit, gold): a puppet (barring the exceptions), resistance, no room for the unit,
            // not trainable, no gold price or not enough gold
            val cost = unit.getStatBuyCost(city, Stat.Gold) ?: return ProductionSanityAi.SR_IMPOSSIBLE
            if (!city.cityConstructions.isConstructionPurchaseAllowed(unit, Stat.Gold, cost)) return ProductionSanityAi.SR_IMPOSSIBLE
        } else if (city.isPuppet) return ProductionSanityAi.SR_IMPOSSIBLE
        val desperate = CityDangerAi.isUnderSiege(city)
        if (desperate && !combat) return ProductionSanityAi.SR_STRATEGY
        if (city.cityConstructions.getBuiltBuildings().count() < 2 && !desperate && !forPurchase) return ProductionSanityAi.SR_STRATEGY
        if (upgradeTrainableAnywhere(civ, unit)) return ProductionSanityAi.SR_STRATEGY

        if (!desperate && combat && !hasNoMaintenance(unit)) {
            val averageGoldPerUnit = civ.stats.getUnitMaintenance() / max(1, civ.units.getCivUnitsSize())
            val turnsLeft = WarStateAi.turnsToBankruptcy(civ, averageGoldPerUnit)
            if (turnsLeft < 9) return ProductionSanityAi.SR_MAINTENANCE
            else if (turnsLeft < 17) bonus -= averageGoldPerUnit * 25
        }

        if (combat && city.getCenterTile().militaryUnit != null && !unit.isAirUnit() && !hasRoomNearby(city, unit))
            return ProductionSanityAi.SR_IMPOSSIBLE

        var numExplorers = UnitAiTypes.numUnitsWithDefaultUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = true)
        var numLandUnits = civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isLandUnit }
        var numSeaUnits = civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isWaterUnit }
        for (other in civ.cities) {
            val training = other.cityConstructions.getCurrentConstruction() as? BaseUnit ?: continue
            if (!training.isMilitary) continue
            if (training.isLandUnit) numLandUnits++ else if (training.isWaterUnit) numSeaUnits++
        }
        // eCurrentlyProducing is read only when !bForPurchase
        val current = if (forPurchase) null else city.cityConstructions.getCurrentConstruction() as? BaseUnit
        if (current != null) when {
            current.isLandUnit && UnitAiTypes.defaultOf(current, rules) == UnitAiType.EXPLORE -> numExplorers--
            current.isLandUnit && current.isMilitary -> numLandUnits--
            current.isWaterUnit && current.isMilitary -> numSeaUnits--
        }
        val numTotalUnits = numLandUnits + numSeaUnits
        numLandUnits -= numExplorers

        if (combat) {
            if (domainLand && unitAi != UnitAiType.EXPLORE && numLandUnits >= military.recommendedLandArmySize) return ProductionSanityAi.SR_UNITSUPPLY
            else if (domainSea && numSeaUnits >= military.recommendedNavySize) return ProductionSanityAi.SR_UNITSUPPLY
            else if (unitAi == UnitAiType.EXPLORE && numExplorers >= military.recommendedExplorers) return ProductionSanityAi.SR_UNITSUPPLY
        }

        val atWar = WarStateAi.numberCivsAtWarWith(civ) > 0
        if (minor && combat) {
            if (numTotalUnits > max(4, (civ.getEraNumber() + 3) * max(1, civ.cities.size))) return ProductionSanityAi.SR_UNITSUPPLY
            if (domainSea) {
                if (numSeaUnits <= 2) bonus += max(0, 300 - numSeaUnits * 100)
                val limit = military.recommendedNavySize
                if (numSeaUnits < limit) {
                    bonus += (limit - numSeaUnits) * 100
                    if (MinorCivHelpers.isRecentlyBulliedByAnyMajor(civ)) bonus += 100
                } else return ProductionSanityAi.SR_UNITSUPPLY
            } else if (domainLand) {
                if (numLandUnits <= 2) bonus += max(0, 800 - numLandUnits * 100)
                val limit = military.recommendedLandArmySize
                if (numLandUnits < limit) {
                    bonus += (limit - numLandUnits) * 150
                    if (MinorCivHelpers.isRecentlyBulliedByAnyMajor(civ)) bonus += 250
                } else return ProductionSanityAi.SR_UNITSUPPLY
            }
        }

        val garrison = city.getCenterTile().militaryUnit
        if (combat && domainLand && !forOperation && garrison?.baseUnit?.isLandUnit == true) {
            val centerTile = city.getCenterTile()
            if ((civ.gameInfo.tileMap.continentSizes[centerTile.getContinent()] ?: Int.MAX_VALUE) <= 3) return ProductionSanityAi.SR_USELESS
        }

        if (!minor) {
            // Aluminum kept for the spaceship: no Aluminum unit (other than a spaceship part) while our own Aluminum is no more than the
            // parts and the core cities' buildings still need
            if (!unit.hasUnique(UniqueType.SpaceshipPart, GameContext.IgnoreConditionals) && SpaceshipProductionAi.plansSpaceship(civ)) {
                val aluminum = civ.gameInfo.ruleset.tileResources[SpaceshipProductionAi.ALUMINUM]
                if (aluminum != null && unit.getResourceRequirementsPerTurn(city.state)[SpaceshipProductionAi.ALUMINUM] > 0 &&
                    DealAi.numResourceAvailable(civ, aluminum, includeImport = false) <=
                    SpaceshipProductionAi.aluminumStillNeededForSpaceship(civ) + SpaceshipProductionAi.aluminumStillNeededForCoreCities(civ))
                    return ProductionSanityAi.SR_STRATEGY
            }

            if (unitAi == UnitAiType.CITY_SPECIAL) {
                var num = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.CITY_SPECIAL, includeBeingTrained = true, includeWater = true)
                if (current != null && UnitAiTypes.defaultOf(current, rules) == UnitAiType.CITY_SPECIAL) num--
                val numArmies = numLandUnits / 6
                if (num < numArmies) {
                    bonus += 2000 * max(3 - num, 1)
                    if (forOperation) bonus += 10000
                }
            }

            if (unit.hasUnique(UniqueType.SelfDestructs, GameContext.IgnoreConditionals) || isNuclear(unit)) {
                if (isNuclear(unit)) bonus += 100
                else if (unit.rangedStrength > 0 && civ.units.getCivUnits().any { it.isNuclearWeapon() }) bonus -= 50
                else bonus += 25
            }

            if (unitAi == UnitAiType.CARRIER_SEA && !forOperation && !EconomicMilitaryStrategyTests.needAirCarriers(civ))
                return ProductionSanityAi.SR_BALANCE

            if (domainLand && unitAi == UnitAiType.EXPLORE) {
                val explorersMissing = military.recommendedExplorers - numExplorers
                if (explorersMissing > 0) bonus += explorersMissing * 500
            }

            if (domainSea) {
                if (combat) {
                    if (!isConnectedToOcean(city)) return ProductionSanityAi.SR_USELESS
                    var value = military.recommendedNavySize - numSeaUnits
                    value *= max(1, civ.getEraNumber())
                    if (value > 0 && !civ.isBarbarian) {
                        if (military.navalDefenseState == DefenseState.Critical) value *= 5
                        else if (military.navalDefenseState == DefenseState.Needed) value *= 2
                        val warValue = warValue(civ, WarFocus.Sea)
                        if (warValue > 0) value *= warValue
                    }
                    bonus += value * 30
                }
            } else if (domainLand) {
                if (combat) {
                    var value = military.recommendedLandArmySize - numLandUnits
                    value *= max(1, civ.getEraNumber())
                    if (value > 0 && !civ.isBarbarian) {
                        var warValue = warValue(civ, WarFocus.Land)
                        if (unitAi == UnitAiType.DEFENSE || unitAi == UnitAiType.COUNTER || unitAi == UnitAiType.ATTACK) {
                            for (settler in city.getCenterTile().getUnits()) {
                                if (!settler.hasUnique(UniqueType.FoundCity)) continue
                                warValue += if (civ.ai.operations.armyOf(settler) == null) 2 else 1
                            }
                        }
                        if (warValue > 0) value *= warValue
                    }
                    bonus += value * 30
                }
            }

            if (unit.isAirUnit()) {
                val ourBombers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.ATTACK_AIR, includeBeingTrained = true, includeWater = true)
                val ourFighters = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.DEFENSE_AIR, includeBeingTrained = true, includeWater = true)
                val emptySlots = city.getMaxAirUnits() - city.getCenterTile().airUnits.size
                when (unitAi) {
                    UnitAiType.ATTACK_AIR -> {
                        if (ourBombers * 2 > ourFighters * 3) return ProductionSanityAi.SR_BALANCE
                        bonus += 2000 + emptySlots * 100
                    }
                    UnitAiType.DEFENSE_AIR -> {
                        if (ourFighters * 2 > ourBombers * 3) return ProductionSanityAi.SR_BALANCE
                        bonus += 2000 + emptySlots * 100
                    }
                    else -> bonus += 1000
                }
            }

            if (domainLand && unit.interceptRange > 0) bonus += airDefenseNeeded(civ) * 50

            if (unit.strength > 0 && civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_GS_CONQUEST")) bonus += 50

            if (unit.hasUnique(UniqueType.SpaceshipPart, GameContext.IgnoreConditionals)) {
                // The AI plans its spaceship parts in SpaceshipProductionAi (CvPlayerAI::AI_doSpaceshipAndUtopiaProduction)
                if (!civ.isHuman()) return ProductionSanityAi.SR_STRATEGY
                bonus += if (civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_GS_SPACESHIP")) 10000 else 1000
                bonus += if (VictoryProgressAi.scienceVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD)) 10000 else 2500
                bonus += spaceProductionModifier(city, unit) * 100
                if (bonus <= 0) bonus = 1
            }

            if (unit.strength > 0) bonus += religiousBonus(civ, unit)

            if (unit.hasUnique(UniqueType.EstablishTradeRoutes, GameContext.IgnoreConditionals)) {
                if (city.population.population <= 4) return ProductionSanityAi.SR_USELESS
                if (atWar) bonus -= 100
                bonus += if (civ.ai.hasTraitCategory(TraitCategory.Diplomat)) 750 else 700
            }
            bonus += greatPersonBonus(civ, unit, unitAi)
        }

        if (unitAi == UnitAiType.SETTLE) {
            if (foodTurnsLeft(city) <= 1) return ProductionSanityAi.SR_STRATEGY
            if (civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")) return ProductionSanityAi.SR_STRATEGY
            if (city.ai.isCityStrategyActive(CityStrategyAi.ENOUGH_SETTLERS)) return ProductionSanityAi.SR_BALANCE
            if (city.getCenterTile().getUnits().any { it.civ == civ && UnitAiTypes.current(it) == UnitAiType.SETTLE }) return ProductionSanityAi.SR_USELESS
            var flavorExpansion = civ.ai.personalityAndGrandStrategy(Flavor.Expansion)
            // We already checked ENOUGH_EXPANSION, so we know we have a good settle plot, but let's bump the priority by the quality of the plot
            val quality = SettleAreas.settlePlotQualityMeasure(civ, SettleAreas.bestSettlePlot(civ, null, null))
            if (quality > 0) flavorExpansion += quality
            // Strategies affect unit flavors but unfortunately we largely ignore the "pre" score from the flavor system
            if (civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.EARLY_EXPANSION)) flavorExpansion += 100
            if (city.ai.isCityStrategyActive(CityStrategyAi.NEW_CONTINENT_FEEDER)) flavorExpansion += 50
            if (EconomicMilitaryStrategyTests.isEmpireUnhappy(civ)) flavorExpansion -= 10
            if (city.isCapital() && civ.cities.size > 2) {
                flavorExpansion -= 25
                flavorExpansion += capitalSettlerProductionModifier(civ, unit)
            }
            if (flavorExpansion <= 0) return ProductionSanityAi.SR_STRATEGY
            flavorExpansion += military.numberOfTimesSettlerBuildSkippedOver * 10
            bonus += flavorExpansion * 23
        }

        if (!minor && unitAi == UnitAiType.ARCHAEOLOGIST) {
            if (!civ.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_NEED_ARCHAEOLOGISTS")) return ProductionSanityAi.SR_BALANCE
            if (UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.ARCHAEOLOGIST, includeBeingTrained = true, includeWater = true) <= 0) bonus += 5000
            if (civ.ai.isGoingForCultureVictory()) bonus += 5000
        }

        val tacticalMap = civ.ai.tactical.lastMap
        if (unitAi == UnitAiType.WORKER_SEA) {
            val zone = tacticalMap?.zoneByCity(city, true)
            if (zone != null && zone.dominance != TacticalDominance.Friendly) return ProductionSanityAi.SR_STRATEGY
            val (needed, closestDistance) = workBoatsNeeded(city, forPurchase)
            if (needed > 0) {
                if (!forPurchase) bonus += 1000 * needed * (city.population.population + civ.getEraNumber())
                else bonus += 1000 * needed * ((civ.getCapital() ?: city).population.population + civ.getEraNumber()) - closestDistance
            } else return ProductionSanityAi.SR_USELESS
        }
        if (unitAi == UnitAiType.WORKER) {
            val zone = tacticalMap?.zoneByCity(city, false)
            if (zone != null && zone.dominance != TacticalDominance.Friendly) return ProductionSanityAi.SR_STRATEGY
            if (city.getCenterTile().getUnits().any { it.civ == civ && UnitAiTypes.current(it) == UnitAiType.WORKER }) return ProductionSanityAi.SR_USELESS
            val numBuilders = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true)
            val missingBuilders = civ.ai.citiesNeedingTerrainImprovements + 1 - numBuilders // one for roads
            val scale = if (missingBuilders > 2) 3 else 1
            if (city.ai.isCityStrategyActive("AICITYSTRATEGY_ENOUGH_TILE_IMPROVERS")) return ProductionSanityAi.SR_USELESS
            if (city.ai.isCityStrategyActive("AICITYSTRATEGY_WANT_TILE_IMPROVERS")) bonus += 200 * missingBuilders * scale
            if (city.ai.isCityStrategyActive("AICITYSTRATEGY_NEED_TILE_IMPROVERS")) bonus += 500 * missingBuilders * scale
        }

        if (!minor) {
            if (combat) {
                var warBooster = 0
                val warType = military.overallWarType(civ)
                if (warType == WarFocus.Land && domainLand || warType == WarFocus.Sea && domainSea) warBooster += enemiesOf(civ).size
                bonus += warBooster * 100
            }
            if (atWar) {
                val threat = CityThreat.threatValue(city)
                if (combat) bonus += threat else bonus -= threat
            }
            if (combat && forOperation && military.numberOfTimesOpsBuildSkippedOver > 0)
                bonus += military.numberOfTimesOpsBuildSkippedOver * 100

            if (combat && civ.cities.size > 1) {
                val combatClass = UnitSanityCache.combatClassOf(civ, unit)
                if (combatClass != null && city == UnitSanityCache.bestCity(civ, combatClass)) bonus += 25 * civ.cities.size
                if (city == UnitSanityCache.bestCity(civ, UnitSanityCache.domainOf(unit))) bonus += 25 * civ.cities.size
            }

            if (combat) {
                var promotionBonus = 0
                for (unique in civ.getMatchingUniques(UniqueType.UnitStartingPromotions)) {
                    if (!unit.matchesFilter(unique.params[0])) continue
                    promotionBonus += 10
                    if (promotionBonus >= 100) break
                }
                bonus += promotionBonus
            }
            if (unit.uniqueTo != null) bonus += 100

            if (combat) {
                if (forOperation) bonus += 500
                if (unit.rangedStrength > 0) bonus += unit.rangedStrength
                if (UnitSanityCache.isUnderrepresented(civ, unitAi)) bonus += 200
                if (domainLand && garrison?.baseUnit?.isLandUnit != true) bonus += 25
            }
        }

        if (numLandUnits <= civ.cities.size * 2 && domainLand) bonus += if (combat) 300 else -300
        if (numSeaUnits <= PactPlanningAi.numEffectiveCoastalCities(civ) && domainSea) bonus += if (combat) 300 else -300
        if (desperate) bonus += if (combat) 500 else -500

        if (combat) {
            if (civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")) bonus += 250
            else if (civ.ai.isMilitaryStrategyActive("MILITARYAISTRATEGY_ERADICATE_BARBARIANS_CRITICAL")) bonus += 500

            if (current != null) {
                if (unit == current && atWar) bonus += 100
                else if (unit == current) bonus += 50
                else if (isCombat(current)) bonus -= 100
            }
            // prioritize besieged cities for purchase
            if (forPurchase && desperate) bonus += 250
        }

        if (bonus < 0) return ProductionSanityAi.SR_UNKNOWN
        weight += bonus
        if (isOccupied(city)) weight /= 5
        return max(1, weight)
    }

    private fun isNuclear(unit: BaseUnit) = unit.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals)

    /** CvUnitEntry::IsNoMaintenance: the unit costs no upkeep ("[-100]% maintenance costs") */
    private fun hasNoMaintenance(unit: BaseUnit): Boolean =
        unit.getMatchingUniques(UniqueType.UnitMaintenanceDiscount, GameContext.IgnoreConditionals).sumOf { it.params[0].toInt() } <= -100

    /** CvCity::IsOccupied and not IsNoOccupiedUnhappiness: a conquered city (not a puppet) without a building that removes the annexed unhappiness */
    private fun isOccupied(city: City): Boolean =
        city.isOccupied() && !city.cityConstructions.builtBuildingUniqueMap.hasUnique(UniqueType.RemovesAnnexUnhappiness, city.state)

    /** CvPlayer::GetPlayersAtWarWith: the living civilizations (city-states included, barbarians not) we are at war with */
    private fun enemiesOf(civ: Civilization): List<Civilization> =
        civ.gameInfo.civilizations.filter { it.isAlive() && !it.isBarbarian && civ.isAtWarWith(it) }

    /**
     *  The "war value" of the naval and land need: per enemy except city-states 4 when the war against it is of the type [focus] and
     *  it is one of our unfriendly majors ([ApproachPart6Ai.isPotentialMilitaryTargetOrThreat]), 2 when only of that type, else 1,
     *  divided by the number of enemies (city-states included); +10 (sea) or +15 (land) when our overall war is of that type
     *  ([MilitaryAi.warType]).
     */
    private fun warValue(civ: Civilization, focus: WarFocus): Int {
        val military = civ.ai.military
        val enemies = enemiesOf(civ)
        var value = 0
        for (enemy in enemies) {
            if (enemy.isCityState) continue
            value += if (military.warType(enemy) != focus) 1
                else if (enemy.isMajorCiv && enemy.cities.isNotEmpty() && ApproachPart6Ai.isPotentialMilitaryTargetOrThreat(civ, enemy)) 4
                else 2
        }
        value /= max(1, enemies.size)
        if (military.overallWarType(civ) == focus) value += if (focus == WarFocus.Sea) 10 else 15
        return value
    }

    /**
     *  The air defense need: for each valid major that is our neighbor or at war with us and has air units: a city of ours when we have
     *  fewer anti-air units than cities, a city when its air units are at least ours.
     */
    private fun airDefenseNeeded(civ: Civilization): Int {
        val military = civ.ai.military
        fun airUnits(of: Civilization) =
            UnitAiTypes.numUnitsWithUnitAi(of, UnitAiType.ATTACK_AIR, includeBeingTrained = false, includeWater = true) +
                UnitAiTypes.numUnitsWithUnitAi(of, UnitAiType.DEFENSE_AIR, includeBeingTrained = false, includeWater = true)
        val ourAir = airUnits(civ)
        var needed = 0
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !WarStateAi.isPlayerValid(civ, other)) continue
            if (DiplomacyTurn.proximity(civ, other) != DiplomacyTurn.PROXIMITY_NEIGHBORS && !civ.isAtWarWith(other)) continue
            val theirAir = airUnits(other)
            if (theirAir == 0) continue
            if (military.numAntiAirUnits < civ.cities.size) needed += civ.cities.size
            if (theirAir >= ourAir) needed += civ.cities.size
        }
        return needed
    }

    /** CvCity::getSpaceProductionModifier: what the city's buildings add to the production of [unit] (a spaceship part) */
    private fun spaceProductionModifier(city: City, unit: BaseUnit): Int {
        var total = 0
        for (building in city.cityConstructions.getBuiltBuildings())
            for (unique in building.getMatchingUniques(UniqueType.PercentProductionUnits, city.state))
                if (unit.matchesFilter(unique.params[1]) && city.matchesFilter(unique.params[2])) total += unique.params[0].toInt()
        return total
    }

    /**
     *  The religion terms of a combat unit: for each belief of the state religion ([OpinionAi.stateReligion]; a pantheon does not count)
     *  the faith earned from kills over 5 for units that may take the attack or fast attack unit AI, the strength against enemy cities for
     *  units of the attack, ranged, fast attack or city bombard AI, the strength in friendly cities for the defense and counter AI.
     *  The belief's land bonuses and yields from kills are VP-only.
     */
    private fun religiousBonus(civ: Civilization, unit: BaseUnit): Int {
        val religion = OpinionAi.stateReligion(civ)?.first ?: return 0
        val rules = civ.gameInfo.ruleset.ai
        fun canHave(vararg types: UnitAiType) = types.any { UnitAiTypes.canHave(unit, it, rules) }
        var bonus = 0
        for (belief in religion.getAllBeliefsOrdered()) {
            val profile = BeliefAiProfile.of(belief, civ)
            if (profile.faithFromKills > 0 && canHave(UnitAiType.ATTACK, UnitAiType.FAST_ATTACK)) bonus += profile.faithFromKills / 5
            if (profile.combatModifierEnemyCities > 0 &&
                canHave(UnitAiType.ATTACK, UnitAiType.RANGED, UnitAiType.FAST_ATTACK, UnitAiType.CITY_BOMBARD)) bonus += profile.combatModifierEnemyCities
            if (profile.combatModifierFriendlyCities > 0 && canHave(UnitAiType.DEFENSE, UnitAiType.COUNTER)) bonus += profile.combatModifierFriendlyCities
        }
        return bonus
    }

    /**
     *  The great person terms: +100 for a unit that starts a golden age, hurries research, hurries culture (a great writer), trades with
     *  city-states (BaseGold), hurries production, and for the general and admiral unit AI; +100 more when the leader's traits fit (golden
     *  age: tourism or expansionist; research: nerd; culture: tourism; trade: diplomat; production: smaller or warmonger; general:
     *  expansionist or warmonger; admiral: diplomat or warmonger). VP's buy-a-city-state and great work flags have no BNW unit data.
     */
    private fun greatPersonBonus(civ: Civilization, unit: BaseUnit, unitAi: UnitAiType): Int {
        fun has(vararg categories: TraitCategory) = categories.any { civ.ai.hasTraitCategory(it) }
        fun uniqueBonus(type: UniqueType, vararg categories: TraitCategory) =
            if (unit.hasUnique(type, GameContext.IgnoreConditionals)) 100 + if (has(*categories)) 100 else 0 else 0
        var bonus = uniqueBonus(UniqueType.OneTimeEnterGoldenAgeTurns, TraitCategory.Tourism, TraitCategory.Expansionist)
        bonus += uniqueBonus(UniqueType.CanHurryResearch, TraitCategory.Nerd)
        bonus += uniqueBonus(UniqueType.CanHurryPolicy, TraitCategory.Tourism)
        bonus += uniqueBonus(UniqueType.CanTradeWithCityStateForGoldAndInfluence, TraitCategory.Diplomat)
        bonus += uniqueBonus(UniqueType.CanSpeedupConstruction, TraitCategory.Smaller, TraitCategory.Warmonger)
        if (unitAi == UnitAiType.GENERAL) bonus += 100 + if (has(TraitCategory.Expansionist, TraitCategory.Warmonger)) 100 else 0
        if (unitAi == UnitAiType.ADMIRAL) bonus += 100 + if (has(TraitCategory.Diplomat, TraitCategory.Warmonger)) 100 else 0
        return bonus
    }

    /**
     *  CvPlayer::getCapitalSettlerProductionModifier: the production bonus of settlers in the capital the civilization's policies give
     *  (BNW's Collective Rule), as the unique "[amount]% Production when constructing [Settler] units [in capital]".
     */
    private fun capitalSettlerProductionModifier(civ: Civilization, settler: BaseUnit): Int =
        civ.getMatchingUniques(UniqueType.PercentProductionUnits)
            .filter { it.params[2].equals("in capital", ignoreCase = true) && settler.matchesFilter(it.params[1]) }
            .sumOf { it.params[0].toInt() }

    /**
     *  CvCity::getFoodTurnsLeft: the turns until the city grows (rounded up), 0 when it is over the threshold, minus the turns until it
     *  starves, a large number (the food it still needs) when its food does not change.
     */
    private fun foodTurnsLeft(city: City): Int {
        val deltaPerTurn = (city.cityStats.currentCityStats.food * 100).roundToInt()
        val foodStored = city.population.foodStored * 100
        val foodNeededToGrow = city.population.getFoodToNextPopulation() * 100 - foodStored
        if (deltaPerTurn > 0) {
            if (foodNeededToGrow <= 0) return 0
            var turnsLeft = foodNeededToGrow / deltaPerTurn
            if (turnsLeft * deltaPerTurn < foodNeededToGrow) turnsLeft++
            return turnsLeft
        }
        if (deltaPerTurn < 0) {
            var turnsLeft = foodStored / deltaPerTurn
            if (turnsLeft * deltaPerTurn < foodStored) turnsLeft++
            return -turnsLeft
        }
        return foodNeededToGrow
    }

    /**
     *  The work boats the city's waters need: the resources in our unimproved water tiles within 12 steps by water of the city (not under
     *  an owner who is being razed, bonus resources only in the work range of the city that owns the tile) that a work boat can improve
     *  ([needsWorkBoat]), less each work boat of ours in those tiles and each other city of ours there that trains one (the city itself too
     *  for a purchase). Also returns the steps to the nearest tile counted (Int.MAX_VALUE when none).
     */
    private fun workBoatsNeeded(city: City, forPurchase: Boolean): Pair<Int, Int> {
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        val start = city.getCenterTile()
        val distance = HashMap<Tile, Int>()
        distance[start] = 0
        val queue = ArrayDeque<Tile>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val tile = queue.removeFirst()
            val steps = distance.getValue(tile)
            if (steps >= WORK_BOAT_REACH) continue
            for (neighbor in tile.neighbors) {
                if (neighbor in distance || !civ.hasExplored(neighbor) || !isValidMovePlot(civ, neighbor)) continue
                if (!(neighbor.isWater || neighbor.isCityCenter() && neighbor.getOwner() == civ)) continue
                if (neighbor.isOcean && !civ.tech.embarkedUnitsCanEnterOcean) continue
                distance[neighbor] = steps + 1
                queue.add(neighbor)
            }
        }
        var needed = 0
        var closestDistance = Int.MAX_VALUE
        val needsBoat = HashMap<TileResource, Boolean>()
        for (tile in distance.keys) {
            for (unit in tile.getUnits())
                if (unit.civ == civ && UnitAiTypes.current(unit) == UnitAiType.WORKER_SEA) needed--
            val tileCity = tile.getCity()
            if (tile.isCityCenter() && tileCity != null && (tileCity != city || forPurchase) && tileCity.civ == civ) {
                val training = tileCity.cityConstructions.getCurrentConstruction() as? BaseUnit
                if (training != null && UnitAiTypes.defaultOf(training, rules) == UnitAiType.WORKER_SEA) needed--
            }
            if (tile.improvement != null || tile.getOwner() != civ) continue
            val owningCity = tileCity ?: continue
            if (owningCity.isBeingRazed) continue
            val resource = tile.tileResource ?: continue
            if (!civ.canSeeResource(resource)) continue
            if (!needsBoat.getOrPut(resource) { needsWorkBoat(civ, resource, tile) }) continue
            if (resource.resourceType == ResourceType.Bonus && tile !in owningCity.tilesInRange) continue
            closestDistance = minOf(closestDistance, distance.getValue(tile))
            needed++
        }
        return needed to closestDistance
    }

    /** CvPlayer::NeedWorkboatToImproveResource: a water improvement that is no great person's connects [resource] and we can build it */
    private fun needsWorkBoat(civ: Civilization, resource: TileResource, tile: Tile): Boolean =
        resource.getImprovements().any { name ->
            val improvement = tile.ruleset.tileImprovements[name] ?: return@any false
            tile.isWater && !improvement.isGreatImprovement() &&
                tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, tile = tile))
        }

    private const val WORK_BOAT_REACH = 12

    /** CvPlayer::GetUnitClassTrainingAllowedAnywhere against CvUnitEntry::GetUpgradeUnitClass: our version of an upgrade of [unit] can be trained in one of our cities */
    private fun upgradeTrainableAnywhere(civ: Civilization, unit: BaseUnit): Boolean =
        unit.getRulesetUpgradeUnits(civ.state).any { upgrade ->
            val ours = civ.getEquivalentUnit(upgrade)
            civ.cities.any { ours.isBuildable(it.cityConstructions) }
        }

    /**
     *  The no-place-to-put-them check: a tile within 2 of the city, of the unit's domain (water for ships, land for land units), that we may
     *  move onto (CvPlot::isValidMovePlot: passable or on an intact road; not the territory of a major that closes its borders to us and is
     *  not at war with us) and that holds no combat unit.
     */
    private fun hasRoomNearby(city: City, unit: BaseUnit): Boolean {
        val civ = city.civ
        return city.getCenterTile().getTilesInDistance(2).any { tile ->
            tile.militaryUnit == null && isValidMovePlot(civ, tile)
                && (tile.isWater && unit.isWaterUnit || tile.isLand && unit.isLandUnit)
        }
    }

    private fun isValidMovePlot(civ: Civilization, tile: Tile): Boolean {
        if (tile.isImpassible() && (tile.roadStatus == RoadStatus.None || tile.roadIsPillaged)) return false
        val owner = tile.getOwner() ?: return true
        return owner == civ || owner.isCityState || civ.diplomacyFunctions.canPassThroughTiles(owner)
    }

    /**
     *  CvCity::IsConnectedToOcean (UpdateConnectedToOcean): a water body next to the city of more than 50 tiles, or one other civilizations
     *  have cities on as well
     */
    fun isConnectedToOcean(city: City): Boolean {
        val bodies = WaterBodies.of(city.civ)
        val adjacent = city.getCenterTile().neighbors.filter { it.isWater }.map { bodies.bodyOf(it) }.toSet()
        if (adjacent.any { bodies.size(it) > 50 }) return true
        return city.civ.gameInfo.civilizations.any { other ->
            other != city.civ && other.cities.any { otherCity -> otherCity.getCenterTile().neighbors.any { it.isWater && bodies.bodyOf(it) in adjacent } }
        }
    }
}

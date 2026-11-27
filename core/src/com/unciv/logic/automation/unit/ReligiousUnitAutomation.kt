package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.ReligionAutomation
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.MapUnitView

/** The moves of religious units: CvHomelandAI::ExecuteMissionaryMoves / ExecuteInquisitorMoves / ExecuteProphetMoves with the targets of CvReligionAI */
object ReligiousUnitAutomation {

    /** CvHomelandAI::ExecuteMissionaryMoves: to the plot from [chooseTargetPlot] next to CvReligionAI::ChooseMissionaryTargetCity, then spread */
    fun automateMissionary(unitView: MapUnitView, cache: AutomationCache) {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        if (ReligionAutomation.religionAiLikeVp(unit.civ)) return automateMissionaryLikeVp(unitView, unit, cache)
        if (unitView.religion != unitView.civ().getReligion()?.name || unitView.religion == null) {
            // A unit of another religion would spread that one - nothing for us to do with it
            unitView.tryDisband()
            return
        }
        val city = ReligionAutomation.chooseMissionaryTargetCity(unit.civ, unit) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.SpreadReligion)
    }

    /** CvHomelandAI::ExecuteInquisitorMoves: to the plot next to CvReligionAI::ChooseInquisitorTargetCity, then remove heresy */
    fun automateInquisitor(unitView: MapUnitView, cache: AutomationCache) {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        if (ReligionAutomation.religionAiLikeVp(unit.civ)) {
            // VP keeps an inquisitor of any religion: it guards and cleanses for its own (CvHomelandAI::ExecuteInquisitorMoves)
            unit.religion?.let { automateInquisitorLikeVp(unitView, unit, it, cache) }
            return
        }
        if (unitView.religion != unitView.civ().getReligion()?.name || unitView.religion == null) {
            // No need to keep a unit we can't use, as it only blocks religion spreads of religions other that its own
            unitView.tryDisband()
            return
        }
        val religion = unit.religion!!
        val city = ReligionAutomation.chooseInquisitorTargetCity(unit.civ, unit) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city && hasReligionOtherThan(city, religion) } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.RemoveHeresy)
    }

    /**
     *  CvHomelandAI::ExecuteMissionaryMoves (V:CvHomelandAI.cpp:5353-5423) with CvReligionAI::ChooseMissionaryTargetCity: the best city it has a
     *  path to, burned for the other missionaries. Without one, a missionary of another religion than the one we spread is disbanded; ours waits.
     *  Not built: the move to a garrison city (CvHomelandAI::MoveCivilianToGarrison, not in Unciv).
     */
    private fun automateMissionaryLikeVp(unitView: MapUnitView, unit: MapUnit, cache: AutomationCache) {
        for (city in ReligionAutomation.missionaryTargetsLikeVp(unit.civ, unit, cache.missionaryTargets)) {
            val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: continue
            cache.missionaryTargets[city] = unit
            moveAndAct(unitView, unit, target, UnitActionType.SpreadReligion)
            return
        }
        if (unit.religion != ReligionAutomation.religionToSpread(unit.civ, considerForeign = true)?.name) unitView.tryDisband()
    }

    /**
     *  CvHomelandAI::ExecuteInquisitorMoves (V:CvHomelandAI.cpp:5426-5490) with CvReligionAI::ChooseInquisitorTargetCity: the best city to remove
     *  heresy from that it has a path to, then the best one to guard; the city is burned for the other inquisitors. A city to remove heresy from
     *  is one where CvReligionAIHelpers::ShouldRemoveHeresy holds, which a city to guard never is; a guard goes into the city, or next to it
     *  ([chooseTargetPlot]), and stays.
     */
    private fun automateInquisitorLikeVp(unitView: MapUnitView, unit: MapUnit, religion: String, cache: AutomationCache) {
        val targets = ReligionAutomation.inquisitorTargetsLikeVp(unit.civ, unit, cache.inquisitorTargets)
        for (city in targets.offensive) {
            val target = chooseTargetPlot(unit, city) { it.getCity() == city && hasReligionOtherThan(city, religion) } ?: continue
            cache.inquisitorTargets[city] = unit
            moveAndAct(unitView, unit, target, UnitActionType.RemoveHeresy)
            return
        }
        for (city in targets.defensive) {
            val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: continue
            cache.inquisitorTargets[city] = unit
            if (unit.getTile() != target) unit.movement.tryHeadTowards(target)
            return
        }
    }

    /** CvCityReligions::IsReligionHereOtherThan */
    private fun hasReligionOtherThan(city: City, religion: String): Boolean =
        city.religion.getPressures().keys.any { it != religion && it != Constants.noReligionName }

    private enum class ProphetDirective { SpreadReligion, UsePower, ConstructImprovement }

    /**
     *  CvPlayerAI::GetDirectiveProphet and CvHomelandAI::ExecuteProphetMoves. Enhanced religion: spread if CvReligionAI::ChooseProphetConversionCity
     *  finds a city, else build an improvement; founded religion: spread if a city needs it critically, else use the prophet's power (enhance);
     *  no religion: found it, or build an improvement when locked out. With [ReligionAutomation.religionAiLikeVp], [prophetDirectiveLikeVp].
     *  @return false for "build an improvement" (the Holy Site), which the caller's Great Person improvement code handles
     */
    fun automateGreatProphet(unitView: MapUnitView): Boolean {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        val civ = unit.civ
        val religionManager = civ.religionManager
        val religion = ReligionAutomation.foundedReligion(civ)
        val directive = if (ReligionAutomation.religionAiLikeVp(civ)) prophetDirectiveLikeVp(unit) else when {
            religion != null && religionManager.religionState == ReligionState.EnhancedReligion ->
                if (ReligionAutomation.chooseProphetConversionCity(civ, false) != null) ProphetDirective.SpreadReligion
                else ProphetDirective.ConstructImprovement
            religion != null ->
                if (ReligionAutomation.chooseProphetConversionCity(civ, true) != null) ProphetDirective.SpreadReligion
                else ProphetDirective.UsePower
            religionManager.remainingFoundableReligions() <= 0 -> ProphetDirective.ConstructImprovement
            else -> ProphetDirective.UsePower
        }
        when (directive) {
            ProphetDirective.ConstructImprovement -> return false
            ProphetDirective.SpreadReligion -> spreadReligionWithProphet(unitView)
            ProphetDirective.UsePower -> useProphetPower(unitView, unit)
        }
        return true
    }

    /**
     *  ExecuteProphetMoves, GREAT_PEOPLE_DIRECTIVE_SPREAD_RELIGION (V:CvHomelandAI.cpp:5102-5141): to CvReligionAI::ChooseProphetTargetPlot, then
     *  spread; the city is [chooseProphetConversionCityLikeVp]'s with [ReligionAutomation.religionAiLikeVp]. Without a city it stays (VP moves it to
     *  safety, CvHomelandAI::MoveCivilianToSafety, not in Unciv).
     */
    fun spreadReligionWithProphet(unitView: MapUnitView) {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        if (ReligionAutomation.religionAiLikeVp(unit.civ)) {
            chooseProphetConversionCityLikeVp(unit)?.let { moveAndAct(unitView, unit, it.plot, UnitActionType.SpreadReligion) }
            return
        }
        val city = ReligionAutomation.chooseProphetConversionCity(unit.civ, false) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.SpreadReligion)
    }

    /**
     *  CvPlayerAI::GetDirectiveProphet (V:CvPlayerAI.cpp:1927-1975): a prophet that has spread already keeps spreading. With an enhanced religion
     *  of ours, it builds a Holy Site while we have no more of them than our religion flavor minus our prophets, or when
     *  [chooseProphetConversionCityLikeVp] finds no city, and spreads otherwise; with our religion not enhanced, it enhances it while our holy
     *  city follows it, and spreads otherwise; without a religion of ours, it founds one, or builds a Holy Site when none is left to found.
     *  Not built: the Prophet Fervor trait (none in Unciv).
     */
    private fun prophetDirectiveLikeVp(unit: MapUnit): ProphetDirective {
        if (!ReligionAutomation.isFullStrength(unit)) return ProphetDirective.SpreadReligion
        val civ = unit.civ
        val religionManager = civ.religionManager
        val owned = religionManager.getOwnedReligion()
            ?: return if (religionManager.remainingFoundableReligions() <= 0) ProphetDirective.ConstructImprovement else ProphetDirective.UsePower
        if (owned.isEnhancedReligion()) {
            val flavor = civ.ai.personality(Flavor.Religion) - UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.PROPHET, includeBeingTrained = false)
            return if (holySites(unit) <= flavor || chooseProphetConversionCityLikeVp(unit) == null) ProphetDirective.ConstructImprovement
            else ProphetDirective.SpreadReligion
        }
        val holyCity = civ.cities.firstOrNull { it.isHolyCityOf(owned.name) }
        return if (holyCity?.religion?.getMajorityReligion() == owned) ProphetDirective.UsePower else ProphetDirective.SpreadReligion
    }

    /** CvPlayer::getImprovementCount for the improvement [unit] builds (the Holy Site): those on the tiles of our cities */
    private fun holySites(unit: MapUnit): Int {
        val improvement = unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).firstOrNull()?.params?.get(0) ?: return 0
        return unit.civ.cities.sumOf { city -> city.getTiles().count { it.improvement == improvement } }
    }

    /** The plot next to the chosen city ([chooseTargetPlot]) and the turns to it */
    private class ProphetTarget(val plot: Tile, val turns: Int)

    /**
     *  CvReligionAI::ChooseProphetConversionCity (V:CvReligionClasses.cpp:6492-6717), its path checks: of the two best cities of
     *  [ReligionAutomation.prophetTargetsLikeVp] the higher score / (turns to the plot of [chooseTargetPlot] + 3), none on a tie or when neither has
     *  a path safe for a civilian ([isPathSafeForCivilian]); a sole candidate if it has such a path.
     *  Not built: MOVEFLAG_NO_ENEMY_TERRITORY (Unciv paths have no such flag).
     */
    private fun chooseProphetConversionCityLikeVp(unit: MapUnit): ProphetTarget? {
        val candidates = ReligionAutomation.prophetTargetsLikeVp(unit.civ, unit)
        fun reach(index: Int): ProphetTarget? {
            val city = candidates.getOrNull(index)?.first ?: return null
            val plot = chooseTargetPlot(unit, city) { it.getCity() == city } ?: return null
            if (!isPathSafeForCivilian(unit, plot)) return null
            return ProphetTarget(plot, turnsToReach(unit, plot))
        }
        if (candidates.size < 2) return reach(0)
        val first = reach(0)
        val second = reach(1)
        val firstScore = first?.let { candidates[0].second / (it.turns + 3) } ?: 0
        val secondScore = second?.let { candidates[1].second / (it.turns + 3) } ?: 0
        return when {
            firstScore > 0 && firstScore > secondScore -> first
            secondScore > 0 && secondScore > firstScore -> second
            else -> null
        }
    }

    /** CvUnit::CachedPathIsSafeForCivilian (V:CvUnit.cpp:31605-31610): the plot where the first turn of the path to [tile] ends has less danger than the unit's health */
    private fun isPathSafeForCivilian(unit: MapUnit, tile: Tile): Boolean {
        if (tile == unit.getTile()) return true
        val firstTurnEnd = unit.movement.getShortestPath(tile).firstOrNull() ?: return true
        return unit.civ.ai.plotDanger.get(firstTurnEnd) < unit.health
    }

    /**
     *  ExecuteProphetMoves, GREAT_PEOPLE_DIRECTIVE_USE_POWER: found or enhance the religion here if possible, else move to the nearest
     *  (in turns) of our cities without a civilian in it. Only cities where the prophet could then act are considered, so that it does not
     *  shuttle between holy cities, where no religion can be founded.
     */
    private fun useProphetPower(unitView: MapUnitView, unit: MapUnit) {
        val religionManager = unit.civ.religionManager
        val tile = unit.getTile()
        if (religionManager.mayFoundReligionHere(tile)) {
            unitView.tryInvokeUnitAction(UnitActionType.FoundReligion)
            return
        }
        if (religionManager.mayEnhanceReligionHere(tile)) {
            unitView.tryInvokeUnitAction(UnitActionType.EnhanceReligion)
            return
        }
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        for (city in unit.civ.cities) {
            val center = city.getCenterTile()
            if (center.civilianUnit != null) continue
            if (!religionManager.mayFoundReligionHere(center) && !religionManager.mayEnhanceReligionHere(center)) continue
            val turns = turnsToReach(unit, center)
            if (turns < bestTurns) {
                bestTurns = turns
                best = center
            }
        }
        if (best != null) unit.movement.tryHeadTowards(best)
    }

    /** CvHomelandAI's TurnsToReachTarget: 0 = this turn, [Int.MAX_VALUE] = unreachable */
    private fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (tile == unit.getTile()) return 0
        if (!unit.movement.canMoveTo(tile)) return Int.MAX_VALUE
        val path = unit.movement.getShortestPath(tile)
        return if (path.isEmpty()) Int.MAX_VALUE else path.size - 1
    }

    /**
     *  CvReligionAI::ChooseMissionaryTargetPlot / ChooseInquisitorTargetPlot / ChooseProphetTargetPlot: our own city without a civilian in it
     *  is entered directly; otherwise the plot next to [city] without foreign units where the unit can act ([canActOn]), fewest turns first,
     *  then the shortest distance, water plots counting double.
     */
    internal fun chooseTargetPlot(unit: MapUnit, city: City, canActOn: (Tile) -> Boolean): Tile? {
        val center = city.getCenterTile()
        if (city.civ == unit.civ && (center.civilianUnit == null || center.civilianUnit == unit) && turnsToReach(unit, center) < Int.MAX_VALUE)
            return center

        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        for (tile in center.neighbors) {
            if (tile.getUnits().any { it.civ != unit.civ }) continue
            if (!canActOn(tile)) continue
            val turns = turnsToReach(unit, tile)
            if (turns == Int.MAX_VALUE) continue
            var distance = unit.getTile().aerialDistanceTo(tile)
            if (tile.isWater) distance *= 2 // those are dangerous
            if (turns < bestTurns || turns == bestTurns && distance < bestDistance) {
                bestTurns = turns
                bestDistance = distance
                best = tile
            }
        }
        return best
    }

    /** Execute*Moves: move to [target]; act once there */
    private fun moveAndAct(unitView: MapUnitView, unit: MapUnit, target: Tile, action: UnitActionType) {
        if (unit.getTile() != target) unit.movement.tryHeadTowards(target)
        if (unit.getTile() == target) unitView.tryInvokeUnitAction(action)
    }
}

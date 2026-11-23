package com.unciv.logic.automation.unit

import com.unciv.logic.map.mapunit.MapUnit
import yairm210.purity.annotations.Readonly

/**
 *  The movement the AI's reach and danger estimates (which plots a unit may attack or walk into within a turn, how far away a unit counts) credit [this] with: with
 *  `embarkAwarePathingLikeVp` what it really has (CvUnit::baseMoves: the embarked state, [MapUnit.getMaxMovement] with its promotions), else the unit type's movement.
 */
@Readonly
fun MapUnit.reachMovement(): Int =
    if (civ.gameInfo.ruleset.modOptions.constants.embarkAwarePathingLikeVp) getMaxMovement() else baseUnit.movement

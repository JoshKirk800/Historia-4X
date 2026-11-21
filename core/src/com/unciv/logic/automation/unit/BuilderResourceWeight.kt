package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.DealResourceValues
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource

/**
 *  CvBuilderTaskingAI.cpp: GetResourceWeight (Vox Populi): how much [civ] wants to hook up [resource] on a tile holding [quantity] of it.
 *  - A luxury that the World Congress has not banned: its happiness × BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_LUXURY_RESOURCE × [quantity].
 *  - A strategic resource: ([quantity] + 1) × 250, ×10 when the tech that reveals it is of the Renaissance era or later.
 *  - Anything else (bonus resources, a banned luxury): 0.
 *
 *  Unciv mapping: the luxury happiness is [DealResourceValues.luxuryHappiness] (4, or the needs constants' with the needs model: Unciv has no per-resource happiness); the reveal tech is [TileResource.revealedBy], its era the tech's era
 *  (the XML's eras up to the Information era have the same numbers in Unciv's rulesets; [AiDefine.RENAISSANCE_ERA]); a strategic resource
 *  without a reveal tech is not multiplied; the ban is [com.unciv.logic.league.League.isLuxuryBanned].
 *  The BNW weight (a sum of the products of resource, improvement and personality flavors) is gone.
 */
fun resourceWeight(civ: Civilization, resource: TileResource, quantity: Int): Int {
    val ruleset = civ.gameInfo.ruleset
    if (resource.resourceType == ResourceType.Luxury && civ.gameInfo.league?.isLuxuryBanned(resource.name) != true)
        return DealResourceValues.luxuryHappiness(civ) * ruleset.ai.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_LUXURY_RESOURCE) * quantity
    if (resource.resourceType != ResourceType.Strategic) return 0

    var value = (quantity + 1) * 250
    val revealEra = resource.revealedBy?.let { ruleset.technologies[it]?.era(ruleset)?.eraNumber }
    if (revealEra != null && revealEra >= ruleset.ai.define(AiDefine.RENAISSANCE_ERA)) value *= 10
    return value
}

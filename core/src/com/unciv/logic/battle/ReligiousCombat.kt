package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly

/**
 * The religious combat bonuses of Vox Populi 5.4.8 (Defender of the Faith, Evangelism), with the ModConstants `religiousCombatLikeVp`:
 * CvUnit::GetGenericMeleeStrengthModifier (V:CvUnit.cpp:16471-16507), which the melee attack and defence strengths both call, and the same block in
 * CvUnit::GetMaxRangedCombatStrength (V:CvUnit.cpp:17336-17372).
 *
 * A land unit fighting a unit of a major civilization (not a barbarian, not a City-State, not a city) reads the four columns `CombatBonusOwnLands`,
 * `CombatBonusVersusOtherReligionOwnLands`, `CombatBonusTheirLands` and `CombatBonusVersusOtherReligionTheirLands` ([UniqueType.ReligiousStrengthOwnLands] and its siblings)
 * from the beliefs of its owner's state religion, each belief only if CvReligionBeliefs::IsBeliefValid accepts it with the holy city of that religion as the city
 * (CvReligionBeliefs::GetCombatBonusOwnLands and its siblings, V:CvBeliefClasses.cpp:2779-2846). The battle tile is the defender's in every case: the melee attack passes
 * the tile attacked, the defence its own tile (CvUnit::GetMaxAttackStrength, GetMaxDefenseStrength), the ranged strength its target tile when attacking and its own when not.
 *
 * DEPARTURES: the ranged block skips the bonuses in the AI's quick estimates (`bQuickAndDirty`); the engine has no such estimate and always adds them. The City-State half of
 * CvPlot::IsFriendlyTerritory does not model `m_bAllowMajorsToIntrude` (a City-State threatened by barbarians or majors, or with a horde or rebellion quest, opens its lands to
 * every major for a turn: CvMinorCivAI::DoUpdateNumThreateningBarbarians, V:CvMinorCivAI.cpp:11686-11691, and CvMinorCivAI::DoIntrusion, V:CvMinorCivAI.cpp:11699-11713): the engine
 * keeps no such state.
 */
object ReligiousCombat {

    /**
     * The modifier from the summed columns, in the order of V:CvUnit.cpp:16490-16503: the own-lands pair when the battle tile is friendly to us, the their-lands pair when it is
     * friendly to the other civilization (both can hold, on a tile that one side opens to the other), each pair only if one of its two values is positive, and the other-religion
     * value of a pair only when the two state religions differ.
     */
    @Pure
    fun modifier(own: Int, ownOtherReligion: Int, their: Int, theirOtherReligion: Int,
                 friendlyToUs: Boolean, friendlyToThem: Boolean, sameStateReligion: Boolean): Int {
        var modifier = 0
        if ((own > 0 || ownOtherReligion > 0) && friendlyToUs) {
            modifier += own
            if (!sameStateReligion) modifier += ownOtherReligion
        }
        if ((their > 0 || theirOtherReligion > 0) && friendlyToThem) {
            modifier += their
            if (!sameStateReligion) modifier += theirOtherReligion
        }
        return modifier
    }

    /**
     * CvPlot::IsFriendlyTerritory (V:CvPlot.cpp:4943-4978): the tile is owned, [civ] is not a barbarian, and the tile is [civ]'s, or its owner opens its borders to [civ],
     * or, for a major [civ], it is a City-State's that [civ] has open borders with (CvMinorCivAI::IsPlayerHasOpenBorders, V:CvMinorCivAI.cpp:11853-11874: not at war, and
     * the trait that lets it intrude freely ([UniqueType.CityStateTerritoryAlwaysFriendly]) or friendship). City-States sign no open borders, so for a City-State [civ] only its
     * own tiles count; the engine's [com.unciv.logic.civilization.diplomacy.DiplomacyManager.hasOpenBorders] lets a City-State enter its ally, which the original does not.
     */
    @Readonly
    fun isFriendlyTerritory(tile: Tile, civ: Civilization): Boolean {
        val owner = tile.getOwner() ?: return false
        if (civ.isBarbarian) return false
        if (owner == civ) return true
        val diplomacy = civ.getDiplomacyManager(owner) ?: return false // never met: no open borders and no friendship
        if (civ.isCityState) return false
        if (!owner.isCityState) return diplomacy.hasOpenBorders
        if (owner.isAtWarWith(civ)) return false
        if (civ.hasUnique(UniqueType.CityStateTerritoryAlwaysFriendly)) return true
        return owner.getDiplomacyManager(civ)!!.isRelationshipLevelGE(RelationshipLevel.Friend)
    }

    @Readonly
    private fun sum(belief: Belief, type: UniqueType, gameContext: GameContext): Int =
        belief.getMatchingUniques(type, gameContext).sumOf { it.params[0].toInt() }

    /**
     * The strength modifier of [unit] fighting a unit of [enemyCiv] on [battleTile], the defender's tile. 0 for a unit that is not a land unit, against a civilization that is
     * not a major one, and for an owner without a state religion ([com.unciv.logic.civilization.managers.ReligionManager.getStateReligionCached]) or whose state religion has no
     * valid belief with these uniques. The state religion of [enemyCiv] may be none, which differs from ours.
     */
    @Readonly
    fun getStrengthModifier(unit: MapUnit, enemyCiv: Civilization, battleTile: Tile, gameContext: GameContext): Int {
        if (!unit.type.isLandUnit() || !enemyCiv.isMajorCiv) return 0
        val civ = unit.civ
        val religionManager = civ.religionManager
        val stateReligion = religionManager.getStateReligionCached() ?: return 0
        var own = 0
        var ownOtherReligion = 0
        var their = 0
        var theirOtherReligion = 0
        var holyCity: City? = null
        var holyCityFound = false
        for (belief in stateReligion.getAllBeliefsOrdered()) {
            val beliefOwn = sum(belief, UniqueType.ReligiousStrengthOwnLands, gameContext)
            val beliefOwnOther = sum(belief, UniqueType.ReligiousStrengthOwnLandsOtherReligion, gameContext)
            val beliefTheir = sum(belief, UniqueType.ReligiousStrengthTheirLands, gameContext)
            val beliefTheirOther = sum(belief, UniqueType.ReligiousStrengthTheirLandsOtherReligion, gameContext)
            if (beliefOwn == 0 && beliefOwnOther == 0 && beliefTheir == 0 && beliefTheirOther == 0) continue
            if (!holyCityFound) {
                holyCity = religionManager.getHolyCityOf(stateReligion)
                holyCityFound = true
            }
            if (!religionManager.isStateReligionBeliefValid(belief, stateReligion, holyCity)) continue
            own += beliefOwn
            ownOtherReligion += beliefOwnOther
            their += beliefTheir
            theirOtherReligion += beliefTheirOther
        }
        if (own == 0 && ownOtherReligion == 0 && their == 0 && theirOtherReligion == 0) return 0
        val sameStateReligion = enemyCiv.religionManager.getStateReligionCached()?.name == stateReligion.name
        return modifier(own, ownOtherReligion, their, theirOtherReligion,
            isFriendlyTerritory(battleTile, civ), isFriendlyTerritory(battleTile, enemyCiv), sameStateReligion)
    }
}

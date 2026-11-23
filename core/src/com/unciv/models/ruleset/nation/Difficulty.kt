package com.unciv.models.ruleset.nation

import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetObject
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.objectdescriptions.FormattedLineListBuilder.Companion.buildCivilopediaText
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import yairm210.purity.annotations.Readonly

class Difficulty : RulesetObject() {
    override lateinit var name: String
    var baseHappiness: Int = 0
    var extraHappinessPerLuxury: Float = 0f
    /** Happiness of the capital on top of [baseHappiness] (the original's `HappinessDefaultCapital`, CvCity::GetLocalHappiness), only with [com.unciv.models.ModConstants.cityNeedsLikeVp] */
    var baseHappinessCapital: Int = 0
    /** Added to [baseHappiness] and [baseHappinessCapital] of an AI civilization, from the game's chosen difficulty and not from the AI's own level
     *  (`AIHappinessDefault`, `AIHappinessDefaultCapital`: CvPlayer::DoUpdateTotalHappiness, CvCity::GetLocalHappiness), only with `cityNeedsLikeVp` */
    var aiBaseHappiness: Int = 0
    var aiBaseHappinessCapital: Int = 0
    /** Percent points added to every need of every city (`PopulationUnhappinessMod`, CvCity::GetAllNeedsModifier); an AI civilization adds
     *  [aiPopulationNeedModifier] of the chosen difficulty to the one of its own level (`AIPopulationUnhappinessMod`). Only with `cityNeedsLikeVp` */
    var populationNeedModifier: Int = 0
    var aiPopulationNeedModifier: Int = 0
    /** Percent change of the empire size modifier of the needs (`EmpireSizeUnhappinessMod`, CvCity::GetEmpireSizeModifier), likewise for an AI.
     *  Only with `cityNeedsLikeVp` */
    var empireSizeNeedModifier: Int = 0
    var aiEmpireSizeNeedModifier: Int = 0
    /** Added to the security of the cities of a civilization with this difficulty (`HandicapInfos.SpySecurityModifier`, CvCity::CalculateCitySecurity, V:CvCity.cpp:26007-26013); the cities of an AI
     *  civilization also get the one of the game's chosen difficulty. Only with [com.unciv.models.ModConstants.espionageLikeVp] */
    var spySecurityModifier: Int = 0
    var researchCostModifier: Float = 1f
    var unitCostModifier: Float = 1f
    var unitSupplyBase: Int = 5
    var unitSupplyPerCity: Int = 2
    var buildingCostModifier: Float = 1f
    var policyCostModifier: Float = 1f
    var unhappinessModifier: Float = 1f
    var barbarianBonus: Float = 0f
    var barbarianSpawnDelay: Int = 0
    var playerBonusStartingUnits = ArrayList<String>()

    /** `false` hides this level from the difficulty choice of a new game. For a level that only exists as the handicap of the AI civilizations
     *  (see [aiDifficultyLevel]), like Brave New World's `HANDICAP_AI_DEFAULT`. */
    var isSelectable: Boolean = true

    var aiDifficultyLevel: String? = null
    /** The level whose player settings city-state civilizations have, default [aiDifficultyLevel]. Brave New World: Prince (MINOR_CIV_HANDICAP) */
    var cityStateDifficultyLevel: String? = null
    /** The level whose player settings the barbarians have, default [aiDifficultyLevel]. Brave New World: Chieftain (BARBARIAN_HANDICAP) */
    var barbarianDifficultyLevel: String? = null
    var aiCityGrowthModifier: Float = 1f
    var aiUnitCostModifier: Float = 1f
    var aiBuildingCostModifier: Float = 1f
    var aiWonderCostModifier: Float = 1f
    var aiBuildingMaintenanceModifier: Float = 1f
    var aiUnitMaintenanceModifier: Float = 1f

    /**
     *  The unit supply of an AI major civilization, from the difficulty the game is played at, not from its own player settings
     *  ([unitSupplyBase], [unitSupplyPerCity]): the Community Patch's AIUnitSupplyBase, AIUnitSupplyPerCity, AIUnitSupplyPopulationPercent
     *  and AIUnitSupplyBonusPercent of the chosen difficulty (CvPlayer::GetNumUnitsSuppliedByHandicap, ...ByCities, ...ByPopulation and
     *  GetUnitSupplyBreakdown). The supply is (base + perCity x cities + populationPercent % of the citizens) x (100 + bonusPercent) / 100.
     *  The defaults are the values the Community Patch gives every level but for the bonus.
     */
    var aiUnitSupplyBase: Int = 7
    var aiUnitSupplyPerCity: Int = 3
    var aiUnitSupplyPopulationPercent: Int = 50
    var aiUnitSupplyBonusPercent: Int = 0

    /** The same four values for a city-state civilization (CityStateUnitSupplyBase, ...PerCity, ...PopulationPercent, ...BonusPercent, which
     *  CvPlayer::GetUnitSupplyBreakdown reads from the difficulty the game is played at for a minor civilization). */
    var cityStateUnitSupplyBase: Int = 7
    var cityStateUnitSupplyPerCity: Int = 3
    var cityStateUnitSupplyPopulationPercent: Int = 50
    var cityStateUnitSupplyBonusPercent: Int = 0

    var aiFreeTechs = ArrayList<String>()
    var aiMajorCivBonusStartingUnits = ArrayList<String>()
    var aiCityStateBonusStartingUnits = ArrayList<String>()
    var aiUnhappinessModifier: Float = 1f
    /** Change of the AI's production costs, city growth thresholds and unit upgrade costs per era the AI is in (AIPerEraModifier): -0.05 is 5% cheaper per era. */
    var aiPerEraModifier: Float = 0f
    /** Multiplier for the gold cost to upgrade a unit of an AI (AIUnitUpgradePercent) */
    var aiUnitUpgradeCostModifier: Float = 1f
    /** Experience an AI's military units start with (AIFreeXP) */
    var aiFreeUnitExperience: Int = 0
    /** Additional experience gain in combat for the units of an AI, 1 = 100% more (AIFreeXPPercent) */
    var aiFreeUnitExperiencePercent: Float = 0f
    /** Additional speed of the workers of an AI, 1 = 100% faster (AIWorkRateModifier) */
    var aiWorkRateModifier: Float = 0f
    /** Multiplier for the gold maintenance of the roads and railroads of this level's players (RouteCostPercent) */
    var routeCostModifier: Float = 1f
    var turnBarbariansCanEnterPlayerTiles: Int = 0
    var clearBarbarianCampReward: Int = 25

    // property defined in json but so far unused:
    // var aisExchangeTechs = false

    // Note: Difficulty uniques will be treated as part of GlobalUniques
    override fun getUniqueTarget(): UniqueTarget = UniqueTarget.Difficulty

    override fun makeLink() = "Difficulty/$name"

    override fun getSortGroup(ruleset: Ruleset) = ruleset.difficulties.keys.indexOf(name)

    @Readonly
    override fun getCivilopediaTextLines(ruleset: Ruleset) = buildCivilopediaText(defaults = FormattedLine(indent = 1)) {
        fun Float.toPercent() = (this * 100).toInt()

        add("Player settings", header = 3, indent = 0)
        add("{Base happiness}: $baseHappiness ${Fonts.happiness}")
        add("{Extra happiness per luxury}: ${extraHappinessPerLuxury.toInt()} ${Fonts.happiness}")
        add("{Research cost modifier}: ${researchCostModifier.toPercent()}% ${Fonts.science}")
        add("{Unit cost modifier}: ${unitCostModifier.toPercent()}% ${Fonts.production}")
        add("{Building cost modifier}: ${buildingCostModifier.toPercent()}% ${Fonts.production}")
        add("{Policy cost modifier}: ${policyCostModifier.toPercent()}% ${Fonts.culture}")
        add("{Unhappiness modifier}: ${unhappinessModifier.toPercent()}%")
        add("{Bonus vs. Barbarians}: ${barbarianBonus.toPercent()}% ${Fonts.strength}")
        add("{Barbarian spawning delay}: $barbarianSpawnDelay")

        if (playerBonusStartingUnits.isNotEmpty()) {
            space()
            add("{Bonus starting units}:")
            playerBonusStartingUnits.groupBy { it }.map {
                it.key to it.value.size     // name to Pair.first and count to Pair.second
            }.forEach {
                // Through a virtual Unique was the simplest way to prevent white icons showing for stuff like eraSpecificUnit
                add(Unique(if (it.second == 1) "[${it.first}]" else "${it.second} [${it.first}]"), indent = 2)
            }
        }

        space()
        add("AI settings", header = 3, indent = 0)
        add("{AI difficulty level}: {$aiDifficultyLevel}")
        add("{AI city growth modifier}: ${aiCityGrowthModifier.toPercent()}% ${Fonts.food}")
        add("{AI unit cost modifier}: ${aiUnitCostModifier.toPercent()}% ${Fonts.production}")
        add("{AI building cost modifier}: ${aiBuildingCostModifier.toPercent()}% ${Fonts.production}")
        add("{AI wonder cost modifier}: ${aiWonderCostModifier.toPercent()}% ${Fonts.production}")
        add("{AI building maintenance modifier}: ${aiBuildingMaintenanceModifier.toPercent()}% ${Fonts.gold}")
        add("{AI unit maintenance modifier}: ${aiUnitMaintenanceModifier.toPercent()}% ${Fonts.gold}")
        add("{AI unhappiness modifier}: ${aiUnhappinessModifier.toPercent()}%")
        if (aiPerEraModifier != 0f) add("{AI per era modifier}: ${(aiPerEraModifier * 100).toInt()}%")
        if (aiUnitUpgradeCostModifier != 1f) add("{AI unit upgrade cost modifier}: ${aiUnitUpgradeCostModifier.toPercent()}% ${Fonts.gold}")
        if (aiFreeUnitExperience != 0) add("{AI free unit experience}: $aiFreeUnitExperience")
        if (aiFreeUnitExperiencePercent != 0f) add("{AI unit experience gain bonus}: ${(aiFreeUnitExperiencePercent * 100).toInt()}%")
        if (aiWorkRateModifier != 0f) add("{AI worker speed bonus}: ${(aiWorkRateModifier * 100).toInt()}%")

        if (aiFreeTechs.isNotEmpty()) {
            space()
            add("{AI free techs}:")
            aiFreeTechs.forEach {
                add(it, link = "Technology/$it", indent = 2)
            }
        }
        if (aiMajorCivBonusStartingUnits.isNotEmpty()) {
            space()
            add("{Major AI civilization bonus starting units}:")
            aiMajorCivBonusStartingUnits.groupBy { it }.map {
                it.key to it.value.size
            }.forEach {
                add(Unique(if (it.second == 1) "[${it.first}]" else "${it.second} [${it.first}]"), indent = 2)
            }
        }
        if (aiCityStateBonusStartingUnits.isNotEmpty()) {
            space()
            add("{City state bonus starting units}:")
            aiCityStateBonusStartingUnits.groupBy { it }.map {
                it.key to it.value.size
            }.forEach {
                add(Unique(if (it.second == 1) "[${it.first}]" else "${it.second} [${it.first}]"), indent = 2)
            }
        }

        defaults(FormattedLine())
        space()
        add("{Turns until barbarians enter player tiles}: $turnBarbariansCanEnterPlayerTiles ${Fonts.turn}")
        add("{Gold reward for clearing barbarian camps}: $clearBarbarianCampReward ${Fonts.gold}")

        addUniques()
    }
}

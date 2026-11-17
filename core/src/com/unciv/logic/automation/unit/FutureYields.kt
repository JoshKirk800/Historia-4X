package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max

/**
 *  CvBuilderTaskingAI::m_futureYieldBonuses with UpdateFutureYields and GetFutureYields (Vox Populi): the yields that techs and policies not yet
 *  had will add to an improvement, each ×(a weight of 0..100) so that [get] is the original's "times 100" amount, which ScorePlotBuild
 *  adds to the potential score. Rebuilt every turn by [BuilderTaskingAi] ([update]).
 *
 *  - Techs: every tech [civ] lacks. Its weight is 100 less 10 per column (CvTechEntry::GetGridX is [com.unciv.models.ruleset.tech.TechColumn.columnNumber])
 *    that the tech lies beyond the average of the column of our latest tech and that of the earliest tech we lack ("to account for beelining");
 *    a tech 10 or more columns beyond counts nothing. The yield is what the improvement's stat uniques conditional on "after discovering [tech]"
 *    give (Improvement_TechYieldChanges; also the "from [tile] tiles" ones: Fresh water and non-fresh water farms).
 *  - Policies: every policy not adopted, of a branch that is (CvPlayerPolicies::HasPolicyBranch is the branch's opener being adopted), at weight
 *    80, an ideology's at 50 ("Ideology policies may stay unchosen"; PolicyBranch.IsPurchaseByLevel is [com.unciv.models.ruleset.PolicyBranch.isIdeology]).
 *    The yield is what the policy's "[stats] from every [improvement]" uniques give (Policy_ImprovementYieldChanges).
 *
 *  None-inputs: the original's policy table is much larger (160 rows) than BNW's "from every [improvement]" uniques (9); WLTKD and golden age
 *  improvement yields are not future yields in either (ScorePlotBuild adds them to the current score, which is none here); tourism has no yield
 *  in the rules' tile stats.
 */
class FutureYields(private val civ: Civilization) {
    private val ruleset = civ.gameInfo.ruleset
    private val bonuses = HashMap<TileImprovement, MutableMap<Stat, Int>>()

    /** UpdateFutureYields: [possible] are the improvements of the builds a unit can plan, plus the ones on our plots */
    fun update(possible: Collection<TileImprovement>) {
        bonuses.clear()
        val improvements = LinkedHashSet(possible)
        for (city in civ.cities) for (tile in city.getTiles()) tile.tileImprovement?.let { improvements.add(it) }

        var ourLatestTechX = 0
        var ourEarliestMissingTechX = Int.MAX_VALUE
        val techsToConsider = ArrayList<Pair<String, Int>>()
        for (tech in ruleset.technologies.values) {
            val techX = tech.column?.columnNumber ?: continue
            if (civ.tech.isResearched(tech.name)) {
                // A bit hacky, the tech's position in the tree decides how far away it is
                if (techX > ourLatestTechX) ourLatestTechX = techX
                continue
            }
            if (techX < ourEarliestMissingTechX) ourEarliestMissingTechX = techX
            techsToConsider.add(tech.name to techX)
        }
        // Tech tree distance from the average of our latest known tech and our earliest unknown tech to account for beelining
        val ourAverageTechX = if (ourEarliestMissingTechX != Int.MAX_VALUE) (ourEarliestMissingTechX + ourLatestTechX) / 2 else ourLatestTechX
        val techMultiplier = techsToConsider.associate { (name, techX) -> name to max(100 - max(techX - ourAverageTechX, 0) * 10, 0) }

        // Only policies of adopted policy trees
        val policiesToConsider = ruleset.policies.values.filter {
            !civ.policies.isAdopted(it.name) && civ.policies.isAdopted(it.branch.name)
        }

        for (improvement in improvements) {
            for (unique in improvement.uniqueObjects) {
                if (unique.type != UniqueType.Stats && unique.type != UniqueType.StatsFromTiles) continue
                val tech = unique.getModifiers(UniqueType.ConditionalTech).firstOrNull()?.params?.get(0) ?: continue
                val multiplier = techMultiplier[tech] ?: continue
                if (multiplier == 0) continue
                add(improvement, unique.stats, multiplier)
            }

            for (policy in policiesToConsider) {
                // An arbitrary value for unadopted policies, ideology policies may stay unchosen
                val multiplier = if (policy.branch.isIdeology) 50 else 80
                for (unique in policy.uniqueObjects)
                    if (unique.type == UniqueType.StatsFromObject && unique.params[1] == improvement.name)
                        add(improvement, unique.stats, multiplier)
            }
        }
    }

    private fun add(improvement: TileImprovement, stats: Stats, multiplier: Int) {
        val perStat = bonuses.getOrPut(improvement) { HashMap() }
        for (stat in PlotBuildScorer.YIELDS) {
            val change = stats[stat].toInt()
            if (change != 0) perStat[stat] = (perStat[stat] ?: 0) + change * multiplier
        }
    }

    /** GetFutureYields: 0 for an improvement or stat without one */
    operator fun get(improvement: TileImprovement, stat: Stat): Int = bonuses[improvement]?.get(stat) ?: 0
}

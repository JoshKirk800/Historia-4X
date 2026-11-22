package com.unciv.testing

import com.unciv.models.ruleset.Ruleset

/**
 *  What the Brave New World ruleset played before it took over Vox Populi's needs (`ModConstants.cityNeedsLikeVp`): the original's happiness model, one
 *  number for the civilization with the effects of its bands as global uniques. The code of that model stays for the rulesets that do not turn the key on
 *  (Gods & Kings, Vanilla, until they are removed), and the tests of it keep running on a Brave New World game that gets back what it had:
 *  `TestGame(*OriginalHappinessModel.globalUniques, baseRuleset = BaseRuleset.Civ_V_BNW)` and then [use] on its ruleset.
 *
 *  The building rows of the original (Colosseum 2, Circus Maximus 5 unmoddable...) are not restored: the ruleset's building objects are shared with
 *  every other test, so a test that needs a building of the original's happiness makes one (`TestGame.createBuilding`).
 */
object OriginalHappinessModel {
    /** The effects of the happiness bands of GlobalUniques.json (CvPlayer::IsEmpireUnhappy, IsEmpireVeryUnhappy, IsEmpireSuperUnhappy, CvCity::getHappinessModifier,
     *  CvUnit::GetUnhappinessCombatPenalty): growth, production, gold and combat strength, settlers and rebels */
    val globalUniques = arrayOf(
        "[-75]% growth [in all cities] <when between [-9] and [-1] [Happiness]>",
        "[-100]% growth [in all cities] <when below [-9] [Happiness]>",
        "[-2]% [Production] [in all cities] <for every [min(20, 0 - [Happiness])]>",
        "[-2]% [Gold] [in all cities] <for every [min(20, 0 - [Happiness])]>",
        "[-2]% Strength <for [All] units> <for every [min(20, 0 - [Happiness])]>",
        "Cannot build [Settler] units <when below [-9] [Happiness]>",
        "Rebel units may spawn <when below [-19] [Happiness]>",
    )

    /** The constants of the original's model that ModOptions.json of Brave New World had: no needs, happiness of the buildings limited by the population
     *  (CvCity::GetLocalHappiness), an occupied city costs UNHAPPINESS_PER_OCCUPIED_POPULATION 1.34 per citizen and nothing more */
    fun use(ruleset: Ruleset) {
        val constants = ruleset.modOptions.constants
        constants.cityNeedsLikeVp = false
        constants.localHappinessCappedByPopulation = true
        constants.annexedCityCitizenUnhappinessFactor = 1.34f
        constants.annexedCityFlatUnhappiness = 0f
    }
}

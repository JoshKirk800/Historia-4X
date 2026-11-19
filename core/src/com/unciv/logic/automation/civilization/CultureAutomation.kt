package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel

/**
 * The culture side of open borders (CvDealAI::GetOpenBordersValue, [DealDiplomacyValues.openBordersValue]): the civilizations an AI with tourism
 * still needs influence on.
 */
object CultureAutomation {

    /**
     * CvPlayerCulture::GetCivLowestInfluence(bCheckOpenBorders = false): the other living major civilization, not at war with us, on which our influence
     * is the lowest share of its lifetime culture, below the Influential level; the first in civilization order on a tie. Null if there is none.
     */
    fun getCivLowestInfluence(civ: Civilization): Civilization? {
        var lowestPercent = InfluenceLevel.Influential.getThresholdPercent(civ)
        var lowest: Civilization? = null
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive() || civ.isAtWarWith(other)) continue
            val percent = civ.culture.getInfluencePercent(other)
            if (percent < lowestPercent) {
                lowestPercent = percent
                lowest = other
            }
        }
        return lowest
    }

    /** Influential on at least as many civilizations as are left to influence (GetNumCivsToBeInfluentialOn <= GetNumCivsInfluentialOn) */
    fun isInfluentialOnHalf(civ: Civilization): Boolean =
        civ.culture.getOtherMajorCivs().size <= civ.culture.getNumCivsInfluentialOn()

    /** Our influence on [other] is below the Influential level */
    fun isBelowInfluential(civ: Civilization, other: Civilization): Boolean =
        (civ.culture.getInfluenceLevel(other) ?: InfluenceLevel.Unknown) < InfluenceLevel.Influential
}

package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import yairm210.purity.annotations.Readonly

/**
 *  Vox Populi's competitor assessments (CvDiplomacyAI, Release-5.4.8; D7): which majors are our major competitors
 *  (DoUpdateMajorCompetitors, stored as [MajorCivMemory.majorCompetitor]) and which are early-game competitors (IsEarlyGameCompetitor,
 *  asked when needed). Inputs we lack count as absent: victory dispute and block levels, tech and policy block levels, wonder
 *  spammers, the World Congress competitor and sanctions, city-state troublemakers, plots against us and coups, easy targets and
 *  preferred attack targets. No vassals (D3). IsUntrustworthy is BNW's form ([OpinionAi.isUntrustworthyFriend]).
 */
object CompetitorAi {

    /**
     *  CvDiplomacyAI::DoUpdateMajorCompetitors, run between the strategic and the final approach passes as VP's DoTurn does. Each valid
     *  major is a competitor, in VP's order, when: only two majors are alive and we compete for victory; they hold a third of the other
     *  original capitals, or as many as a third of the majors ever; we are endgame-aggressive towards them; they took our capital or holy
     *  city; they nuked us or have nukes. Otherwise not when they lost their own capital; else a competitor when we are close to world
     *  conquest; they are untrustworthy, of an opposing ideology, a severe warmonger threat, unforgivable, in a strong land dispute or a
     *  reckless expander; and, competing for victory, by our victory: conquest (from the Medieval era) when they hold a capital, dispute
     *  land or are neighbours; culture (from the Renaissance, or close to it) on a strong wonder dispute, when they are the major we
     *  influence least (below influential), when they become popular with us and are not falling (or influential), or when our influence on
     *  them stays low (Modern era on: familiar or below, or popular and falling; before: exotic or below, or familiar and falling);
     *  spaceship (from the Renaissance, or close to it) from the Atomic era when they know more techs. Diplomacy has no input we keep.
     */
    fun updateMajorCompetitors(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (memory in civ.ai.diplomacy.memories.values) memory.majorCompetitor = false
        for (other in others) {
            if (!other.isMajorCiv) continue
            civ.ai.diplomacy.memoryOf(other).majorCompetitor = isMajorCompetitorNow(civ, other, rules)
        }
    }

    /** CvDiplomacyAI::IsMajorCompetitor: as of the last [updateMajorCompetitors] */
    @Readonly
    fun isMajorCompetitor(civ: Civilization, other: Civilization): Boolean = civ.ai.diplomacy.memories[other.civID]?.majorCompetitor == true

    private fun isMajorCompetitorNow(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val game = civ.gameInfo
        val majorsEver = game.civilizations.count { it.isMajorCiv }
        val competing = VictoryProgressAi.isCompetingForVictory(civ)
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (competing && game.civilizations.count { it.isMajorCiv && it.isAlive() } == 2) return true
        if (fractionOriginalCapitalsUnderControl(other, majorsEver) >= 33) return true
        if (VictoryProgressAi.capitalsConquered(other) >= majorsEver * 33 / 100) return true
        if (VictoryProgressAi.isEndgameAggressiveTo(civ, other, rules)) return true
        if (OpinionAi.isCapitalCapturedBy(civ, other) || isHolyCityCapturedBy(civ, other)) return true
        if (memory.numTimesNuked > 0 || other.units.getCivUnits().any { it.isNuclearWeapon() }) return true
        if (VictoryProgressAi.hasLostCapital(other)) return false
        if (VictoryProgressAi.isCloseToWorldConquest(civ, rules)) return true
        if (OpinionAi.isUntrustworthyFriend(other)) return true
        if (isOpposingIdeology(civ, other)) return true
        if (civ.ai.diplomacy.warmongerThreat(other) >= AiThreat.Severe) return true
        if (memory.opinion == MajorCivOpinion.Unforgivable) return true
        if (memory.landDispute >= DisputeLevel.Strong) return true
        if (WarStateAi.isPlayerRecklessExpander(civ, other)) return true
        if (!competing) return false
        val era = civ.getEra().eraNumber
        if (civ.ai.isGoingForWorldConquest() && era >= rules.define(AiDefine.MEDIEVAL_ERA)) {
            if (VictoryProgressAi.capitalsConquered(other) > 0) return true
            if (memory.landDispute > DisputeLevel.None) return true
            if (civ.getProximity(other) == Proximity.Neighbors) return true
        }
        val renaissance = era >= rules.define(AiDefine.RENAISSANCE_ERA)
        val closeToCulture = VictoryProgressAi.cultureVictoryProgress(civ, rules) >= rules.define(AiDefine.CLOSE_TO_CULTURE_VICTORY_THRESHOLD)
        if ((renaissance && civ.ai.isGoingForCultureVictory()) || closeToCulture) {
            if (memory.wonderDispute >= DisputeLevel.Strong) return true
            if (civLowestInfluence(civ, rules) == other) return true
            val theirLevel = other.culture.getInfluenceLevel(civ) ?: InfluenceLevel.Unknown
            if (theirLevel >= InfluenceLevel.Popular &&
                (theirLevel >= InfluenceLevel.Influential || other.culture.getInfluenceTrend(civ) != InfluenceTrend.Falling)) return true
            val ourLevel = civ.culture.getInfluenceLevel(other) ?: InfluenceLevel.Unknown
            val falling = civ.culture.getInfluenceTrend(other) == InfluenceTrend.Falling
            val modern = era >= rules.define(AiDefine.MODERN_ERA)
            if (modern && (ourLevel <= InfluenceLevel.Familiar || (ourLevel == InfluenceLevel.Popular && falling))) return true
            if (!modern && (ourLevel <= InfluenceLevel.Exotic || (ourLevel == InfluenceLevel.Familiar && falling))) return true
        }
        val closeToScience = VictoryProgressAi.scienceVictoryProgress(civ) >= rules.define(AiDefine.CLOSE_TO_SCIENCE_VICTORY_THRESHOLD)
        if ((renaissance && civ.ai.isGoingForSpaceshipVictory()) || closeToScience) {
            if (era >= rules.define(AiDefine.ATOMIC_ERA) && other.tech.techsResearched.size > civ.tech.techsResearched.size) return true
        }
        return false
    }

    /**
     *  CvDiplomacyAI::IsEarlyGameCompetitor: up to the Medieval era (era 2), when our opinion of them is Enemy or worse, they dispute
     *  land more than weakly, beat us to a wonder, made a demand ([MajorCivMemory.demandCounter]: VP counts demands), converted our cities,
     *  either of us denounced the other, they robbed us, ignored or broke a promise about bullying city-states, attacked or killed a
     *  city-state we protect, or are untrustworthy.
     */
    fun isEarlyGameCompetitor(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        if (civ.getEra().eraNumber > 2) return false
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return false
        return memory.opinion <= MajorCivOpinion.Enemy
            || memory.landDispute > DisputeLevel.Weak
            || memory.numWondersBeatenTo > 0
            || memory.demandCounter != -1
            || memory.negativeReligiousConversionPoints > 0
            || OpinionAi.isDenouncedPlayer(civ, other) || OpinionAi.isDenouncedPlayer(other, civ)
            || memory.numTimesRobbed > 0
            || memory.ignoredBullyCityStatePromise || memory.brokenBullyCityStatePromise
            || OpinionAi.isAngryAboutProtectedMinorAttacked(memory, rules) || OpinionAi.isAngryAboutProtectedMinorKilled(memory, rules)
            || OpinionAi.isUntrustworthyFriend(other)
    }

    /** CvPlayer::GetFractionOriginalCapitalsUnderControl (UpdateMilitaryStats): the original capitals of majors [civ] holds, its own
     *  capital aside, in % of the other majors that ever lived */
    @Readonly
    private fun fractionOriginalCapitalsUnderControl(civ: Civilization, majorsEver: Int): Int {
        val held = civ.cities.count { it.isOriginalCapital && it.foundingCivObject?.isMajorCiv == true && !it.isCapital() }
        return held * 100 / maxOf(1, majorsEver - 1)
    }

    /** CvDiplomacyAI::IsHolyCityCapturedBy, currently: [other] holds the holy city of the religion [civ] founded */
    @Readonly
    private fun isHolyCityCapturedBy(civ: Civilization, other: Civilization): Boolean {
        val religion = civ.religionManager.religion?.takeIf { it.foundingCivName == civ.civName } ?: return false
        return other.cities.any { it.isHolyCityOf(religion.name) }
    }

    /** CvDiplomacyAI::IsPlayerOpposingIdeology: both have an ideology, and they differ */
    @Readonly
    private fun isOpposingIdeology(civ: Civilization, other: Civilization): Boolean {
        val ours = civ.getIdeology() ?: return false
        val theirs = other.getIdeology() ?: return false
        return ours != theirs
    }

    /** CvPlayerCulture::GetCivLowestInfluence (without the open-borders check): the living major not at war with us that our influence
     *  covers the least, below CULTURE_LEVEL_INFLUENTIAL % */
    @Readonly
    private fun civLowestInfluence(civ: Civilization, rules: AiRuleset): Civilization? {
        var lowest = rules.define(AiDefine.CULTURE_LEVEL_INFLUENTIAL)
        var result: Civilization? = null
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive() || civ.isAtWarWith(other)) continue
            val percent = civ.culture.getInfluencePercent(other)
            if (percent < lowest) {
                lowest = percent
                result = other
            }
        }
        return result
    }
}

package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  Vox Populi's war progress score (CvDiplomacyAI m_aiWarProgressScore, Release-5.4.8; D7): how a war goes for each major, from its
 *  own wins and losses, read by the peace willingness. Only majors keep it. VP's trade route plunder and tile theft have no
 *  counterpart.
 */
object WarProgressAi {

    private fun define(civ: Civilization, define: AiDefine) = civ.gameInfo.ruleset.ai.define(define)

    /** CvDiplomacyAI::ChangeWarProgressScore, for majors */
    fun change(civ: Civilization, other: Civilization, change: Int) {
        if (!civ.isMajorCiv) return
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.warProgress = (memory.warProgress + change).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /** CvTeam::DoDeclareWar: both majors start the war at WAR_PROGRESS_INITIAL_VALUE */
    fun onWarDeclared(declarer: Civilization, victim: Civilization) {
        if (declarer.isMajorCiv) declarer.ai.diplomacy.memoryOf(victim).warProgress = define(declarer, AiDefine.WAR_PROGRESS_INITIAL_VALUE)
        if (victim.isMajorCiv) victim.ai.diplomacy.memoryOf(declarer).warProgress = define(victim, AiDefine.WAR_PROGRESS_INITIAL_VALUE)
    }

    /**
     *  CvDiplomacyAI::DoUpdateWarProgressScores: each turn the score towards every player we can evaluate ([validPlayers]) goes down
     *  (WAR_PROGRESS_DECAY_VS_STRONGER, _EQUAL or _WEAKER by their military strength compared to ours); towards the other living
     *  players it is WAR_PROGRESS_INITIAL_VALUE, towards the dead 0.
     */
    fun doTurn(civ: Civilization, validPlayers: List<Civilization>) {
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || other.isBarbarian || other.isSpectator) continue
            val memory = civ.ai.diplomacy.memories[other.civID] ?: continue
            memory.warProgress = when {
                other in validPlayers -> {
                    val strength = WarStateAi.militaryStrengthComparedToUs(civ, other) ?: StrengthLevel.Average
                    memory.warProgress + define(civ, when {
                        strength > StrengthLevel.Average -> AiDefine.WAR_PROGRESS_DECAY_VS_STRONGER
                        strength < StrengthLevel.Average -> AiDefine.WAR_PROGRESS_DECAY_VS_WEAKER
                        else -> AiDefine.WAR_PROGRESS_DECAY_VS_EQUAL
                    })
                }
                other.isAlive() -> define(civ, AiDefine.WAR_PROGRESS_INITIAL_VALUE)
                else -> 0
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }

    /** CvUnit::kill, a combat unit of [owner] killed by [killer]: WAR_PROGRESS_KILLED_UNIT for the killer, _LOST_UNIT for the owner */
    fun onUnitKilled(owner: Civilization, killer: Civilization) {
        change(killer, owner, define(killer, AiDefine.WAR_PROGRESS_KILLED_UNIT))
        change(owner, killer, define(owner, AiDefine.WAR_PROGRESS_LOST_UNIT))
    }

    /** CvUnit::kill, a civilian of [owner] captured or killed by [captor]: settlers count as WAR_PROGRESS_*_SETTLER, others _WORKER */
    fun onCivilianLost(owner: Civilization, captor: Civilization, settler: Boolean) {
        if (owner.isBarbarian || captor.isBarbarian) return
        change(captor, owner, define(captor, if (settler) AiDefine.WAR_PROGRESS_CAPTURED_SETTLER else AiDefine.WAR_PROGRESS_CAPTURED_WORKER))
        change(owner, captor, define(owner, if (settler) AiDefine.WAR_PROGRESS_LOST_SETTLER else AiDefine.WAR_PROGRESS_LOST_WORKER))
    }

    /**
     *  CvPlayer::acquireCity: WAR_PROGRESS_CAPTURED_CITY for the conqueror, _LOST_CITY for the loser, times WAR_PROGRESS_CAPITAL_MULTIPLIER %
     *  for the loser's original capital, else WAR_PROGRESS_HOLY_CITY_MULTIPLIER % for the holy city of the religion a major loser founded.
     *  Call before the city changes hands.
     */
    fun onCityConquered(conqueror: Civilization, loser: Civilization, city: City) {
        if (conqueror.isBarbarian || loser.isBarbarian) return
        val ownReligion = loser.religionManager.religion?.takeIf { it.foundingCivName == loser.civName }
        val percent = when {
            city.isOriginalCapital && city.foundingCivObject == loser -> define(conqueror, AiDefine.WAR_PROGRESS_CAPITAL_MULTIPLIER)
            loser.isMajorCiv && ownReligion != null && city.isHolyCityOf(ownReligion.name) -> define(conqueror, AiDefine.WAR_PROGRESS_HOLY_CITY_MULTIPLIER)
            else -> 100
        }
        change(conqueror, loser, define(conqueror, AiDefine.WAR_PROGRESS_CAPTURED_CITY) * percent / 100)
        change(loser, conqueror, define(loser, AiDefine.WAR_PROGRESS_LOST_CITY) * percent / 100)
    }

    /** CvUnit::pillage at war: WAR_PROGRESS_PILLAGED_IMPROVEMENT and _LOST_IMPROVEMENT, times the high-value multiplier for a [highValue] tile */
    fun onPillage(pillager: Civilization, owner: Civilization, highValue: Boolean) {
        val percent = if (highValue) define(pillager, AiDefine.WAR_PROGRESS_HIGH_VALUE_PILLAGE_MULTIPLIER) else 100
        change(pillager, owner, define(pillager, AiDefine.WAR_PROGRESS_PILLAGED_IMPROVEMENT) * percent / 100)
        change(owner, pillager, define(owner, AiDefine.WAR_PROGRESS_LOST_IMPROVEMENT) * percent / 100)
    }
}

package com.unciv.logic.automation.diplomacy

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality

/**
 *  Vox Populi's backstabber test (CvDiplomacyAI::TestUntrustworthyFriends / TestOnePlayerUntrustworthyFriend, CvDiplomacyAIHelpers::
 *  BackstabbedPlayer; Release-5.4.8, roadmap D7): whom we, personally, hold for a backstabber, kept in [MajorCivMemory.untrustworthy].
 *  BNW had one answer for everybody (a player who attacked or denounced friends of anyone); here each observer decides, and it is
 *  mostly about its own friends. Unciv has no teams and no vassals (D3): VP's loops over a team are the player, the vassal terms are
 *  none, and neither is the always-at-war option.
 */
object UntrustworthyAi {

    /**
     *  CvDiplomacyAI::TestBackstabbingPenalties and TestUntrustworthyFriends, in that order: forgives the betrayals that expired, then sets
     *  [MajorCivMemory.untrustworthy] for every living major we have met.
     */
    fun testUntrustworthyFriends(civ: Civilization, rules: AiRuleset) {
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || other == civ || other.isSpectator) continue
            OpinionAi.testBackstabbingPenalties(civ, civ.ai.diplomacy.memoryOf(other), rules)
        }
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || other == civ || other.isSpectator) continue
            civ.ai.diplomacy.memoryOf(other).untrustworthy = other.isAlive() && civ.knows(other) && testOne(civ, other, rules)
        }
    }

    /**
     *  CvDiplomacyAIHelpers::BackstabbedPlayer: [backstabber] denounced [victim] or declared war on it while friends, attacked it after
     *  resurrecting it, broke (with [includeDoFEnd]) a friendship with it, or broke a promise not to declare war on it or not to attack
     *  a city-state it protects (VP's broken vassal agreement: none).
     */
    fun backstabbedPlayer(victim: Civilization, backstabber: Civilization, includeDoFEnd: Boolean, rules: AiRuleset): Boolean {
        val memory = victim.ai.diplomacy.memories[backstabber.civID] ?: return false
        return memory.friendDenouncedUs || memory.friendDeclaredWarOnUs || memory.resurrectorAttackedUs ||
            (includeDoFEnd && isDoFBroken(victim, backstabber, rules)) || memory.brokenMilitaryPromise || memory.brokenAttackCityStatePromise
    }

    /**
     *  CvDiplomacyAI::IsDoFBroken after TestBackstabbingPenalties: [other] ended a friendship with [civ] (war or denouncement) less than
     *  DOF_BROKEN_TURNS_UNTIL_FORGIVEN turns ago (x the game speed's opinion duration), or, for an AI that is friends with them again
     *  (a friend opinion or better), less than DOF_BROKEN_TURNS_UNTIL_FORGIVEN_FRIENDS (at least DOF_BROKEN_BACKSTAB_TIMER) turns ago.
     *  The original clears the flag when it expires; here the turn of the break stays, so this tests the time at each call.
     */
    fun isDoFBroken(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val memory = civ.ai.diplomacy.memories[other.civID] ?: return false
        if (memory.doFBrokenTurn < 0) return false
        var timeout = rules.define(AiDefine.DOF_BROKEN_TURNS_UNTIL_FORGIVEN) * DiplomacyTurn.opinionDurationPercent(civ) / 100
        if (!civ.isHuman() && memory.opinion >= MajorCivOpinion.Friend)
            timeout = maxOf(rules.define(AiDefine.DOF_BROKEN_TURNS_UNTIL_FORGIVEN_FRIENDS), rules.define(AiDefine.DOF_BROKEN_BACKSTAB_TIMER))
        return civ.gameInfo.turns - memory.doFBrokenTurn < maxOf(1, timeout)
    }

    /** CvGame::GetNumMajorCivsEver: every major civilization of the game, dead or alive */
    fun numMajorCivsEver(civ: Civilization): Int = civ.gameInfo.civilizations.count { it.isMajorCiv && !it.isSpectator }

    /**
     *  CvDiplomacyAI::TestOnePlayerUntrustworthyFriend. Yes for one who holds our original capital or holy city or took them (unless they
     *  resurrected us: then only taking them counts), betrayed or nuked us. A human's shadow AI stops there. Otherwise we weigh how they
     *  treated the others we have met, skipping those between whom and us a friendship was broken before: for our friends and allies
     *  any betrayal counts; for the rest only when they matter to us (not when we are a backstabber ourselves, at war with or denounced
     *  them, they are unforgivable, a critical warmonger threat, a conqueror of a third of the majors or our biggest competitor, unless
     *  backstabbing could get us killed: half the majors gone, or the betrayer a conqueror of a third of them) and more than we tolerate:
     *  broken promises (3, or 2 from loyalty 6; plus 2 when forgiving above 8), friends denounced (2, 1 from loyalty 5; plus 1 for a forgiving
     *  leader) and wars on friends (1, 0 from loyalty 4), at most 2, 1 and 0 when dangerous, more for a liberator of our key cities
     *  (broken promises x 2, one more denouncement and war). One who resurrected us counts only for attacking us after.
     */
    private fun testOne(civ: Civilization, other: Civilization, rules: AiRuleset): Boolean {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        val resurrected = memory.resurrectedUs
        if (!resurrected && (OpinionAi.capitalCaptured(civ, other) || OpinionAi.holyCityCaptured(civ, other))) return true

        // The easy case - they personally betrayed or nuked us
        if (backstabbedPlayer(civ, other, includeDoFEnd = true, rules) || memory.numTimesNuked > 0) return true
        // We should still be grateful for being resurrected, even if they did not give us back our capital
        if (resurrected && (memory.capturedCapital || memory.capturedHolyCity)) return true

        // Can't assume humans will care about others being backstabbed
        if (civ.isHuman()) return false

        var numBrokenAgreements = 0
        var numFriendsDenounced = 0
        var numFriendsAttacked = 0
        val majorsEver = numMajorCivsEver(civ)

        // Don't tolerate backstabbing if it could get us killed!
        val dangerous = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } <= majorsEver / 2 ||
            VictoryProgressAi.numMajorsConquered(other) >= majorsEver / 3
        // If true, ignore backstabbing against anyone who isn't too important
        val backstabber = WarSanityAi.isBackstabber(civ) && !dangerous

        for (loop in civ.gameInfo.civilizations) {
            if (!loop.isMajorCiv || loop == civ || loop == other || loop.isSpectator || !civ.knows(loop)) continue
            if (WarSanityAi.ignoresBackstabbing(civ, loop)) continue

            // Clear any expired penalties since those won't be updated automatically if the player is dead
            val theirMemory = loop.ai.diplomacy.memories[other.civID]
            if (!loop.isAlive() && theirMemory != null) OpinionAi.testBackstabbingPenalties(loop, theirMemory, rules)

            // Do we particularly care about this player's opinion?
            val priority = OpinionAi.isFriendOrAlly(civ, loop)

            // If they're not a priority and it's not dangerous, we might ignore any backstabbing towards them
            if (!priority && !dangerous) {
                if (backstabber || civ.isAtWarWith(loop) || OpinionAi.isDenouncedPlayer(civ, loop)) continue
                if (ai.opinionOf(loop) == MajorCivOpinion.Unforgivable || ai.warmongerThreat(loop) == AiThreat.Critical) continue
                if (VictoryProgressAi.numMajorsConquered(loop) >= majorsEver / 3) continue
                if (CompetitorAi.biggestCompetitor(civ) == loop.civID) continue
            }

            if (priority) {
                // This player is important to us - backstabbing is unacceptable!
                if (backstabbedPlayer(loop, other, includeDoFEnd = false, rules)) return true
            } else if (theirMemory != null) {
                if (theirMemory.friendDeclaredWarOnUs || theirMemory.resurrectorAttackedUs) {
                    if (resurrected) {
                        if (theirMemory.resurrectorAttackedUs) return true
                        continue
                    }
                    numFriendsAttacked++
                }
                if (resurrected) continue
                if (theirMemory.friendDenouncedUs) numFriendsDenounced++
                if (theirMemory.brokenMilitaryPromise) numBrokenAgreements++
                if (theirMemory.brokenAttackCityStatePromise) numBrokenAgreements++
            }
        }

        // If we're a backstabber, we don't care who they have backstabbed if they aren't important to us (and they aren't dangerous)
        if (backstabber) return false

        val loyalty = civ.ai.diplomacyValue(DiplomacyPersonality.Loyalty)
        var brokenAgreementTolerance = if (loyalty < 6) 3 else 2
        var friendDenounceTolerance = if (loyalty < 5) 2 else 1
        var friendAttackedTolerance = if (loyalty < 4) 1 else 0
        if (civ.ai.diplomacyValue(DiplomacyPersonality.Forgiveness) > 8) {
            brokenAgreementTolerance += 2
            friendDenounceTolerance++
        }
        // Hard override in dangerous situations
        if (dangerous) {
            brokenAgreementTolerance = 2
            friendDenounceTolerance = 1
            friendAttackedTolerance = 0
        }
        // Be more tolerant if they've liberated one of our key cities
        if (WarSanityAi.isLiberator(civ, other, onlyMajorCities = true)) {
            brokenAgreementTolerance *= 2
            friendDenounceTolerance++
            friendAttackedTolerance++
        }
        return numBrokenAgreements > brokenAgreementTolerance || numFriendsDenounced > friendDenounceTolerance ||
            numFriendsAttacked > friendAttackedTolerance
    }
}

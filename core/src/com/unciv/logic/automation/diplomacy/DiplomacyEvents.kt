package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.unit.BaseUnit

/**
 *  What CvDiplomacyAI remembers of events in the game: the calls other game code makes into it (CvTeam::declareWar, CvTeam::makePeace,
 *  CvPlayer::acquireCity, CvPlayer::DoLiberatePlayer, CvUnit::kill, CvCity's wonder completion, CvMinorCivAI bullying, nukes, culture bombs,
 *  espionage, religion). Called from the Unciv game code at the same moments.
 */
object DiplomacyEvents {

    //region Contact

    /**
     *  CvDiplomacyAI::DoFirstContact / DoFirstContactInitRelationship: [civ] met [other]. Strengths, aggressive postures and the target value
     *  are assessed first, so the opinion and the first approach are chosen with them known.
     */
    fun onMeet(civ: Civilization, other: Civilization) {
        if (!civ.isMajorCiv || other.isSpectator) return
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.turnMet = civ.gameInfo.turns
        if (!other.isMajorCiv || other.cities.isEmpty() || civ.cities.isEmpty()) return
        val rules = civ.gameInfo.ruleset.ai
        WarStateAi.updateOnContact(civ, other)
        DiplomacyTurn.updateEconomicStrength(civ, other, WarStateAi.economicMight(civ), rules)
        DisputeAi.updateMilitaryPosture(civ, other, rules)
        DisputeAi.updateExpansionPosture(civ, other, rules)
        OpinionAi.updateOpinion(civ, other, rules)
        ApproachAi.initApproach(civ, other, rules)
    }
    //endregion

    //region War and peace

    /**
     *  CvTeam::declareWar: the victim's DoSomeoneDeclaredWarOnMe, then every major that knows the declarer runs DoPlayerDeclaredWarOnSomeone and
     *  (not for defensive pacts) counts the attack in its warmonger amounts (ChangeOtherPlayerNumMinorsAttacked / NumMajorsAttacked).
     *  Call after the war state is set and before the friendship flags are removed.
     */
    fun onWarDeclared(declarer: Civilization, victim: Civilization, isDefensivePact: Boolean) {
        val gameInfo = declarer.gameInfo
        val rules = gameInfo.ruleset.ai
        // CvTeam::DoDeclareWar (Vox Populi): the declarer is the aggressor, unless a defensive pact called it in; either side that
        // wanted the war is one too
        if (declarer.isMajorCiv) declarer.ai.diplomacy.memoryOf(victim).aggressor = !isDefensivePact || wantedWar(declarer, victim)
        if (victim.isMajorCiv) victim.ai.diplomacy.memoryOf(declarer).aggressor = wantedWar(victim, declarer)
        WarProgressAi.onWarDeclared(declarer, victim)
        if (!declarer.isMajorCiv) return
        for (observer in gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == declarer || !observer.isAlive() || !observer.knows(declarer)) continue
            playerDeclaredWarOnSomeone(observer, declarer, victim)
            if (isDefensivePact) continue
            val memory = observer.ai.diplomacy.memoryOf(declarer)
            if (victim.isCityState) memory.warmongerAmount += rules.define(AiDefine.WARMONGER_THREAT_MINOR_ATTACKED_WEIGHT)
            // Not counted if the observer is at war with the victim too
            else if (victim.isMajorCiv && (observer == victim || !observer.isAtWarWith(victim)))
                memory.warmongerAmount += rules.define(AiDefine.WARMONGER_THREAT_MAJOR_ATTACKED_WEIGHT)
        }
        // DoPlayerDeclaredWarOnSomeone runs for the declarer too (ePlayer == me)
        playerDeclaredWarOnSomeone(declarer, declarer, victim)
    }

    /** CvTeam::DoDeclareWar's test of a side that wanted the war: an offensive operation against them, an AI's war approach (Conquest
     *  towards a city-state), the army in place for the attack, or (against a major) a coop war being prepared */
    private fun wantedWar(civ: Civilization, target: Civilization): Boolean {
        if (civ.ai.operations.hasAnyOffensiveOperationsAgainst(target)) return true
        val memory = civ.ai.diplomacy.memoryOf(target)
        if (memory.musteringForAttack) return true
        if (target.isCityState) return !civ.isHuman() && civ.ai.minorCivApproaches[target.civID] == MinorCivApproach.Conquest
        if (!civ.isHuman() && memory.approach == MajorCivApproach.War) return true
        val coop = CoopWarAi.globalState(civ, target)
        return coop == CoopWarState.Soon || coop == CoopWarState.Accepted
    }

    /** CvDiplomacyAI::DoPlayerDeclaredWarOnSomeone, from [me]'s point of view */
    private fun playerDeclaredWarOnSomeone(me: Civilization, declarer: Civilization, victim: Civilization) {
        val ai = me.ai.diplomacy
        if (me == declarer || me == victim) {
            val them = if (me == declarer) victim else declarer
            val memory = ai.memoryOf(them)
            memory.numWarsFought++
            var wasFriends = false
            if (them.isMajorCiv) {
                if (OpinionAi.isDoFAccepted(me, them)) {
                    wasFriends = true
                    memory.doFCounter = -666
                }
                // Coop wars planned with the one we now fight are off
                for (entry in memory.coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
                    entry.state = null
                    entry.counter = -666
                }
            }
            if (me == victim) {
                val rules = me.gameInfo.ruleset.ai
                if (declarer.isMajorCiv) memory.numWarsDeclaredOnUs++
                if (wasFriends) {
                    memory.friendDeclaredWarOnUs = true
                    memory.declaredWarOnFriendValue += rules.define(AiDefine.OPINION_WEIGHT_WAR_FRIEND_EACH) * DiplomacyTurn.opinionDurationPercent(me)
                    if (declarer.isMajorCiv) onDoFBetrayed(declarer, me)
                }
                if (declarer.isMajorCiv) {
                    memory.approach = MajorCivApproach.War
                    memory.warFace = WarFace.Neutral
                }
                if (memory.madeMilitaryPromise && !memory.brokenMilitaryPromise)
                    PromiseAi.markBroken(memory, PromiseAi.PromiseKind.Military, me.gameInfo.turns)
                // Clear what made us like them
                memory.numCiviliansReturned = 0
                memory.recentTradeValue = 0
                memory.commonFoeValue = 0
                if (memory.recentAssistValue < 0) memory.recentAssistValue = 0
                declarer.ai.diplomacy.memoryOf(me).doFCounter = -1
            }
            return
        }
        // Someone else's war: did they attack a city-state we protect?
        if (victim.isCityState && WarStateAi.isPlayerValid(me, victim) && me in victim.cityStateFunctions.getProtectorCivs()) {
            val memory = ai.memoryOf(declarer)
            memory.turnsSinceAttackedProtectedMinor = 0
            memory.protectedMinorAttacked = victim.civID
            memory.protectedMinorAttackedUnseen = true
            memory.numProtectedMinorsAttacked++
        }
    }

    /** CvDiplomacyAI::SetDoFAccepted (Vox Populi): a DoF between majors lifts both sides' DoF level one step from the higher of the
     *  two (a betrayed or new friendship to friends, friends to allies, allies to battle brothers) */
    fun onDoFSigned(a: Civilization, b: Civilization) {
        if (!a.isMajorCiv || !b.isMajorCiv) return
        val ours = a.ai.diplomacy.memoryOf(b)
        val theirs = b.ai.diplomacy.memoryOf(a)
        val next = when (maxOf(ours.doFLevel, theirs.doFLevel)) {
            DoFLevel.Untrustworthy, DoFLevel.New -> DoFLevel.Friends
            DoFLevel.Friends -> DoFLevel.Allies
            DoFLevel.Allies, DoFLevel.BattleBrothers -> DoFLevel.BattleBrothers
        }
        ours.doFLevel = next
        theirs.doFLevel = next
    }

    /** Vox Populi's ending of a friendship by war (DoSomeoneDeclaredWarOnMe), denouncement (DoDenouncePlayer) or statement
     *  ([onDoFEnded]): both sides' DoF level becomes untrustworthy, the [victim] notes when the [betrayer] broke it (SetDoFBroken), and the
     *  [betrayer] has ended a friendship this turn */
    fun onDoFBetrayed(betrayer: Civilization, victim: Civilization) {
        betrayer.ai.diplomacy.memoryOf(victim).doFLevel = DoFLevel.Untrustworthy
        betrayer.ai.diplomacy.endedFriendshipTurn = betrayer.gameInfo.turns
        val memory = victim.ai.diplomacy.memoryOf(betrayer)
        memory.doFLevel = DoFLevel.Untrustworthy
        memory.doFBrokenTurn = victim.gameInfo.turns
    }

    /**
     *  DIPLO_STATEMENT_END_WORK_WITH_US (Vox Populi): [ender] ends its friendship with [friend] without denouncing them. The coop wars
     *  between them are off (CancelCoopWarsWithPlayer with a penalty: one being prepared - our Soon - is a broken promise worth VP's
     *  -300 assist), the friend resents it (another -300), and each other AI that [ender] may deal with and that does not hate it judges:
     *  ending it with a backstabber +75, with one it fights +50 (+25 more when that war goes badly: defensive or worse, VP's troubled),
     *  with one who denounced it +50, with its own friend, pact partner or ally -75 (-150 if it hates the ender). Our recent assist value
     *  has the opposite sign of VP's. VP's coop war agreement score has no input.
     */
    fun onDoFEnded(ender: Civilization, friend: Civilization) {
        if (!ender.isMajorCiv || !friend.isMajorCiv) return
        val friendAi = friend.ai.diplomacy
        val friendMemory = friendAi.memoryOf(ender)
        for (entry in friendMemory.coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
            if (entry.state == CoopWarState.Soon) {
                PromiseAi.markBroken(friendMemory, PromiseAi.PromiseKind.CoopWar, friend.gameInfo.turns)
                friendAi.changeRecentAssistValue(ender, 300)
            }
            entry.state = null
            entry.counter = -666
        }
        for (entry in ender.ai.diplomacy.memoryOf(friend).coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
            entry.state = null
            entry.counter = -666
        }
        friendAi.changeRecentAssistValue(ender, 300)
        for (third in ender.gameInfo.civilizations) {
            if (third == ender || third == friend || !third.isMajorCiv || third.isHuman() || !WarStateAi.isPlayerValid(ender, third)) continue
            val theirAi = third.ai.diplomacy
            val tiedToFriend = OpinionAi.isDoFAccepted(third, friend) || theirAi.opinionOf(friend) == MajorCivOpinion.Ally ||
                third.getDiplomacyManager(friend)?.diplomaticStatus == DiplomaticStatus.DefensivePact
            val hatesUs = third.isAtWarWith(ender) || OpinionAi.isDenouncedPlayer(ender, third) || OpinionAi.isDenouncedPlayer(third, ender) ||
                OpinionAi.isUntrustworthyFriend(ender) || theirAi.opinionOf(ender) == MajorCivOpinion.Unforgivable
            if (!hatesUs) {
                if (OpinionAi.isUntrustworthyFriend(friend)) theirAi.changeRecentAssistValue(ender, -75)
                else if (third.isAtWarWith(friend)) {
                    theirAi.changeRecentAssistValue(ender, -50)
                    val warState = WarStateAi.warState(third, friend)
                    if (warState != null && warState <= WarState.Defensive) theirAi.changeRecentAssistValue(ender, -25)
                }
                else if (OpinionAi.isDenouncedPlayer(friend, third)) theirAi.changeRecentAssistValue(ender, -50)
                else if (tiedToFriend) theirAi.changeRecentAssistValue(ender, 75)
            } else if (tiedToFriend) theirAi.changeRecentAssistValue(ender, 150)
        }
        onDoFBetrayed(ender, friend)
    }

    /** CvTeam::makePeace: both sides run DoWeMadePeaceWithSomeone and remember the turn of the treaty */
    fun onPeace(civ: Civilization, other: Civilization) {
        weMadePeaceWith(civ, other)
        weMadePeaceWith(other, civ)
    }

    /** CvDiplomacyAI::DoWeMadePeaceWithSomeone */
    private fun weMadePeaceWith(civ: Civilization, other: Civilization) {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        memory.turnMadePeace = civ.gameInfo.turns
        // CvDiplomacyAI::ResetSentAttackProtectedMinorTaunts: at peace with a city-state the taunts about it start over
        if (other.isCityState) for (tauntMemory in ai.memories.values) tauntMemory.sentAttackProtectedMinorTaunts.remove(other.civID)
        memory.musteringForAttack = false
        memory.aggressor = false
        if (!other.isMajorCiv) return
        // Coop wars against them are over
        for (partnerMemory in ai.memories.values) partnerMemory.coopWars[other.civID]?.state = null
        // Bias against another war right away
        if (memory.approach == MajorCivApproach.War) memory.approach = MajorCivApproach.Neutral
    }
    //endregion

    //region War damage, conquest, liberation

    /**
     *  CvUnit::kill: a combat unit of [owner] killed by [killer]. The war damage is the unit's value against the strongest unit of its
     *  domain its owner can build ([WarDamageAi.unitWarValue]), applied by the killer ([WarDamageAi.applyWarDamage]); the killer also
     *  notes it in its estimate of the owner's losses (BNW's record, read by its war projection and taunts).
     */
    fun onUnitKilled(owner: Civilization, killer: Civilization, unit: BaseUnit) {
        if (owner.isBarbarian || killer.isBarbarian || unit.getAiPower() <= 0) return
        val value = WarDamageAi.unitWarValue(unit, owner)
        WarDamageAi.applyWarDamage(killer, owner, value)
        WarProgressAi.onUnitKilled(owner, killer)
        if (killer.isMajorCiv) killer.ai.diplomacy.memoryOf(owner).theirWarValueLostToUs += value
    }

    /**
     *  CvPlayer::acquireCity by conquest: the war damage (CvUnitCombat: the conqueror applies the city's [WarDamageAi.cityWarValue];
     *  BNW's record of the loser's losses notes it too), the loser forgets liberation credit (Vox Populi: the liberated cities, ever
     *  liberated, the liberated capital and holy city, the resurrection) and remembers a major taking its original capital or the holy
     *  city of the religion it founded, and the warmonger penalties (CvDiplomacyAIHelpers::ApplyWarmongerPenalties) unless the city was
     *  the conqueror's. Call before the city changes hands.
     */
    fun onCityConquered(conqueror: Civilization, loser: Civilization, city: City) {
        if (conqueror.isBarbarian || loser.isBarbarian) return
        val value = WarDamageAi.cityWarValue(city)
        if (conqueror.isMajorCiv) conqueror.ai.diplomacy.memoryOf(loser).theirWarValueLostToUs += value
        WarDamageAi.applyWarDamage(conqueror, loser, value)
        WarProgressAi.onCityConquered(conqueror, loser, city)
        if (loser.isMajorCiv) {
            val memory = loser.ai.diplomacy.memoryOf(conqueror)
            memory.numCitiesLiberated = 0
            memory.liberatedCitiesTurn = -1
            memory.numCitiesEverLiberated = 0
            memory.liberatedCapital = false
            memory.liberatedHolyCity = false
            if (conqueror.isMajorCiv) {
                memory.resurrectedUs = false
                if (city.isOriginalCapital && city.foundingCivObject == loser) memory.capturedCapital = true
                else {
                    val ownReligion = loser.religionManager.religion?.takeIf { it.foundingCivName == loser.civName }
                    if (ownReligion != null && city.isHolyCityOf(ownReligion.name)) memory.capturedHolyCity = true
                }
            }
        }
        // CvPlayer::acquireCity (Vox Populi): the loser counts the cities the conqueror took, the conqueror the turn it last took one
        if (loser.isMajorCiv) loser.ai.diplomacy.memoryOf(conqueror).numCitiesCapturedBy++
        if (conqueror.isMajorCiv) conqueror.ai.diplomacy.memoryOf(loser).lastCityCaptureTurn = conqueror.gameInfo.turns
        // The loser's city count after the loss, as the original applies the penalties once the city has changed hands
        if (conqueror.isMajorCiv && city.foundingCivObject != conqueror) applyWarmongerPenalties(conqueror, loser, loser.cities.size - 1)
    }

    /**
     *  CvDiplomacyAIHelpers::GetWarmongerOffset (SDK source): 1000 × estimated cities on the map / (actual cities × cities the victim has left),
     *  scaled by WARMONGER_ON_CITY_STATE_MULTIPLIER percent when the victim is a City-State ("Minor power targeted, half penalty")
     */
    fun warmongerOffset(conqueror: Civilization, numCitiesRemaining: Int, victimIsCityState: Boolean): Int {
        val gameInfo = conqueror.gameInfo
        val rules = gameInfo.ruleset.ai
        val estimated = rules.worldSize(gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().name).estimatedNumCities
        val actual = gameInfo.civilizations.sumOf { it.cities.size }
        val offset = 1000 * estimated / (maxOf(actual, 1) * numCitiesRemaining)
        return if (victimIsCityState) offset * rules.define(AiDefine.WARMONGER_ON_CITY_STATE_MULTIPLIER) / 100 else offset
    }

    /** CvDiplomacyAIHelpers::ApplyWarmongerPenalties: every major that knows the conqueror, halved if it is at war with the loser too */
    fun applyWarmongerPenalties(conqueror: Civilization, loser: Civilization, loserCitiesLeft: Int) {
        val offset = warmongerOffset(conqueror, maxOf(loserCitiesLeft, 1), loser.isCityState)
        for (observer in conqueror.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == conqueror || !observer.isAlive() || !observer.knows(conqueror)) continue
            val penalty = if (observer.isAtWarWith(loser)) offset / 2 else offset
            observer.ai.diplomacy.memoryOf(conqueror).warmongerAmount += penalty
        }
    }

    /**
     *  CvPlayer::DoLiberatePlayer: [liberator] gave [city] back to [receiver], who counts its liberated capital, its holy city (of the
     *  religion it founded), or else the liberated city (and the cities ever liberated); a dead receiver is also resurrected and forgets
     *  its grievances; the liberator's warmonger amounts go down for everyone who knows it.
     *  Call before the city changes hands, with [receiverWasAlive] the receiver's state before.
     */
    fun onCityLiberated(liberator: Civilization, receiver: Civilization, city: City, receiverWasAlive: Boolean) {
        if (receiver.isMajorCiv) {
            val memory = receiver.ai.diplomacy.memoryOf(liberator)
            val ownReligion = receiver.religionManager.religion?.takeIf { it.foundingCivName == receiver.civName }
            when {
                city.isOriginalCapital && city.foundingCivObject == receiver -> memory.liberatedCapital = true
                ownReligion != null && city.isHolyCityOf(ownReligion.name) -> memory.liberatedHolyCity = true
                else -> {
                    memory.numCitiesLiberated++
                    memory.numCitiesEverLiberated++
                    memory.liberatedCitiesTurn = receiver.gameInfo.turns // CvDiplomacyAI::SetNumCitiesLiberatedBy (Vox Populi)
                }
            }
            if (!receiverWasAlive) {
                memory.resurrectedUs = true
                memory.landDispute = DisputeLevel.None
                memory.wonderDispute = DisputeLevel.None
                memory.minorCivDispute = DisputeLevel.None
                memory.warmongerThreat = AiThreat.None
                memory.numDemandsMade = 0
                memory.demandMadeTurn = -1
                memory.numTimesCultureBombed = 0
                memory.negativeReligiousConversionPoints = 0
                memory.numTimesRobbed = 0
                memory.brokenMilitaryPromise = false
                memory.ignoredMilitaryPromise = false
                memory.brokenBorderPromise = false
                memory.ignoredBorderPromise = false
                memory.brokenExpansionPromise = false
                memory.ignoredExpansionPromise = false
                memory.brokenNoConvertPromise = false
                memory.ignoredNoConvertPromise = false
                memory.brokenNoDiggingPromise = false
                memory.ignoredNoDiggingPromise = false
                memory.brokenSpyPromise = false
                memory.ignoredSpyPromise = false
                memory.brokenCoopWarPromise = false
                memory.promiseTurns.clear()
                memory.numProtectedMinorsKilled = 0
                memory.numProtectedMinorsAttacked = 0
                memory.numProtectedMinorsBullied = 0
                memory.turnsSinceSidedWithProtectedMinor = -1
                memory.friendDenouncedUs = false
                memory.friendDeclaredWarOnUs = false
                memory.numTimesNuked = 0
                receiver.getDiplomacyManager(liberator)?.let {
                    it.negativeArchaeologyPoints = 0
                    it.removeFlag(DiplomacyFlags.Denunciation)
                }
                liberator.getDiplomacyManager(receiver)?.removeFlag(DiplomacyFlags.Denunciation)
            }
        }
        // Negate the warmongering
        val offset = warmongerOffset(liberator, maxOf(receiver.cities.size + 1, 1), receiver.isCityState)
        for (observer in liberator.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == liberator || !observer.isAlive() || !observer.knows(liberator)) continue
            val memory = observer.ai.diplomacy.memoryOf(liberator)
            memory.warmongerAmount = maxOf(0, memory.warmongerAmount - offset)
        }
    }

    /**
     *  CvPlayer::acquireCity -> CvDiplomacyAI::DoPlayerKilledSomeone: [newOwner] took the last city of [oldOwner]. Only a major notifies anyone
     *  (not a city-state, not the barbarians); every other living major that has met it counts one minor or major conquered, and the
     *  protectors of a dead city-state remember the killing.
     */
    fun onLastCityTaken(newOwner: Civilization, oldOwner: Civilization) {
        if (!newOwner.isMajorCiv) return
        GrandStrategyAi.onCivDestroyed(newOwner, oldOwner)
        if (!oldOwner.isCityState) return
        for (observer in oldOwner.cityStateFunctions.getProtectorCivs()) {
            if (!observer.isMajorCiv || observer == newOwner || !observer.isAlive() || !observer.knows(newOwner) || !WarStateAi.isPlayerValid(observer, newOwner)) continue
            val memory = observer.ai.diplomacy.memoryOf(newOwner)
            memory.turnsSinceKilledProtectedMinor = 0
            memory.numProtectedMinorsKilled++
        }
    }
    //endregion

    //region Other grievances

    /** CvCity: [loser] was building [wonder] when [builder] completed it (CvDiplomacyAI::ChangeNumWondersBeatenTo) */
    fun onWonderBeaten(loser: Civilization, builder: Civilization) {
        if (!loser.isMajorCiv || !builder.isMajorCiv || loser == builder) return
        val memory = loser.ai.diplomacy.memoryOf(builder)
        memory.numWondersBeatenTo++
        memory.beatenToWonderTurn = loser.gameInfo.turns
    }

    /** CvCity::processBuilding (Vox Populi): [builder] completed a world wonder (CvPlayer::ChangeWondersConstructed) */
    fun onWonderConstructed(builder: Civilization) {
        if (builder.isMajorCiv) builder.ai.diplomacy.wondersConstructed++
    }

    /** CvMinorCivAI::DoMajorBullied -> DoPlayerBulliedSomeone for every major that has met the bully */
    fun onCityStateBullied(bully: Civilization, cityState: Civilization) {
        for (protector in cityState.cityStateFunctions.getProtectorCivs()) {
            if (!protector.isMajorCiv || protector == bully || !WarStateAi.isPlayerValid(protector, bully)) continue
            val memory = protector.ai.diplomacy.memoryOf(bully)
            memory.numProtectedMinorsBullied++
            memory.turnsSinceBulliedProtectedMinor = 0
            memory.protectedMinorBullied = cityState.civID
            memory.protectedMinorBulliedTurn = bully.gameInfo.turns
        }
        // CvMinorCivAI::m_aiTurnLastBulliedByMajor, as the bully's own memory of the city-state
        bully.ai.diplomacy.memoryOf(cityState).turnLastBulliedByUs = bully.gameInfo.turns
    }

    /** DoFromUIDiploEvent: [protector] sided with its city-state against [bully] */
    fun onSidedWithProtectedMinor(protector: Civilization, bully: Civilization) {
        if (bully.isMajorCiv) bully.ai.diplomacy.memoryOf(protector).turnsSinceSidedWithProtectedMinor = 0
    }

    /** CvUnitCombat nuke damage: [victim] was nuked by [attacker] */
    fun onNuked(victim: Civilization, attacker: Civilization) {
        if (victim.isMajorCiv && victim != attacker) victim.ai.diplomacy.memoryOf(attacker).numTimesNuked++
    }

    /** CvUnit culture bomb (citadel and similar): [owner] lost tiles to [bomber] */
    fun onCultureBombed(owner: Civilization, bomber: Civilization) {
        if (owner.isMajorCiv && owner != bomber) owner.ai.diplomacy.memoryOf(bomber).numTimesCultureBombed++
    }

    /** CvPlayerEspionage: a spy of [thief] stealing from [victim] was identified or killed */
    fun onRobbed(victim: Civilization, thief: Civilization) {
        if (!victim.isMajorCiv) return
        val memory = victim.ai.diplomacy.memoryOf(thief)
        memory.numTimesRobbed++
        memory.robbedTurn = victim.gameInfo.turns
    }

    /** CvPlayerEspionage::ProcessSpy: a spy of [thief] stealing from [victim] was identified (the victim's CvEspionageAI::m_aiTurnLastSpyCaught) */
    fun onSpyCaught(victim: Civilization, thief: Civilization) {
        if (victim.isMajorCiv && thief.isMajorCiv) victim.ai.diplomacy.memoryOf(thief).turnLastSpyCaught = victim.gameInfo.turns
    }

    /** CvPlayerEspionage::ProcessSpy: a spy of [thief] was killed in a city of [victim] (m_aiTurnLastSpyKilled and m_aiNumSpiesKilled of the victim, m_aiTurnLastSpyDied of the thief) */
    fun onSpyKilled(victim: Civilization, thief: Civilization) {
        if (!victim.isMajorCiv || !thief.isMajorCiv) return
        val memory = victim.ai.diplomacy.memoryOf(thief)
        memory.turnLastSpyKilled = victim.gameInfo.turns
        memory.numSpiesKilled++
        thief.ai.diplomacy.memoryOf(victim).turnLastSpyDied = victim.gameInfo.turns
    }

    /**
     *  CvCityReligions: [responsible]'s religion [newMajority] took a city of [owner] who founded another religion: 1 point if the owner's religion was not
     *  the majority there, 25 for the owner's holy city, 3 otherwise (RELIGION_DIPLO_HIT_*).
     */
    fun onCityConverted(owner: Civilization, responsible: Civilization, city: City, oldMajority: String?, newMajority: String) {
        if (!owner.isMajorCiv || owner == responsible) return
        val ownReligion = owner.religionManager.religion?.name ?: return
        if (newMajority == ownReligion) return  // the owner founded the new majority
        if (owner.religionManager.religionState < com.unciv.logic.civilization.managers.ReligionState.Pantheon) return
        val rules = owner.gameInfo.ruleset.ai
        val points = when {
            oldMajority != ownReligion -> rules.define(AiDefine.RELIGION_DIPLO_HIT_INITIAL_CONVERT_FRIENDLY_CITY)
            city.isHolyCityOf(ownReligion) -> rules.define(AiDefine.RELIGION_DIPLO_HIT_CONVERT_HOLY_CITY)
            else -> rules.define(AiDefine.RELIGION_DIPLO_HIT_RELIGIOUS_FLIP_FRIENDLY_CITY)
        }
        val memory = owner.ai.diplomacy.memoryOf(responsible)
        memory.negativeReligiousConversionPoints += points
        memory.religiousConversionTurn = owner.gameInfo.turns
    }

    /** CvPlayer::DoCivilianReturnLogic: [returner] gave back a captured civilian of [owner] */
    fun onCivilianReturned(owner: Civilization, returner: Civilization) {
        if (!owner.isMajorCiv) return
        val memory = owner.ai.diplomacy.memoryOf(returner)
        memory.numCiviliansReturned++
        memory.civiliansReturnedTurn = owner.gameInfo.turns
    }

    /** CvPlayerCulture: [builder] built a Landmark in [owner]'s lands (CvDiplomacyAI::ChangeNumLandmarksBuiltForMe) */
    fun onLandmarkBuilt(owner: Civilization, builder: Civilization) {
        val diplomacy = owner.getDiplomacyManager(builder) ?: return
        diplomacy.landmarksBuiltForUs++
        owner.ai.diplomacy.memoryOf(builder).landmarksBuiltTurn = owner.gameInfo.turns
    }

    /** CvPlayerCulture: [digger] took an artifact or writing from [owner]'s lands (CvDiplomacyAI::ChangeNegativeArchaeologyPoints, Vox Populi) */
    fun onArtifactStolen(owner: Civilization, digger: Civilization) {
        if (!owner.isMajorCiv) return
        val memory = owner.ai.diplomacy.memoryOf(digger)
        memory.numArtifactsStolen++
        memory.stoleArtifactTurn = owner.gameInfo.turns
    }

    /** CvCity::BuyPlot: [buyer] bought a tile within 3 tiles of one of [owner]'s cities (AI_ChangeNumPlotsAcquiredByOtherPlayer) */
    fun onPlotBoughtNear(owner: Civilization, buyer: Civilization, count: Int) {
        if (owner.isMajorCiv && owner != buyer) owner.ai.diplomacy.memoryOf(buyer).numPlotsBoughtNearUs += count
    }
    //endregion
}

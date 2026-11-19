package com.unciv.logic.automation.diplomacy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Readonly
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach

/** A coop war agreement with one partner against one target (CvDiplomacyAI::m_ppaacCoopWarAcceptedState / m_ppaaiCoopWarCounter) */
class CoopWarEntry() : IsPartOfGameInfoSerialization {
    /** null = NO_COOP_WAR_STATE */
    var state: CoopWarState? = null
    /** -1 = never asked, -666 = cancelled */
    var counter = -1

    constructor(state: CoopWarState?, counter: Int) : this() {
        this.state = state
        this.counter = counter
    }

    fun clone() = CoopWarEntry(state, counter)
}

/**
 *  What one player's CvDiplomacyAI keeps about another player (the m_pa* / m_ppa* arrays indexed by that player), by civID in [DiplomacyAi].
 *  Counters use the original's sentinels: -1 = never, -666 = cancelled. Every field has a default, so old saves load with "nothing known".
 */
class MajorCivMemory : IsPartOfGameInfoSerialization {
    // Opinion and approach
    var opinion = MajorCivOpinion.Neutral
    /** null = NO_MAJOR_CIV_APPROACH (not evaluated yet: reads as Neutral) */
    var approach: MajorCivApproach? = null
    var warFace: WarFace? = null
    /** CvDiplomacyAI::GetCachedOpinionWeight: the opinion weight behind [opinion], from the last DoUpdateOnePlayerOpinion */
    var opinionWeight = 0
    /** CvDiplomacyAI::GetPlayerApproachValue: each approach's score after the approach curve, by [MajorCivApproach.ordinal]; empty until first chosen */
    var approachValues = ArrayList<Int>()
    /** CvDiplomacyAI::GetPlayerStrategicApproachValue / GetCivStrategicApproach: the scores and the choice of this turn's strategic pass */
    var strategicApproachValues = ArrayList<Int>()
    var strategicApproach: MajorCivApproach? = null
    /** CvDiplomacyAI::IsMajorCompetitor: set each turn by [CompetitorAi.updateMajorCompetitors] */
    var majorCompetitor = false

    // War
    /** A war being prepared or a demand being backed ([DiplomacyTurn]); null otherwise */
    var warGoal: WarGoal? = null
    /** CvDiplomacyAI::IsWantsPeaceWithPlayer as of the last peace update ([PeaceAi]) */
    var wantsPeace = false
    var warProjection: WarProjection? = null
    var lastWarProjection: WarProjection? = null
    var warDamageLevel = WarDamageLevel.None
    /** CvDiplomacyAI::m_paiWarValueLost: what we lost in wars against them */
    var warValueLost = 0
    /** CvDiplomacyAI::m_ppaiOtherPlayerWarValueLost[them][us]: what they lost to us, as we estimate it */
    var theirWarValueLostToUs = 0
    var theirWarDamageLevel = WarDamageLevel.None
    var economicStrength: StrengthLevel? = null
    var militaryThreat = AiThreat.None
    var warmongerThreat = AiThreat.None
    /** CvDiplomacyAI::m_aiWarmongerAmountTimes100: the warmongering we hold against them, in hundredths ([WarmongerAi]) */
    var warmongerAmountTimes100 = 0
    /** CvDiplomacyAI::GetOtherPlayerNumMinorsAttacked / NumMajorsAttacked / NumPlayersNuked: what we have seen them do (each adds to the next penalty) */
    var numMinorsAttacked = 0
    var numMajorsAttacked = 0
    var numPlayersNuked = 0
    /**
     *  CvMinorCivAI::GetTurnLastAttacked, kept by the attacker: the game turn we last declared war on this city-state, -1 never; one
     *  attacked within 50 turns counts for no new warmongering ([WarmongerAi.onMinorAttacked])
     */
    var turnLastDeclaredWarOnThem = -1
    /** CvPlayer::m_aiMostRecentWarScore: our war score against them when the last war with them ended, as the game speed scales it ([WarDamageAi]) */
    var mostRecentWarScore = 0
    var treatyWillingToOffer = PeaceTreatyType.None
    var treatyWillingToAccept = PeaceTreatyType.None
    var numWarsFought = 0
    /** CvDiplomacyAI::GetNumWarsDeclaredOnUs (Vox Populi): how many times they declared war on us */
    var numWarsDeclaredOnUs = 0
    /** CvTeam::GetTurnMadePeaceTreatyWithTeam: game turn, -1 = never */
    var turnMadePeace = -1
    /** CvTeam::GetNumTurnsLockedIntoWar */
    var turnsLockedIntoWar = 0
    /** CvDiplomacyAI::m_pabMusteringForAttack: our sneak attack operation against them finished with the army in position; war is declared next (see [WarAi]) */
    var musteringForAttack = false
    /** CvDiplomacyAI::IsAggressor (Vox Populi): we started this war or wanted it ([DiplomacyEvents.onWarDeclared]), until peace */
    var aggressor = false
    /** CvDiplomacyAI::GetWarProgressScore (Vox Populi): how our war with them goes, kept by [WarProgressAi] */
    var warProgress = 0

    // Disputes and postures
    var landDispute = DisputeLevel.None
    var lastTurnLandDispute = DisputeLevel.None
    var wonderDispute = DisputeLevel.None
    var minorCivDispute = DisputeLevel.None
    var numWondersBeatenTo = 0
    var expansionPosture = AggressivePosture.None
    var plotBuyingPosture = AggressivePosture.None
    var militaryPosture = AggressivePosture.None
    var lastTurnMilitaryPosture = AggressivePosture.None

    // Memories feeding opinion
    var recentTradeValue = 0
    var commonFoeValue = 0
    /**
     *  CvCityAI::AI_GetNumPlotsAcquiredByOtherPlayer summed over our cities: the weight (1 to 3) of the tiles they bought within 3 tiles of our
     *  cities, see [DiplomacyEvents.onPlotBought]; reset when they refuse to stop ([PromiseAi.onDemandRefused])
     */
    var numPlotsBoughtNearUs = 0
    var recentAssistValue = 0
    var numCiviliansReturned = 0
    var numCitiesLiberated = 0
    /** CvDiplomacyAI::GetNumCitiesCapturedBy (Vox Populi): cities of ours this player conquered */
    var numCitiesCapturedBy = 0
    /** CvPlayer::GetLastCityCaptureTurn (Vox Populi): the turn we last conquered a city of this player, -1 never */
    var lastCityCaptureTurn = -1
    var resurrectedUs = false
    var numTimesCultureBombed = 0
    var negativeReligiousConversionPoints = 0
    var numTimesNuked = 0
    var numTimesRobbed = 0
    /**
     *  Vox Populi's turn stamps of the stacked deeds (CvDiplomacyAI::Get*Turn): set whenever the count changes and is above 0, -1 at 0;
     *  the counts halve once per duration ([OpinionAi.testOpinionModifiers]) and their opinion terms fade ([OpinionTimers.adjust])
     */
    var civiliansReturnedTurn = -1
    var landmarksBuiltTurn = -1
    var intrigueSharedTurn = -1
    var robbedTurn = -1
    var beatenToWonderTurn = -1
    var religiousConversionTurn = -1
    /** CvDiplomacyAI::GetNegativeArchaeologyPoints (Vox Populi): artifacts they dug up in our lands, and the turn of the last */
    var numArtifactsStolen = 0
    var stoleArtifactTurn = -1
    /** CvDiplomacyAI::GetNumCitiesEverLiberatedBy (Vox Populi): cities of ours they ever liberated, as long as they took none since */
    var numCitiesEverLiberated = 0
    /** CvDiplomacyAI::IsPlayerLiberatedCapital / IsPlayerLiberatedHolyCity (Vox Populi): they gave back our capital / holy city */
    var liberatedCapital = false
    var liberatedHolyCity = false
    /** CvDiplomacyAI::IsPlayerCapturedCapital / IsPlayerCapturedHolyCity (Vox Populi): they took our original capital / holy city */
    var capturedCapital = false
    var capturedHolyCity = false
    /** CvDiplomacyAI::GetCivilianKillerValue (Vox Populi): their razing of our cities, capped, decaying each turn */
    var civilianKillerValue = 0
    var turnsSinceAttackedProtectedMinor = -1
    var numProtectedMinorsAttacked = 0
    var turnsSinceKilledProtectedMinor = -1
    var numProtectedMinorsKilled = 0
    var turnsSinceBulliedProtectedMinor = -1
    var numProtectedMinorsBullied = 0
    var turnsSinceSidedWithProtectedMinor = -1

    // Friendship and denouncements
    /** CvDiplomacyAI::m_paiDoFCounter: turns since the DoF statement/acceptance, -1 never, -666 broken */
    var doFCounter = -1
    /** CvDiplomacyAI::m_paiDenouncedPlayerCounter: turns since we denounced them, -1 never */
    var denouncedCounter = -1
    /** The denouncement we track the expiry of (Unciv keeps the denouncement itself as [com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation]) */
    var denouncementActive = false
    /** CvDiplomacyAI::IsFriendDenouncedUs / GetFriendDenouncedUsTurn (Vox Populi): forgiven after FRIEND_DENOUNCED_US_TURNS_UNTIL_FORGIVEN */
    var friendDenouncedUs = false
    var friendDenouncedUsTurn = -1
    /** CvDiplomacyAI::IsFriendDeclaredWarOnUs / its turn (Vox Populi): forgiven after FRIEND_DECLARED_WAR_ON_US_TURNS_UNTIL_FORGIVEN */
    var friendDeclaredWarOnUs = false
    var friendDeclaredWarOnUsTurn = -1
    /** CvDiplomacyAI::IsResurrectorAttackedUs (Vox Populi): the civilization that resurrected us declared war on us */
    var resurrectorAttackedUs = false
    /** CvDiplomacyAI::GetDoFType (Vox Populi): our friendship's history with them, kept by [DiplomacyEvents.onDoFSigned] and
     *  [DiplomacyEvents.onDoFBetrayed] */
    var doFLevel = DoFLevel.New
    /** CvDiplomacyAI::GetDoFBrokenTurn (Vox Populi): the turn they broke a DoF with us (war or denouncement), -1 = never */
    var doFBrokenTurn = -1
    /** CvDiplomacyAI::GetLiberatedCitiesTurn (Vox Populi): the turn they last liberated a city of ours or resurrected us, -1 = never */
    var liberatedCitiesTurn = -1
    /** CvDiplomacyAI::GetDemandAcceptedTurn (Vox Populi): the turn they last paid a tribute we demanded, -1 = never or refused since */
    var demandAcceptedTurn = -1
    /** CvDiplomacyAI::IsRecklessExpander (Vox Populi): set each turn by [ExpanderAi.updateRecklessExpanders] */
    var recklessExpander = false
    /** CvDiplomacyAI::IsWonderSpammer (Vox Populi): set each turn by [ExpanderAi.updateWonderSpammers] */
    var wonderSpammer = false

    // Coop wars with them as partner, by target civID
    var coopWars = HashMap<String, CoopWarEntry>()

    // Requests, demands, promises
    /** CvDiplomacyAI::GetNumDemandsMade / GetDemandMadeTurn (Vox Populi): the tributes they demanded of us, halving over time, and the last turn */
    var numDemandsMade = 0
    var demandMadeTurn = -1
    /**
     *  CvDiplomacyAI::Get*PromiseTurn (Vox Populi): the turn each of their promises to us was last broken or ignored, by [PromiseKind]
     *  name; the broken and ignored flags below clear once its duration has passed ([PromiseAi.testPromiseExpiry])
     */
    var promiseTurns = LinkedHashMap<String, Int>()
    var madeMilitaryPromise = false
    var brokenMilitaryPromise = false
    var ignoredMilitaryPromise = false
    var brokenExpansionPromise = false
    var ignoredExpansionPromise = false
    var brokenBorderPromise = false
    var ignoredBorderPromise = false
    var brokenAttackCityStatePromise = false
    var ignoredAttackCityStatePromise = false
    var brokenBullyCityStatePromise = false
    var ignoredBullyCityStatePromise = false
    var brokenSpyPromise = false
    var ignoredSpyPromise = false
    var brokenNoConvertPromise = false
    var ignoredNoConvertPromise = false
    var brokenNoDiggingPromise = false
    var ignoredNoDiggingPromise = false
    var brokenCoopWarPromise = false
    var wantsResearchAgreement = false
    /** CvDiplomacyAI::IsWantsDoFWithPlayer / IsWantsToEndDoFWithPlayer (Vox Populi): set each turn by [FriendshipPlanningAi.updateFriendships] */
    var wantsDoF = false
    var wantsToEndDoF = false
    /** CvDiplomacyAI::IsWantsDefensivePactWithPlayer / IsWantsToEndDefensivePactWithPlayer (Vox Populi): set by [PactPlanningAi.updatePacts] */
    var wantsDefensivePact = false
    var wantsToEndDefensivePact = false
    /** CvDiplomacyAI::m_paiPlayerMadeExpansionPromiseTurn: turn they promised not to expand towards us, -1 = never (EverMadeExpansionPromise) */
    var expansionPromiseTurn = -1
    /** CvDiplomacyAI::m_paePlayerExpansionPromiseData: their expansion posture when they promised; the promise breaks when it rises above it */
    var expansionPromisePosture: AggressivePosture? = null
    /** CvDiplomacyAI::m_paiPlayerMadeBorderPromiseTurn and m_paePlayerBorderPromiseData for the plot buying (border) promise */
    var borderPromiseTurn = -1
    var borderPromisePosture: AggressivePosture? = null
    /**
     *  CvDiplomacyAI::GetOtherPlayerProtectedMinorAttacked / Bullied: the last protected city-state they attacked / bullied, by civID.
     *  [protectedMinorAttackedUnseen] stands for "GetTurnsSincePlayerAttackedProtectedMinor == 0": set with the attack, cleared when our
     *  turn's diplomacy ends (the original counts the turns up at the end of its DoTurn); [protectedMinorBulliedTurn] is the game turn of the bullying.
     */
    var protectedMinorAttacked: String? = null
    var protectedMinorAttackedUnseen = false
    var protectedMinorBullied: String? = null
    var protectedMinorBulliedTurn = -1
    /** CvMinorCivAI::GetTurnLastBulliedByMajor of the city-state this memory is about, for us as the bully: game turn, -1 = never */
    var turnLastBulliedByUs = -1
    /** CvEspionageAI::m_aiTurnLastSpyCaught / m_aiTurnLastSpyKilled / m_aiNumSpiesKilled: their spies in our cities, -1 = never */
    var turnLastSpyCaught = -1
    var turnLastSpyKilled = -1
    var numSpiesKilled = 0
    /** CvEspionageAI::m_aiTurnLastSpyDied: one of our spies died in their city, -1 = never */
    var turnLastSpyDied = -1
    /** CvDiplomacyAI::m_paiNumTimesIntrigueSharedBy: how often they warned us of a plot against us (a human sharing what its spies uncovered) */
    var numTimesIntrigueShared = 0
    /** CvDiplomacyAI::m_ppaaiOtherPlayerLastRoundWarValueLost for [theirWarValueLostToUs] (CacheOtherPlayerWarValuesLost) */
    var lastRoundTheirWarValueLostToUs = 0
    /** CvDiplomacyAI::m_pabSentAttackProtectedMinorTaunt: we told them we hurt a city-state they protect, by civID of that city-state */
    var sentAttackProtectedMinorTaunts = HashSet<String>()

    /** The diplo log (DoAddNewStatementToDiploLog): game turn each statement was last sent */
    // Insertion ordered: a HashMap keyed by an enum iterates in identity-hash order, which differs from run to run and made saves of equal games differ
    var statementTurns = LinkedHashMap<DiploStatement, Int>()
    /** Turn of the last statement or offer to them (one per player per turn, DoContactPlayer) */
    var lastContactTurn = -1
    /** CvTeam::GetTurnsSinceMeetingTeam: game turn of first contact, -1 = before this port (counts as long ago) */
    var turnMet = -1

    fun clone(): MajorCivMemory {
        val copy = MajorCivMemory()
        copy.opinion = opinion; copy.approach = approach; copy.warFace = warFace
        copy.opinionWeight = opinionWeight; copy.approachValues = ArrayList(approachValues)
        copy.strategicApproachValues = ArrayList(strategicApproachValues); copy.strategicApproach = strategicApproach
        copy.majorCompetitor = majorCompetitor
        copy.warGoal = warGoal; copy.wantsPeace = wantsPeace; copy.warProjection = warProjection
        copy.lastWarProjection = lastWarProjection; copy.warDamageLevel = warDamageLevel; copy.warValueLost = warValueLost
        copy.theirWarValueLostToUs = theirWarValueLostToUs; copy.theirWarDamageLevel = theirWarDamageLevel
        copy.economicStrength = economicStrength; copy.militaryThreat = militaryThreat; copy.warmongerThreat = warmongerThreat
        copy.warmongerAmountTimes100 = warmongerAmountTimes100; copy.numMinorsAttacked = numMinorsAttacked
        copy.numMajorsAttacked = numMajorsAttacked; copy.numPlayersNuked = numPlayersNuked
        copy.turnLastDeclaredWarOnThem = turnLastDeclaredWarOnThem; copy.mostRecentWarScore = mostRecentWarScore
        copy.treatyWillingToOffer = treatyWillingToOffer
        copy.treatyWillingToAccept = treatyWillingToAccept; copy.numWarsFought = numWarsFought; copy.turnMadePeace = turnMadePeace
        copy.numWarsDeclaredOnUs = numWarsDeclaredOnUs
        copy.turnsLockedIntoWar = turnsLockedIntoWar; copy.musteringForAttack = musteringForAttack; copy.aggressor = aggressor
        copy.warProgress = warProgress
        copy.landDispute = landDispute; copy.lastTurnLandDispute = lastTurnLandDispute; copy.wonderDispute = wonderDispute
        copy.minorCivDispute = minorCivDispute; copy.numWondersBeatenTo = numWondersBeatenTo; copy.expansionPosture = expansionPosture
        copy.plotBuyingPosture = plotBuyingPosture; copy.militaryPosture = militaryPosture
        copy.lastTurnMilitaryPosture = lastTurnMilitaryPosture
        copy.recentTradeValue = recentTradeValue; copy.commonFoeValue = commonFoeValue; copy.recentAssistValue = recentAssistValue
        copy.numPlotsBoughtNearUs = numPlotsBoughtNearUs
        copy.numCiviliansReturned = numCiviliansReturned; copy.numCitiesLiberated = numCitiesLiberated; copy.resurrectedUs = resurrectedUs
        copy.numCitiesCapturedBy = numCitiesCapturedBy; copy.lastCityCaptureTurn = lastCityCaptureTurn
        copy.numTimesCultureBombed = numTimesCultureBombed
        copy.negativeReligiousConversionPoints = negativeReligiousConversionPoints
        copy.numTimesNuked = numTimesNuked; copy.numTimesRobbed = numTimesRobbed
        copy.civiliansReturnedTurn = civiliansReturnedTurn; copy.landmarksBuiltTurn = landmarksBuiltTurn
        copy.intrigueSharedTurn = intrigueSharedTurn; copy.robbedTurn = robbedTurn; copy.beatenToWonderTurn = beatenToWonderTurn
        copy.religiousConversionTurn = religiousConversionTurn
        copy.numArtifactsStolen = numArtifactsStolen; copy.stoleArtifactTurn = stoleArtifactTurn
        copy.numCitiesEverLiberated = numCitiesEverLiberated
        copy.liberatedCapital = liberatedCapital; copy.liberatedHolyCity = liberatedHolyCity
        copy.capturedCapital = capturedCapital; copy.capturedHolyCity = capturedHolyCity
        copy.civilianKillerValue = civilianKillerValue
        copy.turnsSinceAttackedProtectedMinor = turnsSinceAttackedProtectedMinor
        copy.numProtectedMinorsAttacked = numProtectedMinorsAttacked
        copy.turnsSinceKilledProtectedMinor = turnsSinceKilledProtectedMinor; copy.numProtectedMinorsKilled = numProtectedMinorsKilled
        copy.turnsSinceBulliedProtectedMinor = turnsSinceBulliedProtectedMinor; copy.numProtectedMinorsBullied = numProtectedMinorsBullied
        copy.turnsSinceSidedWithProtectedMinor = turnsSinceSidedWithProtectedMinor
        copy.doFCounter = doFCounter; copy.denouncedCounter = denouncedCounter; copy.denouncementActive = denouncementActive
        copy.friendDenouncedUs = friendDenouncedUs; copy.friendDeclaredWarOnUs = friendDeclaredWarOnUs
        copy.friendDenouncedUsTurn = friendDenouncedUsTurn; copy.friendDeclaredWarOnUsTurn = friendDeclaredWarOnUsTurn
        copy.resurrectorAttackedUs = resurrectorAttackedUs
        copy.doFLevel = doFLevel; copy.doFBrokenTurn = doFBrokenTurn; copy.liberatedCitiesTurn = liberatedCitiesTurn
        copy.demandAcceptedTurn = demandAcceptedTurn; copy.recklessExpander = recklessExpander; copy.wonderSpammer = wonderSpammer
        for ((target, entry) in coopWars) copy.coopWars[target] = entry.clone()
        copy.numDemandsMade = numDemandsMade; copy.demandMadeTurn = demandMadeTurn; copy.promiseTurns.putAll(promiseTurns)
        copy.madeMilitaryPromise = madeMilitaryPromise; copy.brokenMilitaryPromise = brokenMilitaryPromise
        copy.ignoredMilitaryPromise = ignoredMilitaryPromise
        copy.brokenExpansionPromise = brokenExpansionPromise; copy.ignoredExpansionPromise = ignoredExpansionPromise
        copy.brokenBorderPromise = brokenBorderPromise; copy.ignoredBorderPromise = ignoredBorderPromise
        copy.brokenAttackCityStatePromise = brokenAttackCityStatePromise
        copy.ignoredAttackCityStatePromise = ignoredAttackCityStatePromise
        copy.brokenBullyCityStatePromise = brokenBullyCityStatePromise; copy.ignoredBullyCityStatePromise = ignoredBullyCityStatePromise
        copy.brokenSpyPromise = brokenSpyPromise; copy.ignoredSpyPromise = ignoredSpyPromise
        copy.brokenNoConvertPromise = brokenNoConvertPromise; copy.ignoredNoConvertPromise = ignoredNoConvertPromise
        copy.brokenNoDiggingPromise = brokenNoDiggingPromise; copy.ignoredNoDiggingPromise = ignoredNoDiggingPromise
        copy.brokenCoopWarPromise = brokenCoopWarPromise; copy.wantsResearchAgreement = wantsResearchAgreement
        copy.wantsDoF = wantsDoF; copy.wantsToEndDoF = wantsToEndDoF
        copy.wantsDefensivePact = wantsDefensivePact; copy.wantsToEndDefensivePact = wantsToEndDefensivePact
        copy.expansionPromiseTurn = expansionPromiseTurn; copy.expansionPromisePosture = expansionPromisePosture
        copy.borderPromiseTurn = borderPromiseTurn; copy.borderPromisePosture = borderPromisePosture
        copy.protectedMinorAttacked = protectedMinorAttacked; copy.protectedMinorAttackedUnseen = protectedMinorAttackedUnseen
        copy.protectedMinorBullied = protectedMinorBullied; copy.protectedMinorBulliedTurn = protectedMinorBulliedTurn
        copy.turnLastBulliedByUs = turnLastBulliedByUs
        copy.turnLastSpyCaught = turnLastSpyCaught; copy.turnLastSpyKilled = turnLastSpyKilled
        copy.numSpiesKilled = numSpiesKilled; copy.turnLastSpyDied = turnLastSpyDied
        copy.numTimesIntrigueShared = numTimesIntrigueShared
        copy.lastRoundTheirWarValueLostToUs = lastRoundTheirWarValueLostToUs
        copy.sentAttackProtectedMinorTaunts.addAll(sentAttackProtectedMinorTaunts)
        copy.statementTurns.putAll(statementTurns); copy.lastContactTurn = lastContactTurn; copy.turnMet = turnMet
        return copy
    }
}

/**
 *  The CvDiplomacyAI state of one civilization (`civ.ai.diplomacy`) and the read API other AI code uses.
 *  The logic that fills it is in [DiplomacyTurn] (assessment, opinions, approaches each turn), [DiplomacyEvents] (what happens in the game)
 *  and [DiplomacyAutomation] (what the AI does). Values are those of the last update: nothing is computed on read.
 */
class DiplomacyAi : IsPartOfGameInfoSerialization {
    /** By civID: majors and city-states we know */
    var memories = HashMap<String, MajorCivMemory>()
    /** CvDiplomacyAI::m_eDemandTargetPlayer, civID */
    var demandTarget: String? = null
    var demandReady = false
    /** CvDiplomacyAI::m_eCSWarTarget and m_eCSBullyTarget (Vox Populi), civIDs: the city-states [CityStateAi.updateMinorCivApproaches] chose */
    var csWarTarget: String? = null
    var csBullyTarget: String? = null
    /** CvDiplomacyAI::GetTotalLandDisputeLevel as of the last [DiplomacyTurn.doTurn] (the disputes it sums change only there) */
    var lastTotalLandDisputeLevel = 0
    /** CvPlayer::m_iTurnsSinceSettledLastCity: turns since this civilization founded a city, -1 = never (counted by [DiplomacyTurn.doTurn]) */
    var turnsSinceSettledLastCity = -1
    /** CvDiplomacyAI::GetBiggestCompetitor, civID; null = none. Set each turn by [CompetitorAi.updateBiggestCompetitor] */
    var biggestCompetitor: String? = null
    /** CvPlayer::m_iMilitaryRating (Vox Populi), -1 = not moved yet (the starting rating): read through [WarDamageAi.militaryRating] */
    var militaryRating = -1
    /** CvDiplomacyAI::IsSaneDiplomaticTarget (Vox Populi) is false for these civIDs: set each turn by [WarSanityAi.updateSaneTargets] */
    var insaneTargets = HashSet<String>()
    /** CvDiplomacyAI::IsPotentialWarTarget (Vox Populi): the civIDs of the majors we might want to attack, set each turn by [WarTargetAi.updateWarTargets] */
    var potentialWarTargets = HashSet<String>()
    /** CvPlayer::GetWondersConstructed (Vox Populi): the world wonders this civilization has built */
    var wondersConstructed = 0
    /** CvDiplomacyAI::GetMostValuableFriend (Vox Populi), civID; null = none. Set each turn by [FriendshipPlanningAi.updateFriendships] */
    var mostValuableFriend: String? = null
    /** CvDiplomacyAI::GetMostValuableAlly (Vox Populi), civID; null = none. Set by [PactPlanningAi.updatePacts] */
    var mostValuableAlly: String? = null
    /** The turn of CvDiplomacyAI::HasEndedFriendshipThisTurn: we last broke a friendship (war, denouncement or ending it), -1 = never */
    var endedFriendshipTurn = -1

    @Transient lateinit var civ: Civilization

    fun clone(): DiplomacyAi {
        val copy = DiplomacyAi()
        for ((id, memory) in memories) copy.memories[id] = memory.clone()
        copy.demandTarget = demandTarget
        copy.demandReady = demandReady
        copy.csWarTarget = csWarTarget
        copy.csBullyTarget = csBullyTarget
        copy.turnsSinceSettledLastCity = turnsSinceSettledLastCity
        copy.lastTotalLandDisputeLevel = lastTotalLandDisputeLevel
        copy.biggestCompetitor = biggestCompetitor
        copy.militaryRating = militaryRating
        copy.insaneTargets.addAll(insaneTargets)
        copy.potentialWarTargets.addAll(potentialWarTargets)
        copy.wondersConstructed = wondersConstructed
        copy.mostValuableFriend = mostValuableFriend
        copy.mostValuableAlly = mostValuableAlly
        copy.endedFriendshipTurn = endedFriendshipTurn
        return copy
    }

    /** The memory of [other], created empty on first use */
    fun memoryOf(other: Civilization): MajorCivMemory = memories.getOrPut(other.civID) { MajorCivMemory() }

    //region Read API (CvDiplomacyAI getters)

    /** CvDiplomacyAI::IsPlayerValid */
    fun isPlayerValid(other: Civilization): Boolean = WarStateAi.isPlayerValid(civ, other)

    /**
     *  CvDiplomacyAI::GetMajorCivApproach. With [hideTrueFeelings] the approach the AI shows: Deceptive looks Friendly, War shows its war face.
     *  Before the first evaluation the approach is Neutral.
     */
    @Readonly
    fun approachTowards(other: Civilization, hideTrueFeelings: Boolean = false): MajorCivApproach {
        val memory = memories[other.civID] ?: return MajorCivApproach.Neutral
        val approach = memory.approach ?: return MajorCivApproach.Neutral
        if (!hideTrueFeelings) return approach
        return when (approach) {
            MajorCivApproach.Deceptive -> MajorCivApproach.Friendly
            MajorCivApproach.War -> when (memory.warFace) {
                WarFace.Hostile -> MajorCivApproach.Hostile
                WarFace.Friendly -> MajorCivApproach.Friendly
                else -> MajorCivApproach.Neutral
            }
            else -> approach
        }
    }

    /** CvDiplomacyAI::GetMajorCivOpinion */
    @Readonly
    fun opinionOf(other: Civilization): MajorCivOpinion = memories[other.civID]?.opinion ?: MajorCivOpinion.Neutral

    /** CvDiplomacyAI::GetWarProjection, null = NO_WAR_PROJECTION_TYPE */
    @Readonly
    fun warProjection(other: Civilization): WarProjection? = memories[other.civID]?.warProjection

    /** CvDiplomacyAI::GetWarGoal, null = NO_WAR_GOAL_TYPE */
    @Readonly
    fun warGoal(other: Civilization): WarGoal? = memories[other.civID]?.warGoal

    /** CvDiplomacyAI::GetTreatyWillingToOffer */
    @Readonly
    fun treatyWillingToOffer(other: Civilization): PeaceTreatyType = memories[other.civID]?.treatyWillingToOffer ?: PeaceTreatyType.None

    /** CvDiplomacyAI::GetTreatyWillingToAccept */
    @Readonly
    fun treatyWillingToAccept(other: Civilization): PeaceTreatyType = memories[other.civID]?.treatyWillingToAccept ?: PeaceTreatyType.None

    /** CvDiplomacyAI::GetPlayerTargetValue (kept by [WarStateAi]) */
    fun targetValue(other: Civilization): TargetValue? = WarStateAi.targetValue(civ, other)

    /** CvDiplomacyAI::GetPlayerMilitaryStrengthComparedToUs (kept by [WarStateAi]) */
    fun militaryStrength(other: Civilization): StrengthLevel? = WarStateAi.militaryStrengthComparedToUs(civ, other)

    /** CvDiplomacyAI::GetPlayerEconomicStrengthComparedToUs */
    @Readonly
    fun economicStrength(other: Civilization): StrengthLevel? = memories[other.civID]?.economicStrength

    /** CvDiplomacyAI::GetMilitaryThreat */
    @Readonly
    fun militaryThreat(other: Civilization): AiThreat = memories[other.civID]?.militaryThreat ?: AiThreat.None

    /** CvDiplomacyAI::GetWarmongerThreat */
    @Readonly
    fun warmongerThreat(other: Civilization): AiThreat = memories[other.civID]?.warmongerThreat ?: AiThreat.None

    /** CvDiplomacyAI::GetLandDisputeLevel */
    @Readonly
    fun landDisputeLevel(other: Civilization): DisputeLevel = memories[other.civID]?.landDispute ?: DisputeLevel.None

    /** CvDiplomacyAI::GetWarDamageLevel: how much [other] has hurt us */
    @Readonly
    fun warDamageLevel(other: Civilization): WarDamageLevel = memories[other.civID]?.warDamageLevel ?: WarDamageLevel.None

    /** CvDiplomacyAI::GetTotalLandDisputeLevel: the land disputes with all players, weighted by AI_DIPLO_LAND_DISPUTE_WEIGHT_* */
    @Readonly
    fun totalLandDisputeLevel(): Int = lastTotalLandDisputeLevel

    /** CvDiplomacyAI::IsWantsPeaceWithPlayer: the war goal has been PEACE for REQUEST_PEACE_TURN_THRESHOLD turns (updated by [DiplomacyTurn]) */
    @Readonly
    fun isWantsPeaceWith(other: Civilization): Boolean = memories[other.civID]?.wantsPeace == true

    /** CvDiplomacyAI::IsWantsResearchAgreementWithPlayer (DoUpdatePlanningExchanges) */
    @Readonly
    fun wantsResearchAgreementWith(other: Civilization): Boolean = memories[other.civID]?.wantsResearchAgreement == true

    /** CvDiplomacyAI::GetNumOurEnemiesPlayerAtWarWith: how many of the valid players we fight are also at war with [other] */
    fun numOurEnemiesAtWarWith(other: Civilization): Int =
        civ.gameInfo.civilizations.count { isPlayerValid(it) && civ.isAtWarWith(it) && other.isAtWarWith(it) }

    /** CvTeam::GetTurnMadePeaceTreatyWithTeam: game turn, -1 = never */
    @Readonly
    fun turnMadePeaceWith(other: Civilization): Int = memories[other.civID]?.turnMadePeace ?: -1

    /** CvDiplomacyAI::GetGlobalCoopWarAcceptedState(ePlayer) >= COOP_WAR_STATE_SOON: a coop war with [other] as partner is agreed against anyone */
    @Readonly
    fun isCoopWarSoon(other: Civilization): Boolean {
        val wars = memories[other.civID]?.coopWars ?: return false
        for (entry in wars.values) {
            val state = entry.state
            if (state != null && state >= CoopWarState.Soon) return true
        }
        return false
    }

    /** DoContactPlayer sends one statement or offer per player per turn: true when one was sent to [other] this turn */
    @Readonly
    fun hasMadeStatementThisTurn(other: Civilization): Boolean = memories[other.civID]?.lastContactTurn == civ.gameInfo.turns

    /** Records a statement or offer to [other] this turn (see [hasMadeStatementThisTurn]) */
    fun markStatementMade(other: Civilization) {
        memoryOf(other).lastContactTurn = civ.gameInfo.turns
    }

    /** The game speed's OpinionDurationPercent, which VP's value changes and caps scale by */
    private fun opinionDuration() = DiplomacyTurn.opinionDurationPercent(civ)

    /** GetMaxRecentTradeValue: DEAL_VALUE_PER_OPINION_WEIGHT x -OPINION_WEIGHT_TRADE_MAX x the game speed's OpinionDurationPercent / 100 */
    fun maxRecentTradeValue(): Int {
        val rules = civ.gameInfo.ruleset.ai
        return maxOf(0, rules.define(AiDefine.DEAL_VALUE_PER_OPINION_WEIGHT) * -rules.define(AiDefine.OPINION_WEIGHT_TRADE_MAX) * opinionDuration() / 100)
    }

    /** GetMaxCommonFoeValue: COMMON_FOE_VALUE_PER_OPINION_WEIGHT x -OPINION_WEIGHT_COMMON_FOE_MAX x OpinionDurationPercent / 100 */
    fun maxCommonFoeValue(): Int {
        val rules = civ.gameInfo.ruleset.ai
        return maxOf(0, rules.define(AiDefine.COMMON_FOE_VALUE_PER_OPINION_WEIGHT) * -rules.define(AiDefine.OPINION_WEIGHT_COMMON_FOE_MAX) * opinionDuration() / 100)
    }

    /**
     *  GetMaxRecentAssistValue / GetMaxRecentFailedAssistValue: ASSIST_VALUE_PER_OPINION_WEIGHT x the size of OPINION_WEIGHT_ASSIST_MAX
     *  (help) or OPINION_WEIGHT_FAILED_ASSIST_MAX (refusals) x OpinionDurationPercent / 100
     */
    fun maxRecentAssistValue(failed: Boolean): Int {
        val rules = civ.gameInfo.ruleset.ai
        val weight = rules.define(if (failed) AiDefine.OPINION_WEIGHT_FAILED_ASSIST_MAX else AiDefine.OPINION_WEIGHT_ASSIST_MAX)
        return rules.define(AiDefine.ASSIST_VALUE_PER_OPINION_WEIGHT) * kotlin.math.abs(weight) * opinionDuration() / 100
    }

    /**
     *  CvDiplomacyAI::ChangeRecentTradeValue: what a deal with [from] was worth to us (CvDealAI::DoAcceptedDeal: in full if generous, else
     *  half); a gain scaled by the game speed's OpinionDurationPercent, kept between 0 and [maxRecentTradeValue]
     */
    fun changeRecentTradeValue(from: Civilization, value: Int) {
        val memory = memoryOf(from)
        val change = if (value > 0) value * opinionDuration() / 100 else value
        memory.recentTradeValue = (memory.recentTradeValue + change).coerceIn(0, maxRecentTradeValue())
    }

    /** CvDiplomacyAI::ChangeCommonFoeValue: damage [from] did to a foe of ours, a gain scaled as [changeRecentTradeValue], kept within 0..[maxCommonFoeValue] */
    fun changeCommonFoeValue(from: Civilization, value: Int) {
        val memory = memoryOf(from)
        val change = if (value > 0) value * opinionDuration() / 100 else value
        memory.commonFoeValue = (memory.commonFoeValue + change).coerceIn(0, maxCommonFoeValue())
    }

    /**
     *  CvDiplomacyAI::ChangeRecentAssistValue (our sign: negative = [from] helped us, positive = they refused; VP's is the other way):
     *  nothing for a human's shadow AI, and a refusal does not count at war with them; scaled by the game speed's OpinionDurationPercent
     *  (the turn's decay, [decay], is not); kept within -[maxRecentAssistValue] of help .. +that of refusals; a decay never crosses 0
     */
    fun changeRecentAssistValue(from: Civilization, value: Int, decay: Boolean = false) {
        if (civ.isHuman()) return
        val memory = memoryOf(from)
        val current = memory.recentAssistValue
        if (decay) {
            if ((current > 0 && current + value < 0) || (current < 0 && current + value > 0)) {
                memory.recentAssistValue = 0
                return
            }
            memory.recentAssistValue = (current + value).coerceIn(-maxRecentAssistValue(false), maxRecentAssistValue(true))
            return
        }
        if (value > 0 && civ.isAtWarWith(from)) return
        memory.recentAssistValue = (current + value * opinionDuration() / 100).coerceIn(-maxRecentAssistValue(false), maxRecentAssistValue(true))
    }

    /** Sets the approach towards [other] directly (scenario setup and tests) */
    @VisibleForTesting
    fun setApproach(other: Civilization, approach: MajorCivApproach, warFace: WarFace? = null) {
        val memory = memoryOf(other)
        memory.approach = approach
        memory.warFace = warFace
    }

    /** Sets the opinion of [other] directly (scenario setup and tests) */
    @VisibleForTesting
    fun setOpinion(other: Civilization, opinion: MajorCivOpinion) {
        memoryOf(other).opinion = opinion
    }
    //endregion
}

package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.BlockLevel
import com.unciv.logic.automation.diplomacy.CompetitorAi
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.diplomacy.VictoryProgressAi
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.Spy
import com.unciv.models.ruleset.SpyMission
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a spy mission, or a counterspy focus, is worth to an AI civilization (CvEspionageAI::GetMissionScore and its helpers GetNegativeMissionScoreForOwner, GetMissionScoreOffensiveBenefits and
 * GetCounterspyEffectsMissionScore, V:CvEspionageClasses.cpp:8393-9383). All of it in whole numbers with the order of divisions of the original, where `x * (a / b)` is not `x * a / b`.
 *
 * Written for the effects that the missions of the ruleset have forms for: stealing technologies and the treasury, sapping a city and stopping its building, siphoning and changing its yields for
 * turns, pillaging resources, roads and fortifications, and the security, Network Points and identification effects of a counterspy focus. The original's terms for stealing a Great Work, no tourism,
 * barbarians, great person points, religious pressure, the needs of a city and destroying buildings belong to missions this ruleset has no effect forms for (waiting for P1, P5, P6 and P8) and are not
 * written, so that a mission is scored whole or not at all (D19 O5).
 *
 * Two lines of the original are not copied, and this is recorded as a departure (D7, measured with `FULL_SIM_ESPIONAGE`):
 * - `iTotalScore = iScore - iIDOffset; iTotalScore = max(0, iScore);` (V:9295, 9298) computes what being identified costs and then drops it; here the offset is taken off, as the comment above it says
 * - the sap term that scores a good moment to sap right now tests the spy's index `> 0` (V:9035), which leaves out the first spy; here it is any spy of ours in the city
 */
object EspionageScoring {
    /** Whether [spy] (or the civilization, with null) could score [mission] in [city]: VP's `-1` is "this mission is not for this city" (own city for an offensive mission, a city-state, a focus for a foreign one) */
    private const val NOT_POSSIBLE = -1

    /**
     * CvEspionageAI::GetMissionScore (V:9072-9383): [spy] is the spy that would do it or is working on it (null when a city is evaluated without one, as the lists of cities do). -1 for a mission that
     * cannot be done at all in this city.
     */
    fun missionScore(civ: Civilization, spy: Spy?, city: City, mission: SpyMission): Int {
        val owner = city.civ
        if (owner.isCityState) return NOT_POSSIBLE
        return if (owner == civ) counterspyScore(civ, spy, city, mission) else offenseScore(civ, spy, city, mission)
    }

    //region Own city, a counterspy focus
    /**
     * The own-city branch of GetMissionScore (V:9091-9198): only a focus; what it does to the city, plus what it would block of what the spies of others can do there. That the focus can be chosen
     * at all (IsCityEventChoiceValidEspionage, V:9097) is for the caller, [EspionageAutomation], to say
     */
    private fun counterspyScore(civ: Civilization, spy: Spy?, city: City, focus: SpyMission): Int {
        if (!focus.isFocus) return NOT_POSSIBLE
        // ESPIONAGE_XP_PER_TURN_COUNTERSPY is 0 in Vox Populi: no base value
        var score = counterspyEffects(civ, city, focus)
        // blocking missions has value only if there are missions and somebody to do them (V:9113-9148; the game option of passive espionage does not exist here; a spy of another civilization counts alive or not as long as it is ours to list: here only the living)
        val missions = civ.gameInfo.ruleset.spyMissions.values.filter { !it.isFocus }
        if (missions.isEmpty()) return score
        val others = civ.gameInfo.getAliveMajorCivs().filter { it != civ && civ.knows(it) }
        if (others.none { it.espionageManager.spyList.any { s -> s.isAlive() } }) return score
        // each mission that this focus blocks is scored by what it would do to us; the enemy can do only one, so the worst of them counts (V:9151-9176)
        var worst = 0
        for (mission in missions) {
            if (focus.name !in mission.blockedBy) continue
            val value = -negativeForOwner(civ, city, civ, mission)
            if (value > worst) worst = value
        }
        // and only if there is evidence that spies are at work in the city (V:9177-9198)
        val robbed = city.civ.espionageManager.timesCityRobbed[city.id] ?: 0
        val spiesInCity = others.count { other -> other.espionageManager.spyList.any { it.isAlive() && it.getCityOrNull() == city } }
        if (spiesInCity > 0) {
            val trainPercent = (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()
            val turnsOfEspionage = max(1, (civ.gameInfo.turns - civ.espionageManager.turnEspionageStarted) * 100 / trainPercent)
            val cooldown = electionCooldown(civ)
            score += worst * (100 + 100 * cooldown * robbed / turnsOfEspionage) / 100
        }
        return score
    }

    /**
     * GetCounterspyEffectsMissionScore (V:8687-8869) for the effects the two foci of the ruleset have: what it takes from the Network Points of spies in the capital, the security it adds or takes, the
     * yields of its specialists and the chance of our own spies to be identified. The yields a focus gives when a spy is found or killed are not scored: "currently the same for all focuses so doesn't
     * affect relative score" (V:8693). Not written, as no focus has the effect: the defence of the city against a siege (V:8735), the religious pressure (V:8747), the needs of the city (V:8755),
     * the yield modifier, growth and improvement yields (V:8784-8822) and the days of We Love the King Day (V:8854)
     */
    private fun counterspyEffects(civ: Civilization, city: City, focus: SpyMission): Int {
        var score = 0
        val ai = civ.ai
        // NP reduction is best used against thefts in the capital (V:8709-8723)
        if (city.isCapital() && focus.counterspyNetworkPointsReduction > 0) {
            score += focus.counterspyNetworkPointsReduction
            if (VictoryProgressAi.isCloseToSpaceshipVictory(civ, civ.gameInfo.ruleset.ai)) score += 150
            else if (ai.isGoingForSpaceshipVictory()) score += 35
        }
        // security: nothing to lose with no security, and twice as much in the capital (V:8724-8733)
        val securityChange = focus.getMatchingUniques(UniqueType.CitySecurity).sumOf { it.params[0].toInt() }
        if (securityChange != 0) {
            val constants = civ.gameInfo.ruleset.modOptions.constants.espionage
            val security = city.espionage.getSecurity()
            val after = min(max(0, securityChange + security), constants.securityMax)
            val capital = if (city.isCapital()) 2 else 1
            score += capital * (after - security) * constants.networkPointsReductionPerSecurity / 100
        }
        // yields of the specialists of the city, each yield by itself: -1 Gold, -1 Science, -1 Culture from every Civil Servant is -3 for each (V:8824-8832, CityEventChoice_SpecialistYieldChange)
        for (unique in focus.getMatchingUniques(UniqueType.StatsFromNamedSpecialist)) {
            val specialists = city.population.specialistAllocations[unique.params[1]]
            score += unique.stats.sum().toInt() * specialists
        }
        // does it change our chance to be found? Not if we are going to kill everyone (V:8837-8852)
        if (focus.identifyChanceReductionGlobal != 0 && !ai.isGoingForWorldConquest()) {
            val offensive = civ.espionageManager.spyList.count { it.isAlive() && it.action == com.unciv.models.SpyAction.GatheringIntel }
            score += offensive * focus.identifyChanceReductionGlobal
        }
        return score
    }
    //endregion

    //region Foreign city, an offensive mission
    /** The foreign-city branch of GetMissionScore (V:9225-9383); that the mission can be done at all (V:9231) is for the caller, [EspionageAutomation], to say */
    private fun offenseScore(civ: Civilization, spy: Spy?, city: City, mission: SpyMission): Int {
        if (mission.isFocus) return NOT_POSSIBLE
        val owner = city.civ
        val diplomacy = civ.ai.diplomacy
        // two considerations: what it gives us, and what it takes from them, of which we count a share by how much we mind them (V:9240-9257). The original also gives 0 to a strategic trade partner (IsStrategicTradePartner, V:9254), which this engine's diplomacy does not have
        var hateFactor = 50 + playerModifier(civ, owner)
        val memory = diplomacy.memoryOf(owner)
        if (memory.victoryDisputeLevel == DisputeLevel.Fierce || memory.victoryBlockLevel == BlockLevel.Fierce) hateFactor = 200
        else if (CompetitorAi.biggestCompetitor(civ) == owner.civID) hateFactor = 100
        else if (civ.getDiplomacyManager(owner)?.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.DefensivePact) hateFactor = min(hateFactor, 0)
        var score = -(negativeForOwner(civ, city, owner, mission) * hateFactor / 100)
        score += offensiveBenefits(civ, city, owner, mission, spy)

        // cheaper and safer missions are better because there are more of them (V:9265-9288)
        val manager = civ.espionageManager
        val expectedNp = manager.networkPointsPerTurn(spy, city, gathering = true)
        val travel = manager.getTravelTurns(city)
        val turnsLostToDeath = travel + civ.gameInfo.ruleset.modOptions.constants.espionage.respawnTurns
        val killChance = mission.killChance
        val cost = mission.getNetworkPointsNeeded(civ.gameInfo.speed)
        val progress = spy?.networkPoints ?: 0
        val leftover = if (spy != null) max(0, progress - cost) else 0
        val effectiveCost = cost + (expectedNp * turnsLostToDeath * killChance / 100)
        val trainPercent = (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()
        val numerator = (2000 * trainPercent / 100) + (turnsLostToDeath * expectedNp)
        // even a free mission starts a cooldown, so there is a minimum cost
        val denominator = leftover + max(electionCooldown(civ) * expectedNp, effectiveCost)
        if (denominator <= 0) return NOT_POSSIBLE
        score *= numerator / denominator
        // normalize to a typical cheap mission
        score /= 2

        // the penalty for being found out is mild against an enemy and bad against a friend (V:9294-9298, where the original forgets to subtract it)
        val identifyChance = max(0, min(100, mission.identifyChance - manager.getIdentifyChanceReduction()))
        val idOffset = ((200 - hateFactor) / 4) * identifyChance / 100
        var total = max(0, score - idOffset)

        // what has been earned towards a mission counts, so that spies do not change their minds all the time (V:9300-9308)
        if (spy != null && score > 0) total += progress * 100 / trainPercent / 5

        // a City-State that wants this mission done makes it worth more (V:9310-9337) needs the quest `SpyMission`, which this ruleset has not: not scored

        // the difference of the Network Points here from the base, which is the security of the city (V:9339-9342)
        total = total * expectedNp / civ.gameInfo.ruleset.modOptions.constants.espionage.networkPointsBase
        return total
    }

    /**
     * CvEspionageAI::GetPlayerModifier(owner, bOnlyDiplo = true) (V:8255-8319): how the way we see the civilization changes how much we want to hurt it. At war or planning one 40; otherwise 33 if either
     * denounced the other, 10 less with a research agreement, 30 less if we are friendly with them and 15 less if neutral or afraid, 15 more if we are guarded, deceptive, hostile (the original's order of
     * approaches is war, hostile, deceptive, guarded, afraid, neutral, friendly); then scaled by our diplomatic balance. NOT written: the 25 less for the civilization we rely on in the World Congress
     * (`GetPrimeLeagueAlly`, set by CvDiplomacyAI::DoUpdatePrimeLeagueAlly from voting histories and proposal support, which the diplomacy AI of this engine does not keep)
     */
    private fun playerModifier(civ: Civilization, owner: Civilization): Int {
        val approach = civ.ai.diplomacy.approachTowards(owner)
        var modifier = 0
        if (civ.isAtWarWith(owner) || approach == MajorCivApproach.War) modifier += 40
        else {
            if (OpinionAi.isDenouncedPlayer(civ, owner) || OpinionAi.isDenouncedPlayer(owner, civ)) modifier += 33
            if (civ.getDiplomacyManager(owner)?.hasFlag(DiplomacyFlags.ResearchAgreement) == true) modifier -= 10
            when (approach) {
                MajorCivApproach.Friendly -> modifier -= 30
                MajorCivApproach.Neutral, MajorCivApproach.Afraid -> modifier -= 15
                MajorCivApproach.Guarded, MajorCivApproach.Deceptive, MajorCivApproach.Hostile -> modifier += 15
                MajorCivApproach.War -> Unit
            }
        }
        modifier *= 100 + 10 * (civ.ai.diplomacyValue(DiplomacyPersonality.DiploBalance) - 5)
        return modifier / 100
    }

    /**
     * GetMissionScoreOffensiveBenefits (V:8873-9069): what we get from doing [mission] to [owner]'s [city]
     */
    private fun offensiveBenefits(civ: Civilization, city: City, owner: Civilization, mission: SpyMission, spy: Spy?): Int {
        var score = 0
        val ai = civ.ai
        val era = max(1, owner.getAiEraNumber())
        val trainPercent = (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()

        // technologies: many behind means catching up is urgent, and for a spaceship it is crucial to be first (V:8878-8891)
        if (mission.hasUnique(UniqueType.StealTechnologies)) {
            score += min(civ.espionageManager.getTechsToSteal(owner).size * 25, 75)
            if (ai.isGoingForSpaceshipVictory()) score += 150
            else if (ai.isGoingForWorldConquest()) score += 25
        }

        // stopping a city that builds a World Wonder we are building too: worth most if the pause lets us finish first. Only with a spy in the city that sees its screen (V:8905-8942).
        // NOT written, a part of this effect left out: the original also scores the projects of the Apollo Program (150 against a civilization we dispute the victory with) and the Manhattan Project (150 at
        // war, 15 if it has attacked us before) in production (V:8956-8978). This engine has them as buildings, not as projects, and a mission that stops a city building scores them as any other building
        val blocked = blockBuildingTurns(mission)
        if (blocked > 0 && spy != null && spy.getBonuses().any { it.hasUnique(UniqueType.SpyRevealsCityScreen) }) {
            val wonder = city.cityConstructions.getCurrentConstruction() as? com.unciv.models.ruleset.Building
            if (wonder != null && wonder.isWonder) {
                // do we build the same wonder somewhere (the first of our cities that does decides)
                val ours = civ.cities.firstOrNull { it.cityConstructions.getCurrentConstruction().name == wonder.name }
                if (ours != null) {
                    val theirTurns = city.cityConstructions.turnsToConstruction(wonder.name)
                    val ourTurns = ours.cityConstructions.turnsToConstruction(wonder.name)
                    // can we build the wonder if we stall them for the turns of the mission?
                    if (ourTurns >= theirTurns && ourTurns < theirTurns + blocked) score += 500
                    // on a tie of the very last turn the civilization that moves first (the lower player number) wins it
                    else if (ourTurns > theirTurns && ourTurns == theirTurns + blocked
                        && civ.gameInfo.civilizations.indexOf(civ) < civ.gameInfo.civilizations.indexOf(owner)) score += 500
                    // "might also be worth it if they're just behind us": the original's condition is that they take MORE turns than we do and at most our turns less 3, which cannot be, so this never
                    // scores (V:8941-8942); written as it is
                    else if (theirTurns > ourTurns && theirTurns <= ourTurns - 3) score += 100
                }
            }
        }

        // the treasury of the owner, less worth the later in the game, and as much more as the speed is faster (V:8998-9009)
        for (unique in mission.getMatchingUniques(UniqueType.StealTreasury)) {
            val percent = unique.params[0].toInt() * 100 / trainPercent
            val amount = owner.gold * percent / 100 / era
            score += amount / 15
        }

        // sapping, at war and with a good target, is worth more with our own border on it, and most when its defence is down (V:9011-9044)
        val sap = sapTurns(mission)
        if (sap > 0) {
            val approachWar = civ.isAtWarWith(owner) || ai.diplomacy.approachTowards(owner) == MajorCivApproach.War
            if (approachWar) {
                val target = ai.diplomacy.targetValue(owner)
                if (target == TargetValue.Favorable || target == TargetValue.Soft) {
                    // the tiles of the city's range, rings 1 to 4 (RING1_PLOTS to RING5_PLOTS), that are ours: where our border is on the city
                    val borderTiles = city.getCenterTile().getTilesInDistanceRange(1..4).count { it.getOwner() == civ }
                    score += min(50, borderTiles * sap * 3)
                    if (ai.isGoingForWorldConquest()) score += sap * 5
                }
            }
            if (spy != null && civ.isAtWarWith(owner)) {
                val ourUnits = city.getCenterTile().neighbors.count { tile -> tile.militaryUnit?.civ == civ }
                if (ourUnits > 0 && city.damageTakenLastTurn > 5) {
                    val damagePercent = 100 * (city.getMaxHealth() - city.health) / city.getMaxHealth()
                    score += damagePercent * ourUnits + city.damageTakenLastTurn * 5
                }
            }
        }

        // siphoning a yield for some turns (V:9047-9063)
        for (unique in mission.getMatchingUniques(UniqueType.SiphonYield)) {
            val stat = Stat.valueOf(unique.params[1])
            var yield = (city.cityStats.currentCityStats[stat] * 100).toInt()
            yield *= unique.params[0].toInt()
            yield *= unique.params[2].toInt()
            yield /= 10000
            yield /= era
            score += yield / 3
        }

        // ESPIONAGE_XP_PER_TURN_OFFENSIVE is 0 in Vox Populi
        return score
    }
    //endregion

    //region What the mission does to the owner
    /**
     * GetNegativeMissionScoreForOwner (V:8393-8684): what [mission] costs [owner] in [city], as a negative number (the original's "score")
     */
    private fun negativeForOwner(civ: Civilization, city: City, owner: Civilization, mission: SpyMission): Int {
        var score = 0
        val era = max(1, owner.getAiEraNumber())
        val trainPercent = (civ.gameInfo.speed.productionCostModifier * 100).roundToInt()

        // stopping a city from building is an annoyance unless a race is on: a World Wonder in production costs 100 (V:8453, 8464). The original also counts the projects (150 for the space race and
        // the nuclear ones, 1000 for the others a victory needs, V:8474-8485): NOT written, this engine has those as buildings (see [offensiveBenefits]), so a project in production costs nothing here
        if (blockBuildingTurns(mission) > 0) {
            val building = city.cityConstructions.getCurrentConstruction()
            if (building is com.unciv.models.ruleset.Building && building.isWonder) score -= 100
        }

        // pillaged resource tiles, by what they are: luxuries 3, strategic 5, the rest 1 (V:8492). The original counts the copies of each resource the city has (GetNumResourceLocal); here the tiles
        // of the city whose resource is improved, which is what a pillage takes away
        for (unique in mission.getMatchingUniques(UniqueType.PillageResourcesAroundCity)) {
            for (tile in city.getTiles()) {
                val resource = tile.tileResource ?: continue
                if (tile.getUnpillagedTileImprovement()?.name !in resource.getImprovements()) continue
                val intensity = when (resource.resourceType) {
                    com.unciv.models.ruleset.tile.ResourceType.Luxury -> 3
                    com.unciv.models.ruleset.tile.ResourceType.Strategic -> 5
                    else -> 1
                }
                score -= intensity * unique.params[0].toInt() / 10
            }
        }

        // roads: worse in the capital, where it breaks the connections (V:8526)
        for (unique in mission.getMatchingUniques(UniqueType.PillageRoadsAroundCity))
            score -= (if (city.isCapital()) 5 else 1) * unique.params[0].toInt() / 10

        // fortifications (the improvements that give no follow-up, which the filter of the mission names), in a war five times worse (V:8533)
        for (unique in mission.getMatchingUniques(UniqueType.PillageAroundCity)) {
            val war = if (owner.isAtWar()) 5 else 1
            val improvements = city.getTiles().count { tile ->
                tile.getUnpillagedTileImprovement()?.matchesFilter(unique.params[1], tile.stateThisTile) == true
            }
            score -= war * improvements * unique.params[0].toInt() / 50
        }

        // the treasury: the share stolen, less for a slower game and for a later era (V:8565)
        for (unique in mission.getMatchingUniques(UniqueType.StealTreasury)) {
            val percent = unique.params[0].toInt() * 100 / trainPercent
            val amount = owner.gold * percent / 100 / era
            score -= amount / 15
        }

        // a city under attack that is sapped is in trouble (V:8579). The original counts the enemy units next to the city (GetNumEnemyUnitsAdjacent); here the next tiles that hold one
        if (sapTurns(mission) > 0 && owner.isAtWar() && city.damageTakenLastTurn > 5) {
            val damagePercent = 100 * (city.getMaxHealth() - city.health) / city.getMaxHealth()
            val attackers = city.getCenterTile().neighbors.count { tile -> tile.militaryUnit?.let { it.civ != owner && owner.isAtWarWith(it.civ) } == true }
            score -= damagePercent * attackers + city.damageTakenLastTurn * 5
        }

        // siphoned yields (V:8602) and yields changed for some turns (V:8616). Not written: the great person points (V:8549), religious pressure (V:8590), destroyed buildings (V:8644, 8657), the Great Works,
        // tourism, barbarians and needs of the start (V:8397-8452), of which this ruleset's missions have no effect
        for (unique in mission.getMatchingUniques(UniqueType.SiphonYield)) {
            val stat = Stat.valueOf(unique.params[1])
            var yield = (city.cityStats.currentCityStats[stat] * 100).toInt()
            yield *= unique.params[0].toInt()
            yield *= unique.params[2].toInt()
            yield /= 10000
            yield /= era
            score -= yield / 3
        }
        for (unique in mission.getMatchingUniques(UniqueType.StatPercentBonusCities)) {
            val turns = unique.getModifiers(UniqueType.ConditionalTimedUnique).firstOrNull()?.params?.get(0)?.toIntOrNull() ?: continue
            val stat = Stat.valueOf(unique.params[1])
            // the base yield, before the percentages of buildings and policies (getBaseYieldRateTimes100)
            var yield = unique.params[0].toInt() * (city.cityStats.baseStatTree.totalStats[stat] * 100).toInt()
            yield *= turns
            yield /= 10000
            yield /= era
            score -= yield / 3
        }
        return score
    }

    /** The turns the missions saps a city for (`SapCityTurns`: `City is sapped <for [n] turns>`) */
    @Readonly
    private fun sapTurns(mission: SpyMission): Int = timedTurns(mission, UniqueType.CitySapped)

    /** The turns it stops a city from building anything (`BlockBuildingTurns`: `Cannot build [All] buildings <for [n] turns>`) */
    @Readonly
    private fun blockBuildingTurns(mission: SpyMission): Int =
        mission.getMatchingUniques(UniqueType.CannotBuildBuildings).filter { it.params[0] == "All" }.maxOfOrNull { timedTurns(it) } ?: 0

    @Readonly
    private fun timedTurns(mission: SpyMission, type: UniqueType): Int = mission.getMatchingUniques(type).maxOfOrNull { timedTurns(it) } ?: 0

    @Readonly
    private fun timedTurns(unique: com.unciv.models.ruleset.unique.Unique): Int =
        unique.getModifiers(UniqueType.ConditionalTimedUnique).firstOrNull()?.params?.get(0)?.toIntOrNull() ?: 0

    /**
     * `GetTurnsBetweenMinorCivElections`, the turns that the original measures a mission's price against (V:9087 `iEspionageCooldown`, CvGame::GetTurnsBetweenMinorCivElections: `ESPIONAGE_TURNS_BETWEEN_CITY_STATE_ELECTIONS` times the speed's election frequency): at standard speed [com.unciv.models.ModConstants.EspionageConstants.missionCooldownTurns],
     * times the speed's modifier, as the cooldown of a spy that did a mission is ([com.unciv.logic.civilization.managers.EspionageManager.getMissionCooldownLeft])
     */
    @Readonly
    private fun electionCooldown(civ: Civilization): Int =
        (civ.gameInfo.ruleset.modOptions.constants.espionage.missionCooldownTurns * civ.gameInfo.speed.modifier).toInt()
    //endregion
}

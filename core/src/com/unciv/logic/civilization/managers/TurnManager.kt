package com.unciv.logic.civilization.managers

import com.unciv.models.metadata.GameSettings
import com.unciv.view.GameView
import com.unciv.UncivGame
import com.unciv.logic.VictoryData
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.civilization.*
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.models.stats.Stats
import com.unciv.ui.components.MayaCalendar
import com.unciv.ui.screens.worldscreen.status.NextTurnProgress
import com.unciv.utils.Log
import yairm210.purity.annotations.Readonly
import kotlin.math.min
import kotlin.math.sqrt
import com.unciv.logic.automation.Timers.Companion.timeThis

class TurnManager(val civInfo: Civilization) {


    fun startTurn(progressBar: NextTurnProgress? = null):Unit = timeThis("TurnManager.startTurn") {
        if (civInfo.isSpectator) return

        for (city in civInfo.cities) city.hasSoldBuildingThisTurn = false

        if (civInfo.cities.isNotEmpty() && civInfo.gameInfo.ruleset.technologies.isNotEmpty())
            civInfo.tech.updateResearchProgress()

        civInfo.cache.updateCivResources() // If you offered a trade last turn, this turn it will have been accepted/declined
        ResourceShortage.refundBuildings(civInfo) // CvCity::DoTurn, before the cities' stats of this turn
        civInfo.monopolies.checkAll() // CvGame::doTurn: every living major civilization looks at every monopoly resource every turn
        InstantYields.checkLuxuryGains(civInfo)
        for (stockpiledResource in civInfo.getCivResourceSupply().filter { it.resource.isStockpiled })
            civInfo.gainStockpiledResource(stockpiledResource.resource, stockpiledResource.amount)

        civInfo.civConstructions.startTurn()
        if (civInfo.faithPurchaseCooldown > 0) civInfo.faithPurchaseCooldown-- // CvPlayer::doTurn
        civInfo.attacksSinceTurnStart.clear()
        civInfo.updateStatsForNextTurn() // for things that change when turn passes e.g. golden age, city state influence

        // Do this after updateStatsForNextTurn but before cities.startTurn
        if (civInfo.playerType == PlayerType.AI && civInfo.gameInfo.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            NextTurnAutomation.automateGoldToSciencePercentage(GameView(civInfo.gameInfo, civInfo).civView)

        // Generate great people at the start of the turn,
        // so they won't be generated out in the open and vulnerable to enemy attacks before you can control them
        if (civInfo.cities.isNotEmpty()) { //if no city available, addGreatPerson will throw exception
            var greatGeneral = civInfo.greatPeople.getNewGreatGeneral()
            while (greatGeneral != null) {
                if (civInfo.gameInfo.ruleset.units.containsKey(greatGeneral))
                    civInfo.units.addUnit(greatGeneral)
                greatGeneral = civInfo.greatPeople.getNewGreatGeneral()
            }
            civInfo.greatPeople.spawnGreatPeopleFromPoints()
            civInfo.religionManager.startTurn()
            if (civInfo.isLongCountActive())
                MayaCalendar.startTurnForMaya(civInfo)
        }

        civInfo.cache.updateViewableTiles() // adds explored tiles so that the units will be able to perform automated actions better
        civInfo.cache.updateCitiesConnectedToCapital()
        civInfo.tradeRoutes.startTurn()
        if (civInfo.isMajorCiv) ArchaeologyFunctions.resolvePendingDigs(civInfo)
        if (civInfo.isMajorCiv) civInfo.gameInfo.league?.refreshCachedSources(civInfo) // CvPlayer::doTurnPostDiplomacy calls DoProcessVotes (V:CvPlayer.cpp:10398-10402)
        if (civInfo.isMajorCiv && civInfo.isHuman()) civInfo.gameInfo.league?.notifyStartTurn(civInfo)
        startTurnFlags()
        updateRevolts()
        civInfo.cityStateFunctions.deliverIncomingUnitGifts()
        updateCityRevolts()

        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponTurnStart, civInfo.state, ignoreCities = true) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }

        // CvPlayer::doTurn at the start of the player's turn: grand strategy, economic/military/city strategies (humans too, as the original)
        civInfo.ai.doTurn()

        for (city in civInfo.cities) {
            progressBar?.increment()
            CityTurnManager(city).startTurn()  // Most expensive part of startTurn
        }

        for (unit in civInfo.units.getCivUnits()) UnitTurnManager(unit).startTurn()

        if (civInfo.playerType == PlayerType.Human && UncivGame.Current.settings.automatedUnitsMoveOnTurnStart) {
            civInfo.hasMovedAutomatedUnits = true
            for (unit in civInfo.units.getCivUnits())
                unit.doAction()
        } else civInfo.hasMovedAutomatedUnits = false

        for (tradeRequest in civInfo.tradeRequests.toList()) { // remove trade requests where one of the sides can no longer supply
            val offeringCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            if (offeringCiv.isDefeated() || !TradeEvaluation().isTradeValid(tradeRequest.trade, civInfo, offeringCiv)) {
                civInfo.tradeRequests.remove(tradeRequest)
                // Yes, this is the right direction. I checked.
                offeringCiv.addNotification("Our proposed trade is no longer relevant!", NotificationCategory.Trade, NotificationIcon.Trade)
                // If it's a counteroffer, remove notification
                civInfo.notifications.removeAll { it.text == "[${offeringCiv.civName}] has made a counteroffer to your trade request" }
            }
        }

        val promotableUnits = civInfo.units.getCivUnits()
            .filter { it.promotions.canBePromoted() }
            .toList()
        if (UncivGame.Current.settings.automaticPromotions == GameSettings.AutomaticPromotions.ForAllUnits) {
            val gameView = GameView(civInfo.gameInfo, civInfo)
            for (unit in promotableUnits) NextTurnAutomation.applyPromotions(gameView.getMapUnitView(unit))
        } else if (promotableUnits.size <= 3) {
            for (unit in promotableUnits) {
                civInfo.addNotification(
                    "[${unit.displayName()}] can be promoted!",
                    listOf(MapUnitAction(unit), PromoteUnitAction(unit)),
                    NotificationCategory.Units,
                    unit.name
                )
            }
        } else {
            civInfo.addNotification(
                "[${promotableUnits.size}] units can be promoted!",
                promotableUnits.map { MapUnitAction(it) },
                NotificationCategory.Units,
                "UnitActionIcons/Promote"
            )
        }

        civInfo.instantYieldNotices.flushDeferred(civInfo) // CvPlayer::doTurn: one notification for each instant yield event that happened again and again
        updateWinningCiv()
    }


    private fun startTurnFlags() {
        // CvMinorCivAI::DoElection under Vox Populi: every city-state holds its election on the turns that are a multiple of the interval, not when a flag of its own runs out (V:CvGame.cpp:11676-11693).
        // The flag of the original's model is dropped so that the two schedules cannot both fire
        if (civInfo.isCityState && civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp) {
            civInfo.removeFlag(CivFlags.TurnsTillCityStateElection.name)
            if (civInfo.gameInfo.isEspionageEnabled() && civInfo.cityStateFunctions.getTurnsUntilElectionLikeVp() == 0) civInfo.cityStateFunctions.holdElectionsLikeVp()
        }
        for (flag in civInfo.flagsCountdown.keys.toList()) {
            // In case we remove flags while iterating
            if (!civInfo.flagsCountdown.containsKey(flag)) continue
            // City revolts only tick while super unhappy with ideological unhappiness.
            if (flag == CivFlags.CityRevoltCountdown.name) continue
            // With uprisingCounterLikeCivV the countdown is civInfo.uprisingCounter; a flag of an older save is dropped
            if (flag == CivFlags.RevoltSpawning.name && civInfo.gameInfo.ruleset.modOptions.constants.uprisingCounterLikeCivV) {
                civInfo.removeFlag(flag)
                continue
            }

            if (flag == CivFlags.CityStateGreatPersonGift.name) {
                val rng = civInfo.state.stateBasedRandom("TurnManager.startTurnFlags")
                val cityStateAllies: List<Civilization> =
                        civInfo.getKnownCivs().filter { it.isCityState && it.allyCiv == civInfo }.toList()
                val givingCityState = cityStateAllies.filter { it.cities.isNotEmpty() }.randomOrNull(rng)

                if (cityStateAllies.isNotEmpty()) civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

                if (civInfo.flagsCountdown[flag]!! < min(cityStateAllies.size, 10) && civInfo.cities.isNotEmpty()
                        && givingCityState != null
                ) {
                    givingCityState.cityStateFunctions.giveGreatPersonToPatron(civInfo)
                    civInfo.flagsCountdown[flag] = civInfo.cityStateFunctions.turnsForGreatPersonFromCityState()
                }

                continue
            }

            if (civInfo.flagsCountdown[flag]!! > 0)
                civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

            if (civInfo.flagsCountdown[flag] != 0) continue

            when (flag) {
                CivFlags.RevoltSpawning.name -> doRevoltSpawn()
                CivFlags.TurnsTillCityStateElection.name -> civInfo.cityStateFunctions.holdElections()
                CivFlags.Anarchy.name -> {
                    civInfo.removeFlag(flag)
                    for (city in civInfo.cities) city.cityStats.update()
                }
            }
        }
        handleDiplomaticVictoryFlags()
    }

    private fun handleDiplomaticVictoryFlags() {
        if (civInfo.flagsCountdown[CivFlags.ShouldResetDiplomaticVotes.name] == 0) {
            civInfo.gameInfo.diplomaticVictoryVotesCast.clear()
            civInfo.gameInfo.diplomaticVictoryVotesProcessed = false
            civInfo.removeFlag(CivFlags.ShowDiplomaticVotingResults.name)
            civInfo.removeFlag(CivFlags.ShouldResetDiplomaticVotes.name)
        }

        if (civInfo.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name] == 0) {
            civInfo.gameInfo.processDiplomaticVictory()
            if (civInfo.gameInfo.civilizations.any { it.victoryManager.hasWon() } ) {
                civInfo.removeFlag(CivFlags.TurnsTillNextDiplomaticVote.name)
            } else {
                civInfo.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 1)
                civInfo.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, civInfo.getTurnsBetweenDiplomaticVotes())
            }
        }

        if (civInfo.flagsCountdown[CivFlags.TurnsTillNextDiplomaticVote.name] == 0) {
            civInfo.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
        }
    }


    fun updateRevolts() {
        if (civInfo.gameInfo.civilizations.none { it.isBarbarian }) {
            // Can't spawn revolts without barbarians ¯\_(ツ)_/¯
            return
        }

        // Vox Populi's uprisings are the counter of the original, driven by the approval rating (see [updateUprisingCounter])
        val constants = civInfo.gameInfo.ruleset.modOptions.constants
        if (constants.uprisingCounterLikeCivV || constants.cityNeedsLikeVp) {
            updateUprisingCounter()
            return
        }

        if (!civInfo.hasUnique(UniqueType.SpawnRebels)) {
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        if (!civInfo.hasFlag(CivFlags.RevoltSpawning.name)) {
            civInfo.addFlag(CivFlags.RevoltSpawning.name, getTurnsBeforeRevolt().coerceAtLeast(1))
            return
        }
    }

    /**
     *  CvPlayer::DoUpdateUprisings: while the empire is very unhappy (the rebel unique) the counter winds down, and an uprising comes when it
     *  hits 0; otherwise it stays as it is, it is not reset. A counter at 0 is seeded when the empire is very unhappy, with half the length
     *  the first time (CvPlayer::DoResetUprisingCounter); after an uprising it starts over at full length.
     *
     *  With [com.unciv.models.ModConstants.cityNeedsLikeVp] the empire is rebellious while its approval rating is under the very unhappy threshold (the
     *  rebel unique is not read: `MOD_BALANCE_VP && IsEmpireVeryUnhappy()`, V:CvPlayer.cpp:20966), and a seed is a quarter shorter when it is super
     *  unhappy (V:CvPlayer.cpp:21030-21034). Vox Populi also waits for the barbarians' release turn, which Unciv has not.
     */
    private fun updateUprisingCounter() {
        val rebellious = if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) civInfo.stats.needs.isVeryUnhappy()
            else civInfo.hasUnique(UniqueType.SpawnRebels)
        if (!rebellious) return
        if (civInfo.uprisingCounter <= 0) {
            civInfo.uprisingCounter = getUprisingCounterLength(firstTime = true)
            return
        }
        civInfo.uprisingCounter--
        if (civInfo.uprisingCounter > 0) return
        doRevoltSpawn()
        civInfo.uprisingCounter = getUprisingCounterLength(firstTime = false)
    }

    /** CvPlayer::DoResetUprisingCounter: 4 + 0..2 turns, longer on slow game speeds, half of it the first time, a quarter less when super unhappy (with the key), at least 1 */
    private fun getUprisingCounterLength(firstTime: Boolean): Int {
        var turns = getTurnsBeforeRevolt()
        if (firstTime) turns /= 2
        if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp && civInfo.stats.needs.isSuperUnhappy())
            turns = turns * 75 / 100
        return turns.coerceAtLeast(1)
    }

    /**
     * CvPlayer::DoUpdateCityRevolts: unlike barbarian uprisings, city revolts need public opinion.
     * Recovery pauses the counter; once it expires the city and recipient are chosen afresh.
     *
     * With [com.unciv.models.ModConstants.cityNeedsLikeVp] a city can revolt while the empire is very unhappy, whatever its public opinion
     * (`IsEmpireSuperUnhappy() || (IsEmpireVeryUnhappy() && MOD_BALANCE_VP)`, V:CvPlayer.cpp:21077); the counter is dropped as soon as it is not
     * (`SetCityRevoltCounter(-1)`, V:CvPlayer.cpp:21134-21137), the seed is [com.unciv.models.ModConstants.NeedsConstants.revoltCounterMin] turns,
     * longer on slow game speeds, a quarter shorter when super unhappy (V:CvPlayer.cpp:21187-21208), and the city and who gets it are
     * chosen by [getCityRevoltTargetLikeVp].
     */
    fun updateCityRevolts() {
        if (!civInfo.isMajorCiv) return
        val needsModel = civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp
        if (needsModel) {
            if (!civInfo.stats.needs.isVeryUnhappy()) {
                civInfo.removeFlag(CivFlags.CityRevoltCountdown.name)
                return
            }
        } else if (civInfo.getHappiness() > -20 || civInfo.culture.publicOpinion.unhappiness <= 0)
            return

        val flag = CivFlags.CityRevoltCountdown.name
        val turnsLeft = civInfo.flagsCountdown[flag] ?: 0
        if (turnsLeft > 0) {
            if (turnsLeft > 1) {
                civInfo.addFlag(flag, turnsLeft - 1)
                return
            }
            civInfo.removeFlag(flag)
            val (city, recipient) = getCityRevoltTarget() ?: return
            val location = city.location.toHexCoord()
            for (observer in civInfo.gameInfo.civilizations) {
                if (!observer.isMajorCiv || observer.isDefeated()) continue
                val text = if (observer == civInfo)
                    "[${city.name}] has revolted and joined [${recipient.civName}]!"
                else
                    "[${city.name}] has revolted from [${civInfo.civName}] and joined [${recipient.civName}]!"
                observer.addNotification(text, location, NotificationCategory.General, NotificationIcon.City)
            }
            // A non-conquest ownership change, retaining the city's population and buildings.
            city.moveToCiv(recipient)
            for (unit in city.getCenterTile().getUnits().filter { it.civ == civInfo }.toList()) {
                unit.currentMovement = 0f
                unit.movement.teleportToClosestMoveableTile()
            }
            return
        }

        val (city, recipient) = getCityRevoltTarget() ?: return
        var turns = if (needsModel) {
            val seed = (civInfo.gameInfo.ruleset.modOptions.constants.needs.revoltCounterMin *
                civInfo.gameInfo.speed.productionCostModifier.coerceAtLeast(1f)).toInt()
            if (civInfo.stats.needs.isSuperUnhappy()) seed * 75 / 100 else seed
        } else (5 * civInfo.gameInfo.speed.productionCostModifier.coerceAtLeast(1f)).toInt()
        turns = turns.coerceAtLeast(1)
        civInfo.addFlag(flag, turns)
        if (civInfo.isHuman())
            civInfo.addNotification(
                "If unhappiness continues, [${city.name}] may revolt and join [${recipient.civName}] in [$turns] turns!",
                city.location.toHexCoord(), NotificationCategory.General, NotificationIcon.City
            )
    }

    /** CvPlayer::GetMostUnhappyCity and GetMostUnhappyCityRecipient. */
    private fun getCityRevoltTarget(): Pair<City, Civilization>? {
        if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) return getCityRevoltTargetLikeVp()
        if (civInfo.cities.size <= 1) return null
        val preferred = civInfo.culture.publicOpinion.preferredIdeology ?: return null
        val eligible = civInfo.gameInfo.civilizations.mapNotNull { other ->
            if (other == civInfo || !other.isMajorCiv || other.isDefeated()
                || other.getIdeology()?.name != preferred) return@mapNotNull null
            val capital = other.getCapital(false) ?: return@mapNotNull null
            val dominance = (other.culture.getInfluenceLevel(civInfo)?.ordinal ?: -1) -
                (civInfo.culture.getInfluenceLevel(other)?.ordinal ?: -1)
            if (dominance <= 0) null else Triple(other, capital.getCenterTile(), dominance)
        }
        if (eligible.isEmpty()) return null

        // CvCity::GetLocalHappiness: building, garrison, policy and religious effects capped at population
        // (the cap is in [CityStats.localHappiness] when the ruleset has localHappinessCappedByPopulation, else it is applied here)
        val city = civInfo.cities.asSequence().filterNot { it.isCapital() }.maxByOrNull { candidate ->
            val population = candidate.population.population
            val localHappiness = candidate.cityStats.localHappiness
                .coerceAtLeast(0f).toInt().coerceAtMost(population)
            val center = candidate.getCenterTile()
            population - localHappiness + eligible.sumOf { (_, capital, dominance) ->
                val distance = center.aerialDistanceTo(capital)
                if (distance >= 100) 0 else sqrt((100 - distance).toDouble()).toInt() * dominance
            }
        } ?: return null
        val center = city.getCenterTile()
        val recipient = eligible.minBy { (_, capital, _) -> center.aerialDistanceTo(capital) }.first
        return city to recipient
    }

    /**
     *  CvPlayer::GetMostUnhappyCity and GetMostUnhappyCityRecipient under `MOD_BALANCE_VP` (V:CvPlayer.cpp:21484-21660).
     *
     *  The city is the one whose `-delta` (the unhappiness over its happiness, above 0) plus the pull of the other civilizations is the greatest, the first of equals:
     *  the capital and the original capitals are out; each living civilization other than us adds the square root of (100 less the distance from its capital,
     *  at least 1) times how much more cultural influence it has over us than we over it; a city we founded counts a quarter, a puppet twice; and (100 less the distance
     *  from our capital, at least 1) / 10 is added.
     *
     *  The recipient is the founder of a city we did not found, if it is alive; else of the living civilizations that are not super unhappy and have a capital,
     *  the one with the highest value: 1000 (times influence difference + 1 when above 0) less twice the distance of its capital from the city, times 3 when its
     *  ideology is the preferred one of our public opinion, among those with a revolutionary wave in our public opinion or that out-influence us by more than 1
     *  while we are super unhappy.
     *
     *  Departures: the source's free city (a new player that takes a city that revolts towards no one) is not made, so a city whose recipient would be a free
     *  city does not revolt; a founder that has been defeated does not get the city back (the source gives it to dead players too);
     *  the influence of dead civilizations over us (5 in the source) and the unused ideology factor (`iUnhappiness *= 20`, never reached in the source) are left out;
     *  the original capital of a civilization is its current capital, as in the rest of our code.
     */
    private fun getCityRevoltTargetLikeVp(): Pair<City, Civilization>? {
        if (civInfo.cities.size <= 1) return null
        val others = civInfo.gameInfo.civilizations.filter { it != civInfo && it.isMajorCiv && !it.isDefeated() && it.getCapital(false) != null }
        val ownCapital = civInfo.getCapital(false)

        var city: City? = null
        var highest = -1
        for (candidate in civInfo.cities) {
            if (candidate.isCapital() || candidate.isOriginalCapital) continue
            var unhappiness = -candidate.cityStats.needs.delta
            if (unhappiness <= 0) continue
            val center = candidate.getCenterTile()
            for (other in others) {
                val dominance = (other.culture.getInfluenceLevel(civInfo)?.ordinal ?: -1) -
                    (civInfo.culture.getInfluenceLevel(other)?.ordinal ?: -1)
                val distanceFactor = (100 - center.aerialDistanceTo(other.getCapital(false)!!.getCenterTile())).coerceAtLeast(1)
                unhappiness += sqrt(distanceFactor.toDouble()).toInt() * dominance
            }
            if (candidate.foundingCivObject == civInfo) unhappiness /= 4
            if (candidate.isPuppet) unhappiness *= 2
            if (ownCapital != null)
                unhappiness += (100 - center.aerialDistanceTo(ownCapital.getCenterTile())).coerceAtLeast(1) / 10
            if (unhappiness > highest) {
                highest = unhappiness
                city = candidate
            }
        }
        if (city == null) return null

        val founder = city.foundingCivObject
        if (founder != null && founder != civInfo) return if (founder.isDefeated()) null else city to founder

        val preferred = civInfo.culture.publicOpinion.preferredIdeology
        val revolutionaryWave = civInfo.culture.publicOpinion.type == PublicOpinionType.RevolutionaryWave
        val center = city.getCenterTile()
        var recipient: Civilization? = null
        var bestValue = 0
        for (other in others) {
            if (other.isEmpireSuperUnhappy()) continue
            val dominance = (other.culture.getInfluenceLevel(civInfo)?.ordinal ?: -1) -
                (civInfo.culture.getInfluenceLevel(other)?.ordinal ?: -1)
            if (!revolutionaryWave && !(civInfo.isEmpireSuperUnhappy() && dominance > 1)) continue
            var value = 1000
            if (dominance > 0) value *= dominance + 1
            value -= center.aerialDistanceTo(other.getCapital(false)!!.getCenterTile()) * 2
            if (preferred != null && other.getIdeology()?.name == preferred) value *= 3
            if (value > bestValue) {
                bestValue = value
                recipient = other
            }
        }
        return recipient?.let { city to it }
    }

    private fun doRevoltSpawn() {
        val barbarians = try {
            // The first test in `updateRevolts` should prevent getting here in a no-barbarians game, but it has been shown to still occur
            civInfo.gameInfo.getBarbarianCivilization()
        } catch (ex: NoSuchElementException) {
            Log.error("Barbarian civilization not found", ex)
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        val random = civInfo.state.stateBasedRandom("TurnManager.doRevoltSpawn")
        val rebelCount: Int
        val spawnCity: City
        val constants = civInfo.gameInfo.ruleset.modOptions.constants
        if (constants.cityNeedsLikeVp) {
            // CvPlayer::DoUprising (V:CvPlayer.cpp:21042-21071): (100 + (cities + 0..cities-1) x 20) / 100 rebels, in the city with the most citizens
            // plus 0..9 (the first of equals)
            val cities = civInfo.cities.size
            if (cities == 0) return
            rebelCount = (constants.needs.uprisingRebelsBase + (cities + random.nextInt(cities)) * constants.needs.uprisingRebelsPerCity) / 100
            spawnCity = civInfo.cities.maxByOrNull { it.population.population + random.nextInt(10) }!!
        } else {
            rebelCount = 1 + random.nextInt(100 + 20 * (civInfo.cities.size - 1)) / 100
            spawnCity = civInfo.cities.maxByOrNull { random.nextInt(it.population.population + 10) } ?: return
        }
        val spawnTile = spawnCity.getTiles().maxByOrNull { rateTileForRevoltSpawn(it) } ?: return
        val unitToSpawn = civInfo.gameInfo.ruleset.units.values.asSequence().filter {
            it.uniqueTo == null && it.isMelee() && it.isLandUnit
                    && !it.hasUnique(UniqueType.CannotAttack) && it.isBuildable(civInfo)
        }.maxByOrNull {
            random.nextInt(1000)
        } ?: return

        repeat(rebelCount) {
            civInfo.gameInfo.tileMap.placeUnitNearTile(
                spawnTile.position,
                unitToSpawn,
                barbarians
            )
        }

        // Will be automatically added again as long as unhappiness is still low enough
        civInfo.removeFlag(CivFlags.RevoltSpawning.name)

        civInfo.addNotification("Your citizens are revolting due to very high unhappiness!", spawnTile.position, NotificationCategory.General, unitToSpawn.name, "StatIcons/Malcontent")
    }

    // Higher is better
    @Readonly
    private fun rateTileForRevoltSpawn(tile: Tile): Int {
        if (tile.isWater || tile.militaryUnit != null || tile.civilianUnit != null || tile.isCityCenter() || tile.isImpassible())
            return -1
        var score = 10
        if (tile.improvement == null) {
            score += 4
            if (tile.resource != null) {
                score += 3
            }
        }
        if (tile.getDefensiveBonus() > 0)
            score += 4
        return score
    }
    
    @Readonly
    private fun getTurnsBeforeRevolt() =
        ((civInfo.gameInfo.ruleset.modOptions.constants.baseTurnsUntilRevolt + civInfo.state.stateBasedRandom("TurnManager.getTurnsBeforeRevolt").nextInt(3)) 
            * civInfo.gameInfo.speed.modifier.coerceAtLeast(1f)).toInt()


    fun endTurn(progressBar: NextTurnProgress? = null):Unit = timeThis("TurnManager.endTurn") {
        // A human's setting: the cities of an AI civ (or an auto-playing one) bombard as part of the tactical AI's attacks (CvTacticalAI)
        if (UncivGame.Current.settings.citiesAutoBombardAtEndOfTurn && !civInfo.isAIOrAutoPlaying())
            NextTurnAutomation.automateCityBombardment(GameView(civInfo.gameInfo, civInfo).civView) // Bombard with all cities that haven't, maybe you missed one

        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponTurnEnd, civInfo.state, ignoreCities = true) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }

        val notificationsLog = civInfo.notificationsLog
        val notificationsThisTurn = Civilization.NotificationsLog(civInfo.gameInfo.turns)
        notificationsThisTurn.notifications.addAll(civInfo.notifications)

        while (notificationsLog.size >= UncivGame.Current.settings.notificationsLogMaxTurns) {
            notificationsLog.removeAt(0)
        }

        if (notificationsThisTurn.notifications.isNotEmpty())
            notificationsLog.add(notificationsThisTurn)

        civInfo.notifications.clear()
        civInfo.notificationCountAtStartTurn = null

        if (civInfo.isDefeated() || civInfo.isSpectator) return  // yes they do call this, best not update any further stuff
        
        var nextTurnStats =
            if (civInfo.isBarbarian)
                Stats()
            else {
                civInfo.updateStatsForNextTurn()
                civInfo.stats.statsForNextTurn
            }

        val makesTourism = civInfo.isMajorCiv && !civInfo.isSpectator
        if (makesTourism) civInfo.culture.rememberLastTurn() // before the culture of this turn is added
        // CvPlayer::doTurnPostDiplomacy adds GetTotalJONSCulturePerTurnTimes100 (V:CvPlayer.cpp:10450-10459); without cultureHundredthsLikeVp a whole number
        civInfo.policies.endTurn(
            if (civInfo.gameInfo.ruleset.modOptions.constants.cultureHundredthsLikeVp) (nextTurnStats.culture * 100).toLong()
            else nextTurnStats.culture.toInt() * 100L
        )
        if (makesTourism) civInfo.culture.endTurn()
        // CvPlayer::doTurnPostDiplomacy calls updateYieldPerTurnHistory (V:CvPlayer.cpp:10530)
        if (makesTourism) civInfo.culture.recordYieldHistory()

        if (civInfo.isCityState) {
            civInfo.questManager.endTurn()

            // Set turns to elections to a random number so not every city-state has the same election date
            // May be called at game start or when migrating a game from an older version
            if (civInfo.gameInfo.isEspionageEnabled() && !civInfo.gameInfo.ruleset.modOptions.constants.espionageLikeVp
                && !civInfo.hasFlag(CivFlags.TurnsTillCityStateElection.name)) {
                val rng = civInfo.state.stateBasedRandom("TurnManager.endTurn(Espianage)")
                civInfo.addFlag(CivFlags.TurnsTillCityStateElection.name, rng.nextInt(civInfo.cityStateFunctions.getTurnsBetweenElections() + 1))
            }
        }

        if (! civInfo.isBarbarian) {
            // CvTreasury::DoGold: when the treasury after this turn's income would be at or below the threshold (-5 gold), CvPlayer::DoDeficit
            // disbands a unit, in half of the cases. With treasuryNeverNegative the treasury is then set to 0 as in the original, else the debt stays (it costs science)
            val goldAfterTimes100 = civInfo.gold * 100 + (nextTurnStats.gold * 100).toInt()
            if (goldAfterTimes100 <= civInfo.gameInfo.ruleset.ai.define(AiDefine.DEFICIT_UNIT_DISBANDING_THRESHOLD) * 100) {
                civInfo.ai.military.doDeficit(civInfo)
                // No need to recalculate unit upkeep, disband did that in UnitManager.removeUnit
                nextTurnStats = civInfo.stats.statsForNextTurn
            }
        }

        val goldChange = nextTurnStats.gold.toInt()
        // CvTreasury::DoGold: a treasury that the income would take below zero is set to 0 (the rest of the deficit is lost)
        if (civInfo.gameInfo.ruleset.modOptions.constants.treasuryNeverNegative && civInfo.gold + goldChange < 0)
            civInfo.addGold(-civInfo.gold)
        else civInfo.addGold(goldChange)

        if (civInfo.cities.isNotEmpty() && civInfo.gameInfo.ruleset.technologies.isNotEmpty())
            civInfo.tech.endTurn(nextTurnStats.science.toInt())

        civInfo.religionManager.endTurn(nextTurnStats.faith.toInt())
        civInfo.totalFaithForContests += nextTurnStats.faith.toInt()

        civInfo.espionageManager.endTurn()

        if (civInfo.isMajorCiv) // City-states don't get great people!
            civInfo.greatPeople.addGreatPersonPoints()

        // To handle tile's owner issue (#8246), we need to run cities being razed first.
        // a city can be removed while iterating (if it's being razed) so we need to iterate over a copy - sorting does one
        for (city in civInfo.cities.sortedByDescending { it.isBeingRazed }) {
            progressBar?.increment()
            CityTurnManager(city).endTurn()
        }

        civInfo.temporaryUniques.endTurn()

        civInfo.goldenAges.endTurn(
            // CvPlayer::GetHappinessForGAP: the positive deltas of the cities with the key, else the excess happiness
            (if (civInfo.gameInfo.ruleset.modOptions.constants.cityNeedsLikeVp) civInfo.stats.needs.goldenAgePointsFromHappiness()
            else civInfo.getHappiness())
                // CvPlayer::GetGoldenAgePointsFromEmpireTimes100 comes on top of GetHappinessForGAP (V:CvPlayer.cpp:24794-24795)
                + civInfo.goldenAges.pointsPerTurnFromUniques())
        civInfo.units.getCivUnits().forEach { UnitTurnManager(it).endTurn() }  // This is the most expensive part of endTurn
        civInfo.diplomacy.values.toList().forEach { it.nextTurn() } // we copy the diplomacy values so if it changes in-loop we won't crash
        civInfo.cache.updateHasActiveEnemyMovementPenalty()

        civInfo.resetMilitaryMightCache()

        civInfo.instantYieldNotices.flushDeferred(civInfo) // what the cities' growth gave this turn, told once
        updateWinningCiv() // Maybe we did something this turn to win
        
        civInfo.lastTurnProcessedWithVersion = UncivGame.VERSION
    }

    fun updateWinningCiv() {
        if (civInfo.gameInfo.victoryData != null) return // Game already won

        val victoryType = civInfo.victoryManager.getVictoryTypeAchieved()
        if (victoryType != null) {
            civInfo.gameInfo.victoryData =
                    VictoryData(civInfo, victoryType, civInfo.gameInfo.turns)

            // Notify other human players about this civInfo's victory
            for (otherCiv in civInfo.gameInfo.civilizations) {
                // Skip winner, displaying VictoryScreen is handled separately in WorldScreen.update
                // by checking `viewingCiv.isDefeated() || gameInfo.checkForVictory()`
                if (otherCiv.playerType != PlayerType.Human || otherCiv == civInfo) continue
                otherCiv.popupAlerts.add(PopupAlert(AlertType.GameHasBeenWon, ""))
            }
        }
    }

    fun automateTurn() {
        // Defeated civs do nothing
        if (civInfo.isDefeated())
            return
        timeThis("automateTurn") {
            // Do stuff
        NextTurnAutomation.automateCivMoves(civInfo)

        // Update barbarian camps
        if (civInfo.isBarbarian && !civInfo.gameInfo.gameParameters.noBarbarians)
            civInfo.gameInfo.barbarians.updateEncampments()
        }
    }

}

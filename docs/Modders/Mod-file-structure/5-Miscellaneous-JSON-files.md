# Miscellaneous JSON files

## Difficulties.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Difficulties.json)

This optional file defines the difficulty levels a player can choose when starting a new game.

Base ruleset Mods can omit the file or supply an empty list, even though at least one difficulty is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/Difficulties.json).

Each difficulty level has the following structure:

| Attribute                         | Type            | Default  | Notes                                                                                                                                                                                                                      |
|-----------------------------------|-----------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| name                              | String          | Required |                                                                                                                                                                                                                            |
| baseHappiness                     | Integer         | 0        |                                                                                                                                                                                                                            |
| extraHappinessPerLuxury           | Float           | 0        |                                                                                                                                                                                                                            |
| baseHappinessCapital              | Integer         | 0        | Happiness of the capital on top of `baseHappiness` (the original's `HappinessDefaultCapital`). Only with the ModConstants `cityNeedsLikeVp` |
| spySecurityModifier               | Integer         | 0        | Added to the security of the cities of a civilization at this level (`HandicapInfos.SpySecurityModifier`); the cities of an AI civilization also get that of the game's chosen difficulty. Only with `espionageLikeVp` |
| aiBaseHappiness                   | Integer         | 0        | Added to `baseHappiness` of an AI civilization, from the game's chosen difficulty (`AIHappinessDefault`). Only with `cityNeedsLikeVp` |
| aiBaseHappinessCapital            | Integer         | 0        | Added to `baseHappinessCapital` of the capital of an AI civilization, from the chosen difficulty (`AIHappinessDefaultCapital`). Only with `cityNeedsLikeVp` |
| populationNeedModifier            | Integer         | 0        | Percent points added to every need of every city (`PopulationUnhappinessMod`). Only with `cityNeedsLikeVp` |
| aiPopulationNeedModifier          | Integer         | 0        | Added to `populationNeedModifier` of an AI civilization, from the chosen difficulty (`AIPopulationUnhappinessMod`). Only with `cityNeedsLikeVp` |
| empireSizeNeedModifier            | Integer         | 0        | Percent change of the rise of the needs for the size of the empire (`EmpireSizeUnhappinessMod`). Only with `cityNeedsLikeVp` |
| aiEmpireSizeNeedModifier          | Integer         | 0        | Added to `empireSizeNeedModifier` of an AI civilization, from the chosen difficulty (`AIEmpireSizeUnhappinessMod`). Only with `cityNeedsLikeVp` |
| researchCostModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| unitCostModifier                  | Float           | 1        | Applies to the gold maintenance of the units of a human player (the original's UnitCostPercent), not to production |
| unitSupplyBase                    | Integer         | 5        |                                                                                                                                                                                                                            |
| unitSupplyPerCity                 | Integer         | 2        |                                                                                                                                                                                                                            |
| buildingCostModifier              | Float           | 1        | Applies to the gold maintenance of the buildings of a human player (the original's BuildingCostPercent), not to production |
| policyCostModifier                | Float           | 1        |                                                                                                                                                                                                                            |
| unhappinessModifier               | Float           | 1        |                                                                                                                                                                                                                            |
| barbarianBonus                    | Float           | 0        |                                                                                                                                                                                                                            |
| barbarianSpawnDelay               | Integer         | 0        |                                                                                                                                                                                                                            |
| playerBonusStartingUnits          | List of Strings | empty    | Can also be 'Era Starting Unit', maps to `startingMilitaryUnit` of the Eras file. All other units must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson). Applies only to human player civs                     |
| isSelectable                      | Boolean         | true     | `false` hides the level from the difficulty choice of a new game: for a level that only serves as the handicap of the AI civilizations, see aiDifficultyLevel |
| aiDifficultyLevel                 | String          | empty    | Applies some modifiers to the AI from the chosen difficulty: the AI civilizations get the player settings (happiness, research and policy cost, unhappiness, unit supply) of this level. Modifiers such as unhappinessModifier and aiUnhappinessModifier stack multiplicatively. If empty: defaults to "Chieftain", or the first available difficulty. |
| cityStateDifficultyLevel          | String          | empty    | The level whose player settings city-state civilizations have. If empty: aiDifficultyLevel                                                                                                                                 |
| barbarianDifficultyLevel          | String          | empty    | The level whose player settings the barbarians have. If empty: aiDifficultyLevel                                                                                                                                           |
| aiCityGrowthModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitCostModifier                | Float           | 1        |                                                                                                                                                                                                                            |
| aiBuildingCostModifier            | Float           | 1        |                                                                                                                                                                                                                            |
| aiWonderCostModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| aiBuildingMaintenanceModifier     | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitMaintenanceModifier         | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitSupplyBase                  | Integer         | 7        | Unit supply of an AI major civilization that does not depend on its cities or population (the Community Patch's AIUnitSupplyBase). Read from the difficulty the game is played at, not from the level of the AI itself |
| aiUnitSupplyPerCity               | Integer         | 3        | Unit supply per city of an AI major civilization (AIUnitSupplyPerCity) |
| aiUnitSupplyPopulationPercent     | Integer         | 50       | Percentage of the citizens of its cities that an AI major civilization gets as unit supply (AIUnitSupplyPopulationPercent). 50: one per two citizens |
| aiUnitSupplyBonusPercent          | Integer         | 0        | Percentage added to the whole unit supply of an AI major civilization (AIUnitSupplyBonusPercent). 20: 20% more |
| cityStateUnitSupplyBase           | Integer         | 7        | As aiUnitSupplyBase, for city-state civilizations (CityStateUnitSupplyBase) |
| cityStateUnitSupplyPerCity        | Integer         | 3        | As aiUnitSupplyPerCity, for city-state civilizations (CityStateUnitSupplyPerCity) |
| cityStateUnitSupplyPopulationPercent | Integer      | 50       | As aiUnitSupplyPopulationPercent, for city-state civilizations (CityStateUnitSupplyPopulationPercent) |
| cityStateUnitSupplyBonusPercent   | Integer         | 0        | As aiUnitSupplyBonusPercent, for city-state civilizations (CityStateUnitSupplyBonusPercent) |
| aiFreeTechs                       | List of Strings | empty    | Must be in [Techs.json](2-Civilization-related-JSON-files.md#techsjson)                                                                                                                                                    |
| aiMajorCivBonusStartingUnits      | List of Strings | empty    | Same rules as playerBonusStartingUnits, See above. Applies only to AI major civs                                                                                                                                           |
| aiCityStateBonusStartingUnits     | List of Strings | empty    | Same rules as playerBonusStartingUnits, See above. Applies only to city-state civs                                                                                                                                         |
| aiUnhappinessModifier             | Float           | 1        |                                                                                                                                                                                                                            |
| aiPerEraModifier                  | Float           | 0        | Change of an AI's production costs, city growth thresholds and unit upgrade costs per era it is in. -0.05: 5% cheaper per era (the original's AIPerEraModifier) |
| aiUnitUpgradeCostModifier         | Float           | 1        | Multiplier for the gold cost of unit upgrades of an AI |
| aiFreeUnitExperience              | Integer         | 0        | Experience that new military units of an AI start with |
| aiFreeUnitExperiencePercent       | Float           | 0        | Additional experience gain in combat for the units of an AI. 1: 100% more |
| aiWorkRateModifier                | Float           | 0        | Additional speed of the workers of an AI. 1: twice as fast |
| routeCostModifier                 | Float           | 1        | Multiplier for the gold maintenance of roads and railroads of the players with this level (the original's RouteCostPercent) |
| turnBarbariansCanEnterPlayerTiles | Integer         | 0        |                                                                                                                                                                                                                            |
| clearBarbarianCampReward          | Integer         | 25       |                                                                                                                                                                                                                            |
| uniques                           | List of Strings | empty    | Will be treated as part of GlobalUniques when this difficulty is chosen                                                                                                                                                    |

## Eras.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Eras.json)

This file should contain all the era's you want to use in your mod.

Each era can have the following attributes:

| Attribute                 | Type                                   | Default         | Notes                                                                                                                                                                                                                                           |
|---------------------------|----------------------------------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| name                      | String                                 | Required        |                                                                                                                                                                                                                                                 |
| researchAgreementCost     | Integer (≥0)                           | 300             | Cost of research agreements when the most technologically advanced civ is in this era                                                                                                                                                           |
| iconRGB                   | [List of 3× Integer](#rgb-colors-list) | white           | RGB color that icons for technologies of this era should have in the Tech screen                                                                                                                                                                |
| startingSettlerCount      | Integer (≥0)                           | 1               | Amount of settler units that should be spawned when starting a game in this era (setting this to zero is discouraged [^1])                                                                                                                      |
| startingSettlerUnit       | String                                 | "Settler"       | Name of the unit that should be used for the previous field. Must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson), or a unit with the "Founds a new city" unique must exist                                                             |
| startingWorkerCount       | Integer (≥0)                           | 0               | Amount of worker units that should be spawned when starting a game in this era                                                                                                                                                                  |
| startingWorkerUnit        | String                                 | "Worker"        | Name of the unit that should be used for the previous field. If startingWorkerCount>0, then it must exist in [Units.json](4-Unit-related-JSON-files.md#unitsjson), or a unit with the "Can build [filter] improvements on tiles" unique must exist |
| startingMilitaryUnitCount | Integer (≥0)                           | 1               | Amount of military units that should be spawned when starting a game in this era                                                                                                                                                                |
| startingMilitaryUnit      | String                                 | "Warrior"       | Name of the unit that should be used for the previous field. Must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson)                                                                                                                       |
| startingGold              | Integer (≥0)                           | 0               | Amount of gold each civ should receive when starting a game in this era                                                                                                                                                                         |
| startingCulture           | Integer (≥0)                           | 0               | Amount of culture each civ should receive when starting a game in this era                                                                                                                                                                      |
| settlerPopulation         | Integer (>0)                           | 1               | Amount of population each city should have when settled when starting a game in this era                                                                                                                                                        |
| settlerBuildings          | List of Strings                        | empty           | Buildings that should automatically be built whenever a city is settled when starting a game in this era                                                                                                                                        |
| startingObsoleteWonders   | List of Strings                        | empty           | Wonders (and technically buildings) that should be impossible to built when starting a game in this era. Used in the base game to remove all wonders older than 2 era's                                                                         |
| baseUnitBuyCost           | Integer                                | 200             | Default value used for the unique `Can be purchased with [stat] [cityFilter]`                                                                                                                                                                   |
| embarkDefense             | Integer                                | 3               | Default defense for embarked unit in this era                                                                                                                                                                                                   |
| startPercent              | Integer                                | 0               | When starting, percentage (\[0\]%-\[100\]%) of turns skipped in total turns specified in [Speed.json](#speedsjson)                                                                                                                              |
| researchPercent           | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Tech cost |
| trainPercent              | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of units |
| constructPercent          | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of buildings and wonders |
| createPercent             | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of spaceship parts (the original game's projects) |
| greatPeoplePercent        | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Points needed for a Great Person, Great General or Great Admiral |
| growthPercent             | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Food needed for a city to grow |
| buildPercent              | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Time workers need to build improvements |
| buildingMaintenancePercent | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Gold maintenance of buildings |
| laterEraBuildingConstructMod | Integer                             | 0               | Percent the production cost of a building changes by for each era a civilization has gone through since the era of the building's technology, the values of the eras from that one up to its own era added (a building without a technology uses every earlier era). Vox Populi: -2 |
| tradeRouteFoodBonus       | Integer                                | 0               | Hundredths of food a domestic trade route adds in this era with the ModConstants `tradeRouteModelLikeVp` (the original's `TradeRouteFoodBonusTimes100`) |
| tradeRouteProductionBonus | Integer                                | 0               | The same for production (`TradeRouteProductionBonusTimes100`)  |
| citySound                 | String                                 | "cityClassical" | Sound used when city is founded in this era                                                                                                                                                                                                     |
| uniques                           | List of Strings | empty    | Will be treated as part of GlobalUniques when starting in this era                                                                                                                                                    |

[^1]: Successfully setting startingSettlerCount to zero in a mod (idea: conquer or die) is not easy. Some player-controlled settings require at least one Settler, through any source (see difficulties for other possible settler sources), or you won't be able to start a game: Once City Challenge requires one for all players, and allowing any city-states requires one for those. Would also affect defeat rules.

## Speeds.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Speeds.json)

This optional file should contain all the speeds you want to use in your mod.

Base ruleset Mods can omit the file or supply an empty list, even though at least one game speed is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/Speeds.json).

Each speed can have the following attributes:

| Attribute                       | Type            | Default          | Notes                                                                                                       |
|---------------------------------|-----------------|------------------|-------------------------------------------------------------------------------------------------------------|
| name                            | String          | Required         | Name of the speed                                                                                           |
| modifier                        | Float (≥0)      | 1.0              | Overall game speed modifier                                                                                 |
| productionCostModifier          | Float (≥0)      | `modifier` value | Scales production cost of units and buildings                                                               |
| goldCostModifier                | Float (≥0)      | `modifier` value | Scales gold costs                                                                                           |
| scienceCostModifier             | Float (≥0)      | `modifier` value | Scales science costs                                                                                        |
| cultureCostModifier             | Float (≥0)      | `modifier` value | Scales culture costs                                                                                        |
| faithCostModifier               | Float (≥0)      | `modifier` value | Scales faith costs                                                                                          |
| instantYieldModifier            | Float (≥0)      | `modifier` value | Scales `Gain [amount] [stat] <(modified by game speed)>` (the original's `InstantYieldPercent`: 0.75, 1, 1.5, 2). Only with the ModConstants `instantYieldsUseInstantYieldPercent` |
| hurryModifier                   | Float (≥0)      | 1.0              | Scales the gold price of buildings and units (the original's `HurryPercent`, 100% in every speed of Brave New World and Vox Populi, so it does not default to `modifier`) |
| tradeRouteSpeedModifier         | Float (≥0)      | 1.0              | Scales the number of turns a trade route lasts (the original's `TradeRouteSpeedMod`: 3, 1.5, 1 and 0.67 for Marathon, Epic, Standard and Quick in Vox Populi; only read with the ModConstants `tradeRouteModelLikeVp`) |
| improvementBuildLengthModifier  | Float (≥0)      | `modifier` value | Scales the time it takes for a worker to build tile improvements                                            |
| barbarianModifier               | Float (≥0)      | `modifier` value | Scales the time between barbarian spawns                                                                    |
| goldGiftModifier                | Float (≥0)      | `modifier` value | Scales the influence gained from gifting gold to city-states                                                |
| cityStateTributeScalingInterval | Float (≥0)      | 6.5              | The number of turns it takes for the amount of gold a player demands from city-states to increase by 5 gold |
| goldenAgeLengthModifier         | Float (≥0)      | `modifier` value | Scales the length of golden ages                                                                            |
| religiousPressureAdjacentCity   | Integer (≥0)    | 6                | Defines how much religious pressure a city exerts on nearby cities                                          |
| startingHappiness               | Integer         | 0                | Happiness every civilization starts with on this speed (the original's `StartingHappiness`). Only with the ModConstants `cityNeedsLikeVp` |
| peaceDealDuration               | Integer (≥0)    | 10               | The number of turns a peace deal lasts                                                                      |
| dealDuration                    | Integer (≥0)    | 30               | The number of turns a non-peace deal (research agreement, open borders, etc.) lasts                         |
| startYear                       | Float           | -4000            | The start year of the game (negative is BC/BCE)                                                             |
| turns                           | List            | Required         | List of time interval per turn, [see below](#time-interval-per-turn)                                        |
| uniques                         | List of Strings | empty            | Will be treated as part of GlobalUniques when this speed is chosen                                          |


### Time interval per turn

The "turns" attribute defines the number of years passing between turns. The attribute consists of a list of objects, each having 2 required attributes: "yearsPerTurn" (Float) and "untilTurn" (Integer)

| Attribute    | Type    | Default  | Notes                                                      |
|--------------|---------|----------|------------------------------------------------------------|
| yearsPerTurn | Integer | Required | Number of years passing between turns                      |
| untilTurn    | Integer | Required | End of this interval (if it is the last object, see below) |

For each row, "yearsPerTurn" is applied up to the "untilTurn"-1 to "untilTurn" step.
The last "untilTurn" in the list is ignored for year calculation, that is, if a game passes that turn number, years continue to increment by the "yearsPerTurn" of the last entry.
However, this is used when starting a game in a later Era: Era.startPercent is relative to the last "untilTurn".

The code below is an example of a valid "turns" definition and it specifies that the first 50 turns of a game last for 60 years each, then the next 30 turns (and any played after the 80th) last for 40 years each.

```json
"turns": [
    {"yearsPerTurn": 60, "untilTurn":  50},
    {"yearsPerTurn": 40, "untilTurn":  80}
]
```

## Events.json

Events allow users to choose between options of triggers to activate.

| Attribute       | Type                                | Default  | Notes                                                                                                                    |
|-----------------|-------------------------------------|----------|--------------------------------------------------------------------------------------------------------------------------|
| name            | String                              | Required | Used for triggering via "Triggers a [event] event" unique                                                                |
| text            | String                              | None     | Flavor text displayed to user                                                                                            |
| presentation    | One of: "None", "Alert", "Floating" | Alert    | "Alert" indicates a regular popup, "None" means the choice is made randomly, "Floating" is for tutorial-style indicators |
| civilopediaText | List                                | Optional | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)                                            |
| choices         | List of EventChoices                |          | User can choose to trigger one of the viable choices                                                                     |


You can use text and/or civilopediaText, if both are present both are shown (but why would you?)

Add the "Only the first available choice is offered" unique to an Event to restrict it to just the first viable choice (in list order), like a `when`/`case` statement.

Event choices are comprised of:

| Attribute       | Type                                           | Default    | Notes                                                                                                               |
|-----------------|------------------------------------------------|------------|---------------------------------------------------------------------------------------------------------------------|
| text            | String                                         | Required   | Displayed to user as button. Should be an action name - "Do X"                                                      |
| uniques         | List of uniques to trigger or limit conditions | Required   | The triggers that this choice activates upon being chosen, and "Unavailable" / "Only available" uniques             |
| keyShortcut     | key to select (name)                           | none       | Key names see [Gdx.Input.Keys](https://github.com/libgdx/libgdx/blob/master/gdx/src/com/badlogic/gdx/Input.java#L69) |
| civilopediaText | List                                           | Optional   | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)                                          |

Here, civilopediaText is shown outside the active Button, before the triggeredUniques.

## ModOptions.json

<!-- [Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/ModOptions.json) -->

This file is a little different:

- Does not exist in Vanilla ruleset
- Is entirely optional but will be created after downloading a mod

Note that this file controls _declarative mod compatibility_ (Work in progress) - e.g. there's [uniques](../uniques.md#modoptions-uniques) to say your Mod should only or never be used as 'Permanent audiovisual mod'.
Incompatibility filtering works so far between extension and base mods, but feel free to document known extension-to-extension incompatibilities using the same Unique now. Stay tuned!

The file can have the following attributes, not including the values Unciv sets automatically:

| Attribute              | Type    | default | Notes                                                                                                                                                                               |
|------------------------|---------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| isBaseRuleset          | Boolean | false   | Replaces vanilla ruleset if true                                                                                                                                                    |
| uniques                | List    | empty   | Mod-wide specials, [see here](../uniques.md#modoptions-uniques)                                                                                                                     |
| beliefsToRemove        | List    | empty   | List of [Beliefs](2-Civilization-related-JSON-files.md#beliefsjson) or beliefFilter (matches name, type, "All" or a filtering Unique) to remove (isBaseRuleset=false only)          |
| buildingsToRemove      | List    | empty   | List of [Buildings or Wonders](2-Civilization-related-JSON-files.md#buildingsjson) or [buildingFilter](../Unique-parameters.md#buildingfilter) to remove (isBaseRuleset=false only) |
| cityStateTypesToRemove | List    | empty   | List of Citystate Types or "All" to remove (isBaseRuleset=false only).                                                                                                              |
| difficultiesToRemove   | List    | empty   | List of Difficulties or "All" to remove (isBaseRuleset=false only).                                                                                                                 |
| eventsToRemove         | List    | empty   | List of List of Events or "All" to remove (isBaseRuleset=false only).                                                                                                               |
| greatWorksToRemove     | List    | empty   | List of [Great Works](2-Civilization-related-JSON-files.md#greatworksjson) or "All" to remove (isBaseRuleset=false only).                                                           |
| improvementsToRemove   | List    | empty   | List of List of Tile Improvements or [improvementFilter](../Unique-parameters.md#improvementFilter) to remove (isBaseRuleset=false only).                                           |
| nationsToRemove        | List    | empty   | List of [Nations](2-Civilization-related-JSON-files.md#nationsjson) or [nationFilter](../Unique-parameters.md#nationfilter) to remove (isBaseRuleset=false only)                    |
| personalitiesToRemove  | List    | empty   | List of Leader Personalities or "All" to remove (isBaseRuleset=false only).                                                                                                         |
| policyBranchesToRemove | List    | empty   | List of [Policy Branches](2-Civilization-related-JSON-files.md#policiesjson) to remove (isBaseRuleset=false only)                                                                   |
| policiesToRemove       | List    | empty   | List of [Policies](2-Civilization-related-JSON-files.md#policiesjson) to remove (isBaseRuleset=false only)                                                                          |
| questsToRemove         | List    | empty   | List of Citystate Quest names or "All" to remove (isBaseRuleset=false only).                                                                                                        |
| resolutionsToRemove    | List    | empty   | List of [Resolutions](2-Civilization-related-JSON-files.md#resolutionsjson) or "All" to remove (isBaseRuleset=false only).                                                          |
| religionsToRemove      | List    | empty   | List of [Religions](2-Civilization-related-JSON-files.md#religionsjson) or "All" to remove (isBaseRuleset=false only)                                                               |
| resourcesToRemove      | List    | empty   | List of Tile Resources or [resourceFilter](../Unique-parameters.md#resourceFilter) to remove (isBaseRuleset=false only).                                                            |
| ruinRewardsToRemove    | List    | empty   | List of Ruin Rewards or "All" to remove (isBaseRuleset=false only).                                                                                                                 |
| specialistsToRemove    | List    | empty   | List of Specialists or "All" to remove (isBaseRuleset=false only).                                                                                                                  |
| speedsToRemove         | List    | empty   | List of Game Speeds or "All" to remove (isBaseRuleset=false only).                                                                                                                  |
| techsToRemove          | List    | empty   | List of [Technologies](2-Civilization-related-JSON-files.md#techsjson) or [technologyFilter](../Unique-parameters.md#technologyfilter) to remove (isBaseRuleset=false only)         |
| terrainsToRemove       | List    | empty   | List of Terrains or [terrainFilter](../Unique-parameters.md#terrainFilter) to remove (isBaseRuleset=false only).                                                                    |
| tutorialsToRemove      | List    | empty   | List of Tutorials or "All" to remove (isBaseRuleset=false only).                                                                                                                    |
| unitsToRemove          | List    | empty   | List of [Units](4-Unit-related-JSON-files.md#unitsjson) or [unitFilter](../Unique-parameters.md#baseunitfilter) to remove (isBaseRuleset=false only)                                |
| unitNameGroupsToRemove | List    | empty   | List of Unit-name Groups or "All" to remove (isBaseRuleset=false only).                                                                                                             |
| unitPromotionsToRemove | List    | empty   | List of Unit Promotions or "All" to remove (isBaseRuleset=false only).                                                                                                              |
| unitTypesToRemove      | List    | empty   | List of Unit Types or [unitTypeFilter](../Unique-parameters.md#unitTypeFilter) to remove (isBaseRuleset=false only). Note dependent units are not automatically removed as well.    |
| victoriesToRemove      | List    | empty   | List of Victories or "All" to remove (isBaseRuleset=false only).                                                                                                                    |
| constants              | Object  | empty   | See [ModConstants](#modconstants)                                                                                                                                                   |
| tileset                | String  | empty   | Only applicable for base rulesets                                                                                                                                                   |
| unitset                | String  | empty   | Only applicable for base rulesets                                                                                                                                                   |

Note that removal happens before adding new content from your mod, so an "All" filter won't kill your content too.

Policies with their branches and Technologies have more complex removal rules.

- Removing Policy Branches removes their member Policies as well.
- Overriding a Policy Branch will remove existing member Policies.
- Removing all techs from a TechColumn will also remove that column.
- Adding techs will merge columns by their `columnNumber`.
- Adding tech columns outside the existing ones is allowed: Replacing a column that was removed, or placing a column before (using negative `columnNumber`) or after the existing ones.
- After tech removal and merge is done, TechColumns are resorted and renumbered so `columnNumber` is monotonous, contiguous ands starts at 0.

Removing religions will clean those from the preferred religions of nations.

Otherwise, no interdependencies are handled automatically, and your mod may easily fail validation unless you're careful.

The values normally set automatically from github metadata are:

| Attribute     | Type    |        | Notes                                                                                 |
|---------------|---------|--------|---------------------------------------------------------------------------------------|
| modUrl        | String  |        | The github page the mod was downloaded from, or empty if a freely hosted zip was used |
| defaultBranch | String  | master | The repo's default branch                                                             |
| author        | String  |        | Repo owner                                                                            |
| lastUpdated   | String  |        | ISO date                                                                              |
| modSize       | Integer | 0      | Size in kB                                                                            |
| topics        | List    | empty  | A list of "unciv-mod-*" github topics                                                 |

To clarify: When your Mod is distributed via github, including these in the Mod repo has no effect.
However, when a Mod is distributed _without_ a github repository, these values can and _should_ be set by the author in the distributed `ModOptions.json`.

### ModConstants

Stored in ModOptions.constants, this is a collection of constants used internally in Unciv.
This is the only structure that is _merged_ field by field from mods, not overwritten, so you can change XP from Barbarians in one mod
and city distance in another. In case of conflicts, there is no guarantee which mod wins, only that _default_ values are ignored.

| Attribute                                | Type   | Default                       | Notes |
|------------------------------------------|--------|-------------------------------|-------|
| maxXPfromBarbarians                      | Int    | 30                            | [^A]  |
| cityStrengthBase                         | Float  | 8.0                           | [^B]  |
| cityStrengthPerPop                       | Float  | 0.4                           | [^B]  |
| cityStrengthFromTechsMultiplier          | Float  | 5.5                           | [^B]  |
| cityStrengthFromTechsExponent            | Float  | 2.8                           | [^B]  |
| cityStrengthFromTechsFullMultiplier      | Float  | 1.0                           | [^B]  |
| cityStrengthFromGarrison                 | Float  | 0.2                           | [^B]  |
| baseCityBombardRange                     | Int    | 2                             | [^X]  |
| cityWorkRange                            | Int    | 3                             | [^Y]  |
| cityExpandRange                          | Int    | 5                             | [^Z]  |
| cityAirUnitCapacity                      | Int    | 6                             | [^W]  |
| cultureCostLikeCivV                      | Boolean | false                        | [^V]  |
| treasuryNeverNegative                    | Boolean | false                        | [^Treasury] |
| plotAcquisitionLikeCivV                  | Boolean | false                        | [^PlotBuy] |
| sightBeyondRangeLikeCivV                 | Boolean | false                        | [^SightBeyond] |
| minimumSightRange                        | Int     | 1                            | [^MinSight] |
| unitGiftTravelTurns                      | Int     | 0                            | [^GiftTravel] |
| airInterceptionChanceScalesWithHealth    | Boolean | false                        | [^IntercHealth] |
| uprisingCounterLikeCivV                  | Boolean | false                        | [^Uprising] |
| localHappinessCappedByPopulation         | Boolean | false                        | [^LocalHappiness] |
| cityNeedsLikeVp                          | Boolean | false                        | [^CityNeeds] |
| needs                                    | Object  | [See below](#needsconstants) | [^CityNeeds] |
| instantYieldsUseInstantYieldPercent      | Boolean | false                        | [^InstantYields] |
| instantYieldsCutTotalLikeVp              | Boolean | false                        | [^InstantYieldsTotal] |
| repeatTechsGiveNoInstantYieldsLikeVp     | Boolean | false                        | [^RepeatTechs] |
| purchase                                 | Object  | [See below](#purchaseconstants) | [^PurchasePrice] |
| buildingInvestments                      | Boolean | false                        | [^Investments] |
| buildingInvestmentBaseline               | Int     | -50                          | [^Investments] |
| buildingInvestmentWonderDivisor          | Int     | 2                            | [^Investments] |
| goldBuildingCostPercent                  | Int     | 100                          | [^Investments] |
| worldWonderCostIncrease                  | List<Int> | empty                      | [^WonderCost] |
| purchaseCooldownEnabled                  | Boolean | false                        | [^Cooldown] |
| rangedSupportFireUnitsGetNoFortification | Boolean | false                        | [^SupportFire] |
| unitHurryLikeCivV                        | Boolean | false                        | [^UnitHurry] |
| minorDepositsOnFlatLandOnly              | Boolean | false                        | [^MinorDeposits] |
| minorDepositFrequency                    | Int     | 30                           | [^MinorDeposits] |
| cityStateSitesLikeCivV                   | Boolean | false                        | [^CityStateSites] |
| annexedCityCitizenUnhappinessFactor      | Float   | 2                            | [^Annexed] |
| annexedCityFlatUnhappiness               | Float   | 2                            | [^Annexed] |
| nukeCivilianDeathDamage                  | Int     | 60                           | [^NukeCivilians] |
| disbandRefundPercent                     | Int     | 0                            | [^Disband] |
| featureOnHillsExtraMovement              | Int     | 0                            | [^HillFeature] |
| buildingSaleDivisor                      | Int     | 10                           | [^SaleDivisor] |
| unitUpgradeCostModsAreSummed             | Boolean | false                        | [^UpgradeSum] |
| unitUpgradeCostDiscountMax               | Int     | -75                          | [^UpgradeSum] |
| unitUpgradeCostFromPurchasePrice         | Boolean | false                        | [^UpgradePrice] |
| minorCivTechPercent                      | Int     | 0                            | [^MinorTech] |
| maxedUnitGoldPercent                     | Int     | 0                            | [^MaxedGold] |
| maxedBuildingGoldPercent                 | Int     | 0                            | [^MaxedGold] |
| cityHealLikeCivV                         | Boolean | false                        | [^CityHeal] |
| resourceDemandLikeCivV                   | Boolean | false                        | [^ResDemand] |
| improvementsOnResourcesLikeCivV          | Boolean | false                        | [^ImpOnRes] |
| cityStateElectionsScaleWithGameSpeed     | Boolean | false                        | [^ElectionSpeed] |
| greatGeneralThresholdLikeCivV            | Boolean | false                        | [^GGThreshold] |
| greatGeneralPercentInBorders             | Integer | 0                            | [^GGBorders] |
| withdrawLikeCivV                         | Boolean | false                        | [^Withdraw] |
| withdrawModEnemyMoves                    | Integer | -20                          | [^Withdraw] |
| withdrawModBlockedTile                   | Integer | -20                          | [^Withdraw] |
| citiesCanBombardCities                   | Boolean | true                         | [^CityBombard] |
| cityStateInfluenceChangeForTilesTaken    | Float   | -15                          | [^TilesTaken] |
| cityStateInfluenceOncePerTilesTaken      | Boolean | false                        | [^TilesTaken] |
| startNormalizerLikeCivV                  | Boolean | false                        | [^G]  |
| coastExpansionDice                       | List<Int> | empty                      | [^T]  |
| seaDepositsOfLandResource                | String  | empty                        | [^SeaOil] |
| terrainGeneratorLikeCivV                 | Boolean | false                        | [^CivVTerrain] |
| noMountainsOnTheCoast                    | Boolean | false                        | [^NoCoastMountains] |
| luxuryPlacementLikeCivV                  | Boolean | false                        | [^CivVLux] |
| startLuxuriesLikeVoxPopuli               | Boolean | false                        | [^VpLux] |
| startAssignmentLikeCivV                 | Boolean | false                        | [^CivVAssign] |
| regionAssignmentLikeVoxPopuli           | Boolean | false                        | [^VpRegions] |
| cityStatePlacementLikeVoxPopuli         | Boolean | false                        | [^VpCityStates] |
| startFinderLikeVoxPopuli                | Boolean | false                        | [^VpStartFinder] |
| resourceExtrasLikeCivV                  | Boolean | false                        | [^CivVExtras] |
| strategicPlacementLikeVoxPopuli         | Boolean | false                        | [^VoxPopuliStrategics] |
| naturalWondersLikeCivV                  | Boolean | false                        | [^CivVWonders] |
| ancientRuinsLikeCivV                    | Boolean | false                        | [^CivVRuins] |
| tilesPerAncientRuinLikeCivV              | Int     | 40                           | Land tiles per ancient ruin when `ancientRuinsLikeCivV` is set |
| unitSupplyPerPopulation                  | Float  | 0.5                           | [^C]  |
| unitMaintenanceFreeUnits                 | Int    | 3                             | [^U]  |
| unitMaintenanceGameMultiplier            | Float  | 1.0                           | [^U]  |
| unitMaintenanceExponentDivisor           | Float  | 3.0                           | [^U]  |
| sciencePerPopulation                     | Float  | 1.0                           | Science each citizen of a city gives (`SCIENCE_PER_POPULATION`: 1 in Brave New World, 0 in Vox Populi). The Brave New World ruleset keeps 1 until the science sources that replace it in Vox Populi (policies, traits, wonders, specialists in buildings) are flipped: with 0 now its research halves |
| cityGrowth                               | Object | [See below](#citygrowthconstants) | Food a city needs to grow (`CITY_GROWTH_MULTIPLIER` and the others) |
| borderCulture                            | Object | [See below](#borderculturecostconstants) | Culture a city needs for its next tile with `cultureCostLikeCivV` (`CULTURE_COST_FIRST_PLOT` and the others) |
| greatPersonThresholdBase                 | Int    | 100                           | Points a city needs for the first Great Person of a group (`GREAT_PERSON_THRESHOLD_BASE`: Brave New World 100, Vox Populi 150) |
| greatPersonThresholdIncrease             | Int    | 100                           | Points added for each Great Person of the group gained so far (`GREAT_PERSON_THRESHOLD_INCREASE`: Brave New World 100, Vox Populi 250). The groups are those of the unique `Is part of Great Person group [comment]` |
| minimalCityDistance                      | Int    | 3                             | [^D]  |
| minimalCityDistanceOnDifferentContinents | Int    | 2                             | [^D]  |
| unitUpgradeCost                          | Object | [See below](#unitupgradecost) | [^J]  |
| naturalWonderCountMultiplier             | Float  | 0.124                         | [^E]  |
| naturalWonderCountAddedConstant          | Float  | 0.1                           | [^E]  |
| ancientRuinCountMultiplier               | Float  | 0.025                         | [^F]  |
| maxLakeSize                              | Int    | 10                            | [^H]  |
| riverCountMultiplier                     | Float  | 0.01                          | [^I]  |
| minRiverLength                           | Int    | 5                             | [^I]  |
| maxRiverLength                           | Int    | 666                           | [^I]  |
| religionLimitBase                        | Int    | 1                             | [^K]  |
| religionLimitMultiplier                  | Float  | 0.5                           | [^K]  |
| pantheonBase                             | Int    | 10                            | [^L]  |
| pantheonGrowth                           | Int    | 5                             | [^L]  |
| workboatAutomationSearchMaxTiles         | Int    | 37                            | [^M]  |
| cityStateInfluenceLikeVp                 | Boolean | false                        | How the influence with a City-State changes each turn as in Vox Populi instead of Brave New World's: a protector's resting point is 5 (not 10), the decay grows with the points over the resting point, is three times as fast towards a protector whose capital is damaged, the trade route bonus is multiplied by the number of routes to City-States (at most 5), the change is divided by the game speed's production cost percentage, and an ally stays until another civilization has more influence |
| cityStateInfluence                       | Object  | [See below](#citystateinfluenceconstants) | The defines of that model, only read with `cityStateInfluenceLikeVp` |
| cityStateProtectionLikeVp                | Boolean | false                        | The pledge to protect a City-State as in Vox Populi instead of Brave New World's: only a civilization whose military might over its distance is at least the median of all, and that is the ally, has a trade route with the City-State or could make one, can pledge; it cannot be withdrawn for 50 turns; one that no longer qualifies ends after a warning of 12 turns, or at once below 0 influence, and costs influence for the era |
| cityStateProtection                    | Object  | [See below](#citystateprotectionconstants) | The defines of that model, only read with `cityStateProtectionLikeVp` |
| cityStateQuestsLikeVp                    | Boolean | false                        | The rewards of City-State quests as in Vox Populi instead of influence alone: the quests hold rewards of 15 kinds which grow with the era, get a random share, the speed's percentage and the multipliers of the type and personality of the City-State and the modifier of the civilization, are frozen when the quest is assigned and paid when it is complete; a heavy tribute (with `cityStateBullyLikeVp`) gives gold and the rewards of the personal quests |
| cityStateQuests                          | Object  | [See below](#citystatequestconstants) | The defines of that model, only read with `cityStateQuestsLikeVp` |
| cityStateGoldGifts                       | Boolean | true                         | Whether gold can be given to a City-State for influence, by the player and by the AI. Vox Populi has none (`CSD_GOLD_GIFT_DISABLED`) |
| cityStateBullyLikeVp                     | Boolean | false                        | Demanding tribute as in Vox Populi instead of Brave New World's: the score is the share of the military might and the local power with no base reluctance and no penalty for low influence, gold needs a score of 20 and the heavy tribute (the worker) 100, a demand costs 30 influence and what the civilization has over its resting point, and the `upon demanding tribute from a City-State` yields are paid |
| cityStateBully                           | Object  | [See below](#citystatebullyconstants) | The defines of that model, only read with `cityStateBullyLikeVp` |
| spyPointsLikeVp                          | Boolean | false                        | Vox Populi's spy points instead of whole spies: `Gain [n] Spy Points` counts points and makes a spy each time they reach the threshold (100 in Vox Populi), `Gain an extra spy` is one threshold of points, a negative amount is a debt. Without it `Gain [n] Spy Points` does nothing |
| espionageLikeVp                          | Boolean | false                        | Vox Populi's espionage: a spy in a foreign major's city gathers Network Points each turn (less the city's security), the owner gets the passive bonuses of [SpyBonuses.json](2-Civilization-related-JSON-files.md#spybonusesjson), a diplomat gathers them in the capital, and a spy needs turns to travel that grow with the distance of the civilizations. The old establishing of a network, surveillance and the theft of technology by progress are never entered |
| espionage                                | Object  | [See below](#espionageconstants) | The threshold of spy points and the time a killed spy stays dead |
| maxSpyRank                               | Int    | 3                             | [^N]  |
| spyRankSkillPercentBonus                 | Float  | 30                            | [^O]  |
| minimumWarDuration                       | Int    | 10                            | [^P]  |
| baseTurnsUntilRevolt                     | Int    | 4                             | [^Q]  |
| cityStateElectionTurns                   | Int    | 15                            | [^R]  |
| maxImprovementTechErasForward            | Int    | None                          | [^S]  |
| scoreFromCities                          | Int    | 10                            | Score for each city, scaled by the map size. Brave New World uses 8 |
| scoreFromGreatWorks                      | Int    | 0                             | Score for each Great Work. Brave New World uses 4 |
| scoreFromPolicies                        | Int    | 0                             | Score for each adopted policy (branch openers, policies, finishers, ideology tenets; not the choice of an ideology). Brave New World uses 4 |
| scoreFromReligionBeliefs                 | Int    | 0                             | Score for each belief of the civilization's founded religion (not a pantheon alone). Brave New World uses 20 |
| scoreFromReligionCities                  | Int    | 0                             | Score for each city of any civilization that follows the civilization's founded religion. Brave New World uses 1 |
| scoreBaseMapTiles                        | Int    | 1276                          | Map size, in tiles, at which the score from cities, population and land is not scaled; smaller maps score more of it (a third of the difference), larger maps less. Civ V uses 4160 |
| greatWorkBaseCulture                     | Int    | 2                             | Culture each Great Work gives its city, before bonuses (see the unique `[stats] from each Great Work`) |
| greatWorkBaseTourism                     | Int    | 2                             | Tourism each Great Work gives its city, before bonuses |
| tourismModifierSharedReligion            | Int    | 25                            | Percent more tourism towards civilizations that follow the same religion. See the unique `[relativeAmount]% Tourism towards civilizations with [tourismRelation]` |
| tourismModifierOpenBorders               | Int    | 25                            | Percent more tourism towards civilizations that have opened their borders to us |
| tourismModifierTradeRoute                | Int    | 25                            | Percent more tourism towards civilizations that we have a trade route with |
| tourismModifierDiplomat                  | Int    | 25                            | Percent more tourism towards a civilization that has our diplomat in its capital, if its ideology differs from ours (any ideologies with `tourismModifierLikeVp`). Vox Populi uses 20 |
| tourismModifierSharedReligionMax         | Int    | 0                             | With `tourismModifierLikeVp`, the most that shared religion gives before the percent of the uniques for it; 0 for no cap. Vox Populi uses 50 |
| tourismModifierLikeVp                    | Boolean | false                        | Vox Populi's percent more tourism towards a civilization instead of Brave New World's: `[relativeAmount]% Tourism towards civilizations with [Common Enemies]` counts once however many enemies there are in common, the diplomat counts whatever the ideologies, and the sum is never below -100. Open borders count when we let the other in, not when it lets us in. Shared religion is `tourismModifierSharedReligion` for each percent of the people of the other that follow our state religion (not a pantheon), at most `tourismModifierSharedReligionMax`, and `[relativeAmount]% Tourism towards civilizations with [Shared Religion]` multiplies it. Vox Populi uses 1 for `tourismModifierSharedReligion` |
| instantTourismLikeVp                     | Boolean | false                        | Vox Populi's tourism that is not made by the cities: `[positiveAmount] Tourism towards all civilizations we have met` and a concert tour are multiplied by the speed's `cultureCostModifier` and the tourism modifier towards each civilization they reach, cut to a whole number for each |
| influenceTrendLikeVp                     | Boolean | false                        | Vox Populi's influence trend and turns to Influential: this turn's influence plus the tourism per turn and the average change of influence over the last 30 turns, against the other's lifetime culture plus its culture per turn and its average instant culture; last turn's side adds what that turn gave. A change of a tenth or less is static |
| tourismPrecisionLikeVp                   | Boolean | false                        | Vox Populi's tourism in hundredths: the tourism of the cities keeps the parts of their percentages up to the modifier towards each civilization, and the influence keeps what is below a whole one for the next turn; the level, the Vox Populi trend and turns to Influential read it |
| cultureHundredthsLikeVp                  | Boolean | false                        | Vox Populi's culture of the turn in hundredths: the stored culture and the culture of the whole game keep what is below a whole one for the next turn; the influence levels and turns to Influential read the latter |
| accomplishmentsLikeVp                    | Boolean | false                        | Vox Populi's accomplishments: a finished policy branch of the first, third or fifth era, the first ideology, each chosen belief by its type and each civilization eliminated are counted, for the countable `Completed [accomplishment] accomplishments` |
| longCountYieldsLikeVp                    | Boolean | false                        | Vox Populi's Long Count yields: a new b'ak'tun fires `upon the end of a b'ak'tun` in each city, and each Great Person chosen for the Long Count is counted, for the countable `Long Count choices of [baseUnitFilter]` |
| historicEventsLikeVp                     | Boolean | false                        | Vox Populi's historic events: a Great Person born, a new era and a world wonder built give tourism from the last turns of culture and tourism that `[positiveAmount] turns of yields as tourism for historic events` say, towards every civilization met, and are counted for the countable `Historic events` |
| warVictoryLikeVp                         | Boolean | false                        | Vox Populi's war victories: with a war score of at least 25, the winner of a peace deal the other side surrenders, of a peace brokered with a third civilization or of an elimination completes `War won`, fires `upon winning a war` and has the historic event of a won war |
| historicEventDispatchLikeVp              | Boolean | false                        | Vox Populi's other historic events, with `historicEventsLikeVp`: a golden age, a trade route that ends (to a major civilization, a City-State, or a domestic one with `Domestic trade routes give tourism for completed trade routes`), a city adopting our state religion for the first time and the finds of an archaeological dig give the tourism of their own `turns of yields as tourism` uniques and count |
| archaeologyChoicesLikeVp                 | Boolean | false                        | Vox Populi's archaeology choices: a Landmark in a City-State's land gives `landmarkRestingInfluencePerEra` resting influence per era while the City-State keeps it, the influence rises to the resting point if below and then by `landmarkInfluenceWithCityStates` times the era; a culture boost in our lands also goes to the borders of the city; the record of the site goes but under a Landmark; `antiquity sites with a sarcophagus appear near each of the first [positiveAmount] cities founded` works |
| greatWorkWltkdLikeVp                     | Boolean | false                        | Vox Populi's celebrations of new Great Works: with `Each new Great Work gives all cities [positiveAmount] turns of We Love The King Day`, each work created by a Great Person, a building or a dig adds those turns, times the game speed's production cost, to the We Love The King Day of every city |
| greatWorkThemingLikeVp                   | Boolean | false                        | Vox Populi's theming: the AI puts no works into a city that might fall, themes and fills undamaged cities, then undamaged puppets, then endangered cities (the capital never is; another city is when it has lost more than half its health or is razed below 3 citizens), and tries the theming bonuses of a building from the highest; the works other civilizations at peace offer are swapped in while theming (for the first of our works not in the set, and only if we hold as many works as slots), and the leftovers fill with our works only; the AI also arranges its works right after each new one, City-States arrange theirs swapping with no one, and humans get Optimize and Swap & Optimize buttons in the culture overview instead of swap requests; the percentages of `[relativeAmount]% Theming bonuses [cityFilter]` are added up and truncated once |
| goldenAgeConversionLikeVp                | Boolean | false                        | Vox Populi's golden age conversion: each golden age started or extended, while there is a capital, carries the golden age points the next golden age needs (before the count of golden ages goes up) as the triggering amount of `upon entering a Golden Age`, for `Gain [relativeAmount]% of the triggering amount as [stat]` and `Gain [relativeAmount]% of the triggering amount as Tourism towards all civilizations we have met` |
| concertTourLimitsLikeVp                  | Boolean | false                        | Vox Populi's limits of the concert tour: none in the lands of a civilization we are at war with or already Influential over |
| influenceTradeGrowthLikeVp               | Boolean | false                        | Vox Populi's growth from influence: a city grows 5% faster for each influence level (Exotic 5% to Dominant 25%) we have over the owner of the major civilization's city each of its international trade routes goes to |
| publicOpinionLikeVp                      | Boolean | false                        | Vox Populi's public opinion: an influence counts from Popular on, raised by the trends of both sides (rising 0, static 1, falling 2); the ideology gets pressure of its own when few follow it ((civilizations with any ideology - those with ours + 1) / ideologies) and for 30 turns (by the game speed) after it was chosen or changed to; of the others the first in the ruleset that presses most is preferred; the unhappiness is 1 + 0.2 per point of dissatisfaction per city (up to 10), or 1 per 10 - dissatisfaction citizens, the greater. For another ideology only Popular or more counts, and the time since adoption does not. Vox Populi's limits of the types are 4 and 7 (`publicOpinionCivilResistance`, `publicOpinionRevolutionaryWave`) |
| religionCultureAiLikeVp                  | Boolean | false                        | Vox Populi's religion and culture AI. Missionaries score a city by 50 - the distances to the holy city and to the unit, plus how much their visit would move it, more for a city without a religion, our own or one whose owner has none; never a guarded one or one at war. Inquisitors remove heresy where another religion leads (or gains on ours without a majority) with more than 6 heretics (counted twice under a heretic majority), else guard the holy city or a city a missionary could sway. Each city is the target of one unit a turn, and a unit avoids cities in danger. The religion to spread is the state religion; without one, once no more religions can be founded, the foreign religion whose beliefs we like best (weighed by our opinion of its founder) among those our cities follow, spread only in our own cities. Faith goes, each bought or saved for in turn: to a Great Prophet that enhances our religion once spreading it has stalled (or with too many missionaries); from the Industrial era to the desired Great Person (scored 20000 minus its faith price, plus 100 or 200 by the victory pursued; a prophet only while the capital or holy city follows our religion, doubled while it is not enhanced) when it is less than max(13, 23 - religion flavor) turns away; to missionaries or inquisitors while one of our cities follows another religion; then, with a religion flavor of 7 or more, to missionaries for foreign cities nearby. Up to RELIGION_MAX_MISSIONARIES missionaries, plus one for each yield our beliefs give for spreading, or one for a religion we do not own. Faith none of these takes buys an inquisitor in a city of ours that a foreign Great Prophet of another religion can reach this turn; else, unless we must save to found a religion, each city (most faith first, holy cities counted twice, puppets half) buys the faith building of its religion, or else another building with Faith that adds no religious pressure, or else, once all our cities but puppets follow that religion, with net gold above 0 and no unit out of supply, the unit it would hurry with Faith (also buyable with gold now, not religious, no Great Person or aircraft) if an operation or army wants it or we have at most 5 of its kind. A Great Prophet that has spread keeps spreading; with our religion enhanced it builds a Holy Site while we have no more of them than our religion flavor minus our prophets, or when no city is worth converting, else spreads; with it not enhanced it enhances while our holy city follows it, else spreads; without one it founds one, or builds a Holy Site once none can be founded. It converts the better (by score over turns + 3) of the two best cities with a path safe for it: our holy city first, then our cities by their heretics and atheists over the distance to the holy city, then cities of others within 23 steps by their heretics, more where our beliefs pay for foreign followers. |
| concertTourMinimumTourism                | Int    | 100                           | The least a concert tour is worth, before the game speed |
| influencePercentExotic                   | Int    | 10                            | Tourism towards a civilization, added up over the game, makes us Exotic to it at this percent of the culture it has made in the whole game |
| influencePercentFamiliar                 | Int    | 30                            | As above, for Familiar |
| influencePercentPopular                  | Int    | 60                            | As above, for Popular |
| influencePercentInfluential              | Int    | 100                           | As above, for Influential |
| influencePercentDominant                 | Int    | 200                           | As above, for Dominant |
| archaeologySitesPerCivMin                | Int    | 5                             | Archaeological sites that appear for each major civilization, at least. See the unique `Archaeological sites appear around the world` |
| archaeologySitesPerCivMax                | Int    | 8                             | ... and at most |
| archaeologyPercentHidden                 | Int    | 30                            | Percent of the sites that are hidden until a policy is adopted (the resource `archaeologyHiddenSiteResource`) |
| archaeologyPercentHiddenWriting          | Int    | 30                            | Percent of the hidden sites that hold a writing |
| archaeologySiteResource                  | String | Antiquity site                | The resource of a site |
| archaeologyHiddenSiteResource            | String | Hidden Antiquity site         | The resource of a hidden site |
| archaeologicalDigImprovement            | String | Archaeological Dig            | The improvement of a dig, see the unique `Excavate what was found here` |
| landmarkImprovement                      | String | Landmark                      | The improvement that can be chosen after a dig |
| archaeologySiteTech                      | String | Archaeology                   | The technology whose civilizations are told when the sites appear |
| landmarkInfluenceWithCityStates          | Int    | 50                            | Influence with a city-state when a Landmark is built on its land |
| landmarkRestingInfluencePerEra           | Int    | 10                            | With `archaeologyChoicesLikeVp`: resting influence with a City-State per era of the builder for each Landmark on its land |
| ideologyTenetsLostOnSwitch               | Int    | 2                             | Tenets that are lost for good when a civilization changes its ideology (see the unique `Is an ideology`); the others become free tenets |
| ideologyAnarchyTurns                     | Int    | 2                             | Turns without gold, science, culture, faith and production after a change of ideology |
| tourismModifierDifferentIdeologies       | Int    | -34                           | Percent change of the tourism towards a civilization that follows another ideology (`Different Ideologies` of the tourism relations) |
| tradeRouteBaseGold                       | Float  | 1                             | Gold a trade route is worth before additions, for both ends. See the unique `Can establish trade routes` |
| tradeRouteCityGoldDivisor                | Int    | 20                            | Each end of an international route adds the gold of the city at the other end divided by this |
| tradeRouteGoldPerDifferentResource       | Float  | 0.5                           | Gold per resource of the other civilization that the route brings that the origin does not have |
| tradeRouteSeaBonusPercent                | Int    | 100                           | Percent more gold on routes by water |
| tradeRouteRiverBonusPercent              | Int    | 25                            | Percent more gold on routes over land between cities on a river |
| tradeRouteTargetTurns                    | Int    | 30                            | A route lasts the whole number of round trips that comes closest to this many turns, but at least two |
| tradeRoutePlunderGold                    | Int    | 100                           | Gold for plundering a route, more for one by water |
| tradeRouteDomesticBaseYield              | Float  | 3                             | Food or production of a route to a city of the same civilization |
| tradeRouteDomesticYieldPerEra            | Float  | 0.5                           | Added to the yield of a domestic route for every era of the destination civilization |
| tradeRouteModelLikeVp                    | Boolean | false                        | Vox Populi's trade route model instead of Brave New World's: the value of a route (gold, science, culture, food and production by the original's formulas), how long it lasts by the game speed, what a plunder pays, the range by `worldTradeRouteDistance` |
| tradeRouteVp                             | Object  | [See below](#traderoutevpconstants) | The defines of that model, only read with `tradeRouteModelLikeVp` |
| worldTradeRouteDistance                  | List of objects | empty                | `{"tiles": n, "percent": p}`: what the route range is multiplied by on a map of at least n tiles (the entry with the greatest n that fits); empty: 100 percent |
| resourceShortageLikeVp                   | Boolean | false                        | Vox Populi's rule for a major civilization that uses more of a strategic resource than it has, instead of the original's combat malus: units short of a resource they need do not heal and keep their strength, and at the start of each turn the buildings that use a short resource are refunded the shortage (in ruleset order, the most each building uses), each refunded copy adding 2 percent to the maintenance of all the civilization's buildings. The amount of the resource itself does not change |
| dealsBlockTemporaryForPermanent          | Boolean | false                        | A deal in which the AI gives something temporary and gets something permanent is refused (not used by any rule yet) |
| dealHumanPermanentForAiTemporary         | Boolean | true                         | With that rule: except when a human gives the permanent item |
| dealNoPeacetimeSellingFoundedCities      | Boolean | false                        | The founder of a city does not sell it unless at war (not used by any rule yet) |
| dealLuxuryValueLikeVp                    | Boolean | false                        | The AI values a luxury in a deal in the units of one gold per turn, as Vox Populi does (the last copy of an unhappy empire is not for sale) |
| monopoly                                 | Object  | [See below](#monopolyconstants) | The shares of the copies of a resource on the map that make a monopoly |
| leagueProposersPerSession                | Int    | 2                             | Civilizations that propose resolutions in a session of the World Congress |
| leagueProposalsPerProposer               | Int    | 1                             | Resolutions each proposer proposes |
| leagueSessionSoonWarningTurns            | Int    | 5                             | Turns ahead of a session that the player is warned |
| leagueFallbackSessionInterval            | Int    | 12                            | Turns between sessions at Standard speed while no era has the unique `World Congress sessions: ...` |
| leagueFallbackDelegates                  | Int    | 1                             | Delegates of each civilization while no era has that unique |
| leagueLeadersVoteBonusCount              | Int    | 2                             | Civilizations leading the vote that get the delegates of `leadersVoteBonusOnFail` when the World Leader vote fails |
| leagueWorldLeaderCivCoefficient          | Float  | 1.443                         | Votes needed for the World Leader: the sum of `max(0, a * ln(civs) + b)` for civilizations (a, b = this and the next) and `max(0, c * ln(city-states) + d)` for city-states |
| leagueWorldLeaderCivConstant             | Float  | 7                             | See above (b) |
| leagueWorldLeaderCityStateCoefficient    | Float  | 16.023                        | See above (c) |
| leagueWorldLeaderCityStateConstant       | Float  | -13.758                       | See above (d) |
| leagueRegularHostProposals               | Int    | 1                             | Proposals the host makes in a regular session |
| leagueProjectTier1Threshold              | Float  | 0.5                           | Contribution tier 1 of a World Congress project begins at this multiple of a civilization's share of the project cost |
| leagueProjectTier2Threshold              | Float  | 1                             | The same for tier 2. Tier 3 goes to the highest contributor, who must have at least this much |
| leagueProjectProgressWarningPercent      | Int    | 33                            | Percent of its cost at which the civilizations are told how far a project is |

Legend:

- [^A]: Max amount of experience that can be gained from combat with barbarians
- [^B]: Formula for city Strength:
    Strength = baseStrength + strengthPerPop + strengthFromTiles +
    ((%techs \* multiplier) ^ exponent) \* fullMultiplier +
    (garrisonBonus \* garrisonUnitStrength \* garrisonUnitHealth/100) +
    defensiveBuildingStrength
    where %techs is the percentage of techs in the tech tree that are complete
    If no techs exist in this ruleset, %techs = 0.5 (=50%)
- [^X]: The distance that cities can attack. Minimum 0.
- [^Y]: The tiles in distance that population in cities can work on. Note: Higher values may lead to performace issues and may cause bugs. cityWorkRange may be greater than cityExpandRange. Minimum 1, maximum 100.
- [^Z]: The distance that cities can expand their borders to. Note: Higher values may lead to performace issues and may cause bugs. Minimum 1, maximum 100.
- [^C]: Formula for Unit Supply:
    Supply = unitSupplyBase (difficulties.json)
    unitSupplyPerCity \* amountOfCities + (difficulties.json)
    unitSupplyPerPopulation \* amountOfPopulationInAllCities
    unitSupplyBase and unitSupplyPerCity can be found in difficulties.json
    unitSupplyBase, unitSupplyPerCity and unitSupplyPerPopulation can also be increased through uniques
    Only the units that use supply count against it: those with a combat strength, and those with the unique "Uses unit supply" (the Caravan, Cargo Ship and Work Boat of the Civ V rulesets). Workers, settlers, Great People and religious units do not.
    For an AI major civilization or a city-state, the base, the amount per city and the population share (a percentage of the citizens, in place of unitSupplyPerPopulation) are the aiUnitSupply... and cityStateUnitSupply... fields of the difficulty the game is played at, and the sum is multiplied by (100 + the bonus percent) / 100.
- [^U]: Formula for the gold maintenance of units (0.5 gold per unit that is not free):
    cost = (paidUnits \* 0.5 \* (1 + unitMaintenanceGameMultiplier \* progress)) ^ (1 + progress / unitMaintenanceExponentDivisor)
    progress is the turn divided by the number of turns of the game speed, paidUnits is the number of units minus unitMaintenanceFreeUnits and the units made free by uniques.
    The Civ V rulesets use 0, 8 and 7.
- [^V]: The culture a city needs for its next tile. false: 6 \* (tilesClaimed + 1.4813) ^ 1.3, the percentages of "Culture cost of natural border growth" multiplied.
- [^GGThreshold]: How many more combat points the next Great General or Admiral needs after one is earned. false: 50 more each. true: as in Civ V (`CvPlayer::createGreatGeneral`) the base of 200 grows by 100 x (number earned / 10 + 1) each time, so 300, 400, ... 1100, then steps of 200.
- [^GGBorders]: Percent of extra Great General points a land unit earns when it fights on a tile owned by its own civilization, added to the other bonuses such as "is earned faster" (`CvUnit::changeExperience`, `COMBAT_EXPERIENCE_IN_BORDERS_PERCENT`, 100 in Civ V).
- [^Withdraw]: How units with "Withdraws before melee combat" withdraw. false: when the chance of the unique comes up, to a random tile behind them. true: as in Civ V (`CvUnit::CanWithdrawFromMelee`) the chances of all such uniques of the unit are added (a unique without a chance counts 100), `withdrawModEnemyMoves` is added for each move the attacker has above 2 and `withdrawModBlockedTile` for each of the three tiles behind the unit that it cannot retreat to, a unit with all three blocked does not withdraw, and one that does goes to the tile straight behind it if it can. The two modifiers do nothing unless `withdrawLikeCivV` is true.
- [^CityBombard]: Whether a city can bombard an enemy city within its range. false: it can only bombard units outside cities, as in Civ V (`CvCity::canRangeStrikeAt`). This applies to the player's bombard action and to the AI alike.
- [^TilesTaken]: The change of a city-state's influence with a civilization that takes tiles from it with "Gain control over [tileFilter] tiles in a [nonNegativeAmount]-tile radius" (the culture bomb of a Great Artist or a Citadel). false: `cityStateInfluenceChangeForTilesTaken` for each tile taken. true: as in Civ V (`CvUnit::PerformCultureBomb`, -50) once for each city-state tiles were taken from.
- [^Treasury]: What happens to the treasury when the turn's income would take it below zero. false: the civilization goes into debt, which costs science. true: as in Civ V (`CvTreasury::DoGold`) the treasury is set to 0 and the rest of the deficit is lost, so the gold of a civilization is never negative. Units are disbanded under a deficit in both cases.
- [^PlotBuy]: What buying a tile costs, how far away it may be, and which tile the border takes. false: 50 x (distance - 1) + 5 x the tiles the city claimed, with the "Gold cost of acquiring tiles" percentages multiplied, within the city's expand range. true: as in Civ V (`CvPlayer::GetBuyPlotCost`, `CvCity::GetBuyPlotCost`, `CvCity::CanBuyPlot`): (50 + 5 x the tiles the civilization has bought so far, times (100 + the sum of the "[in all cities]" percentages) %, times 2 for a city-state) x (100 + 100/3 x (the cost of the cheapest path from the city to the tile, see `influenceCost` of [terrains](3-Map-related-JSON-files.md), minus the same for the city's cheapest unowned tile) ) %, minus 100 points for a tile with a resource the civilization can see (the factor applies only above 100%), times the game speed's gold cost, times (100 + the sum of the percentages of the city's own cost uniques, as the Krepost has) %, rounded down to 5. A tile can be bought within 3 tiles of the city (`MAXIMUM_BUY_PLOT_DISTANCE`), and only next to a tile the same city owns. The tile the border takes (by culture, or as extra territory when founding) is, as in `CvCity::GetBuyablePlotList`, one of the unowned tiles within the expand range with the lowest score, picked at random: 100 x the cost of the influence path, -105 for a resource the civilization can see (a bonus resource beyond the work range 0, within it -104), else +25 for water and +100 beyond the work range, -5 for an improvement (+100 for a barbarian camp), -105 for a natural wonder, -1 for every point of food, production, gold, science, culture and faith, -1 for every unowned neighbor with a resource or a natural wonder (-1 more if a wonder is within the work range), +1000 if no neighbor belongs to the city. Otherwise the tile with the best `rankTileForExpansion` is taken. The Civ V rulesets set it true.
- [^SightBeyond]: Which tile one step beyond a unit's sight range is seen. false: one that is taller than the tile the unit stands on. true: as in Civ V (`CvPlot::changeAdjacentSight`, `seeThroughLevel` without the forest or jungle on the tile) one that is taller than what blocks the line to it, whatever the unit stands on; a unit on a hill then sees a hill or mountain behind flat land, and a forest on flat land behind flat land is no longer seen.
- [^MinSight]: The least sight range a unit has, whatever the "Sight" uniques add up to (the base is 2). 1: a unit always sees the tiles around it. 0: as in Civ V (`CvUnit::visibilityRange`), where an embarked unit has `EMBARKED_VISIBILITY_RANGE` (0) plus the sight of its promotions, so the global "[-2] Sight <for [Embarked] units>" of the Civ V rulesets brings the base 2 down to 0. With `sightBeyondRangeLikeCivV` a unit of range 0 still sees the ring of tiles around it.
- [^GiftTravel]: Gifting a military unit to a city-state from anywhere (the action "Gift unit to [city-state]"; inside the city-state's borders the plain "Gift unit" works at once). 0: not possible. Otherwise as in Civ V (`CvPlayer::AddIncomingUnit`, `MINOR_UNIT_GIFT_TRAVEL_TURNS` 3): the unit is removed from its owner at once and a new unit of the same type (with none of its promotions or health) appears next to the city-state's capital after that many turns of the city-state; the influence of the gift comes with it. A civilization can have one such unit on its way to each city-state.
- [^IntercHealth]: The chance of an air unit (a fighter, a missile) to intercept an air attack. false: the "[relativeAmount]% chance to intercept air attacks" of its uniques, whatever its health. true: as in Civ V (`CvUnit::currInterceptionProbability`) that chance times its health divided by 100, rounded down; the same value picks the best interceptor, and an interceptor left with no chance is not picked. Units that are not air units are not scaled.
- [^Uprising]: How the countdown to the rebels of a very unhappy empire (the "Rebel units may spawn" unique) runs. false: a countdown of `baseTurnsUntilRevolt` + 0-2 turns (times the game speed, if longer) that starts over whenever the empire is not very unhappy at the start of a turn. true: as in Civ V (`CvPlayer::DoUpdateUprisings`) a counter that winds down one a turn only while the empire is very unhappy and keeps its value otherwise; the first time it is seeded it is half as long, and after an uprising it starts over at full length.
- [^LocalHappiness]: Whether the happiness a city has from its buildings, from policies and beliefs that give happiness for each building of a kind, from the garrison policy and from beliefs per city or on rivers is limited to the city's population (two thirds of it, rounded, for a civilization with a unique that changes the unhappiness from the number of cities, like India's). Civ V (`CvCity::GetLocalHappiness`) does that; its `UnmoddedHappiness` buildings (Circus Maximus, Notre Dame, Eiffel Tower, Prora) are exempt, which a ruleset marks with the unique "Happiness from this building is not limited by the population of the city". Everything else, like luxuries, trade routes and wonder uniques for the whole civilization, is not limited. The part above the limit shows in the city's happiness as "Population limit on local happiness".
- [^CityNeeds]: Which happiness model the ruleset plays. false: the original's, one number for the whole civilization (luxuries, the cities' own happiness and the civilization's sources, less the unhappiness of the cities and of public opinion) whose effects are uniques of `GlobalUniques.json`. true: Vox Populi's per-city needs (`CvCity::GetUnhappinessAggregated`, `GetLocalHappiness`, `CvPlayer::DoUpdateTotalHappiness`, `DistributeHappinessToCities`, `CalculateNetHappiness`): every city of a major civilization has needs (Distress, Poverty, Illiteracy and Boredom, each the citizens its food and production, gold, science or culture do not cover at the median of all major civilizations' cities per citizen; religious unrest; occupation, famine, pillaged tiles, isolation, specialists) that are numbers of unhappy citizens, the empire has a pool of happiness (difficulty, game speed, luxuries, religion, natural wonders, city-states, uniques) that it hands out to its cities, and the approval rating (happy citizens against unhappy ones) decides the effects. The stat Happiness then is the approval rating less `needs.unhappyThreshold`, so 0 is the line under which the empire is unhappy. The medians are kept in the saved game and updated once per game turn. The effects on growth, the production and gold and faith price of settlers and combat unit production, and combat strength are in the code; the uniques for needs ([Reduces [need] by ...](../uniques.md#global-uniques) and its siblings) give buildings and policies their effect. The numbers are [NeedsConstants](#needsconstants), the difficulty's `baseHappinessCapital`, `aiBaseHappiness`, `aiBaseHappinessCapital`, `populationNeedModifier`, `aiPopulationNeedModifier`, `empireSizeNeedModifier` and `aiEmpireSizeNeedModifier`, and the speed's `startingHappiness`. The Civ V rulesets of this fork do not set it yet.
- [^InstantYields]: What the unique "Gain [amount] [stat] <(modified by game speed)>" (and "Gain [relativeAmount]% of the triggering amount as [stat]") is multiplied by. false: the entry of the stat in the speed's cost modifiers, rounded (as before). true: as in Vox Populi (`CvPlayer::doInstantYield`) the `instantYieldModifier` of the game speed (the original's `InstantYieldPercent`: 75, 100, 150 and 200 percent for Quick, Standard, Epic and Marathon), cut off at the whole number. The events that the original does not scale (purchases, unit production, refunds, quest rewards, bullying, combat experience, research agreements) are the uniques that are written without the modifier.
- [^InstantYieldsTotal]: Where an instant yield "Gain [amount] [stat] <(modified by game speed)>" with `<for every ...>` modifiers is cut to a whole number. false: each copy that the multipliers make is changed by the speed and cut by itself. true: as in Vox Populi (`CvPlayer::doInstantYield`) the amount times every multiplier but `<for every [Era scaler]>` is changed by the speed and cut once, then multiplied by the `Era scaler` (2 Faith for each of 250 followers is 375 at Quick, not 250). Each unique is cut by itself; the original adds up all sources of a yield first.
- [^RepeatTechs]: Whether researching a tech that can be researched again (Future Tech) fires `upon discovering [techFilter] technology` for the instant yields of the civilization's uniques. false: every time. true: never, as in Vox Populi (`CvTeam::setHasTech` calls `processTech`, which gives the tech's instant yields, only for a tech that does not repeat). The tech's own triggered uniques work either way.
- [^PurchasePrice]: The gold price of a construction from its production cost (`GetBasePurchaseCostFromProduction`), see [PurchaseConstants](#purchaseconstants). The Civilopedia reads it too.
- [^Investments]: Whether buying a building with gold is an investment (`CvCity::PurchaseBuilding` with `MOD_BALANCE_BUILDING_INVESTMENTS`, which Vox Populi sets). false: the building is built at once. true: the gold price is `goldBuildingCostPercent` percent of the formula's, and the building is not built but stays in the queue (it is put at the front if it was not in it) with a lower production cost: `(100 + buildingInvestmentBaseline)` percent of what it cost, with the percentage divided by `buildingInvestmentWonderDivisor` for a world wonder, and never less than one more production than the city has put into it. A building cannot be invested in twice, and not when the city finishes it next turn anyway. The investment is gone when the building is built or sold or the city changes hands.
- [^WonderCost]: The percent the production cost of a world wonder rises by for each world wonder the civilization has (`CvPlayer::getProductionNeeded` with `MOD_BALANCE_WORLD_WONDER_COST_INCREASE`): the entry of the list for how many eras ago the era of that wonder's technology was, the first for the civilization's own era, the last one for all earlier eras (Vox Populi: `[25, 15, 10, 5]`). A wonder without a technology does not count. Empty: no increase.
- [^Cooldown]: Whether buying a unit sets a cooldown (`CvCity::Purchase`): with gold the unit's `purchaseCooldown` less the reduction of the city's buildings (`purchaseCooldownReduction` of military, `purchaseCooldownReductionCivilian` of civilian units), counting down one each turn of the city, during which the city cannot buy a unit of that kind (military if the unit has a combat strength) with gold; with faith the unit's `globalFaithPurchaseCooldown` for the civilization and `localFaithPurchaseCooldown` for the city, times the game speed's `productionCostModifier`.
- [^SupportFire]: Whether a unit with the unique "Before engaging in combat performs an extra ranged attack with [amount]% of melee combat strength" (the Zulu Impi) gets no strength bonus for being fortified. false: it gets the normal bonus. true: as in Civ V (`CvUnit::fortifyModifier`, which returns 0 for a unit with ranged support fire).
- [^UnitHurry]: How much a unit with "Can speed up construction of a building" (the Great Engineer) adds to the city's production. false: (300 + 30 x population) x the production cost modifier of the game speed, at most what is still missing minus 1, and the action is offered even if the city completes the item next turn anyway. true: as in Civ V (`CvUnit::getMaxHurryProduction`, `getHurryProduction`, `canHurry`) the same amount in whole numbers using the speed's percentage, at most everything that is still missing, and the action is not offered when the production would be completed next turn anyway.
- [^MinorDeposits]: Where the small deposits of strategic resources ("Minor deposits generated with weight [amount]") go, and how many. One deposit for every `minorDepositFrequency` tiles of the list they are drawn from. false: the list holds every land tile that does not block resources, hills, marsh and mountains included. true: as in `AssignStartingPlots:GenerateGlobalResourcePlotLists` and `PlaceSmallQuantitiesOfStrategics`, only flat land that has no feature but forest or jungle, no resource and no start or city-state on it (the original's `land_list`: no hills, mountains, marsh, flood plains, oasis or natural wonders), so the weights of the "minor deposits" uniques for hills and marsh are not used. The Civ V rulesets set true and 23.
- [^CityStateSites]: Where a city-state may be put, in the region it was assigned to and in the count of land for the city-states of uninhabited landmasses. false: not on land that is undesirable for a start (snow, mountains, desert outside of desert regions: the `Considered [Undesirable] when determining start locations` terrains) and not on a tile at the edge of the map. true: as in Civ V (`AssignStartingPlots:CanPlaceCityStateAt`, `AssignCityStatesToRegionsOrToUninhabited`) on any land tile that is no mountain and not snow, so deserts, tundra and tiles next to the map edge are possible. The Civ V rulesets set true.
- [^Annexed]: The happiness an annexed city of a conquered civilization costs (a puppet or a city of the civilization's own founding does not count): its citizens' unhappiness is multiplied by the factor, and the city costs the flat amount more as "Occupied City". The Civ V rulesets use 1.34 and 0, as `CvPlayer::GetUnhappinessFromOccupiedCities` does (`UNHAPPINESS_PER_OCCUPIED_POPULATION`).
- [^NukeCivilians]: The damage, out of 100 hit points, from a nuclear blast at which a civilian unit dies. 60: it dies when its health is down to 40 or less. Civ V (`CvUnitCombat::ApplyNuclearExplosionDamage`) uses 6, which kills every civilian in the blast, because a blast does at least 30 damage.
- [^Disband]: The gold a disbanded unit gives, in percent of its production cost, times the health it has left and the game speed, plus the same for every unit it carries (a transport). 0: a twentieth of its purchase price, whatever its health. Civ V (`CvUnit::GetScrapGold`, `DISBAND_UNIT_REFUND_PERCENT`): 25.
- [^HillFeature]: Movement points extra for entering a hill that has a feature (forest, jungle, fallout) on top, in addition to the feature's own movement cost. 0: the feature's cost only. Civ V (`CvUnitMovement::GetCostsForMove`, `HILLS_EXTRA_MOVEMENT`): 1, so a forested hill costs 3.
    true: the formula of Civ V, 15 + (10 \* tilesGainedByCulture) ^ 1.1, times 1.5 for City-States, the percentages added up (at least -85), times the game speed, rounded down to a multiple of 5.
    The tiles gained by culture do not count tiles that were bought. The Civ V rulesets set true.
- [^SaleDivisor]: The gold for selling a building. 10: the raw `cost` / 10. Any other value: as in Civ V (`CvCityBuildings::GetSellBuildingRefund`) the production cost of the building for the city's civilization (game speed, start era, unique modifiers) divided by this value; Civ V uses 4.
- [^UpgradeSum]: How the percentages of the `[relativeAmount]% Gold cost of upgrading` uniques of a civilization combine. false: they are multiplied. true: as in Civ V (`CvPlayer::SetUnitUpgradeCostMod`) they are summed (two times -33% give a factor 0.34), and the sum is not below `unitUpgradeCostDiscountMax` (-75, GlobalDefines `UNIT_UPGRADE_COST_DISCOUNT_MAX`); the limit is only used when the percentages are summed.
- [^UpgradePrice]: How the gold price of a unit upgrade is made. false: `unitUpgradeCost` (base plus gold per production of the cost difference, times the game speed). true: as Vox Populi's option `BALANCE_UNIT_UPGRADE_COST` (`GetBaseUnitUpgradeCost`): the difference between the gold purchase prices of the two units (the `purchase` curve of their production costs with the game speed's and the starting era's training percentages and the speed's hurry percentage; half the new price if the old unit costs nothing), never below 0. The base, gold per production and era multiplier of `unitUpgradeCost` are not read then.
- [^MinorTech]: The share of the living major civilizations that must know a technology for the City-States to get it for free (when they could research it). 0: more than half of them. Otherwise as in Civ V (`CvTeam::DoMinorCivTech`, `MINOR_CIV_TECH_PERCENT`): at least the number of major civilizations times this percentage / 100 (rounded down, but at least 1), so 40 in the Civ V rulesets.
- [^MaxedGold]: The percentage of the production stored in a unit (`maxedUnitGoldPercent`) or non-wonder building (`maxedBuildingGoldPercent`) that is converted to gold when the construction can no longer be built because its limit was reached (`MaxNumberBuildable`), and of the production above the overflow cap (the cost of the item or the production of the city per turn, whichever is higher) when a unit or building is completed. 0: it is lost. Production put into a wonder that cannot be built any more is always converted to gold at 100%. Civ V (`CvCity::doCheckProduction`, `CvCity::popOrder`): both 100.
- [^CityHeal]: What a damaged city heals at the end of each turn. false: 20 hit points. true: as in Civ V (`CvCity::doTurn`) 20 hit points, one more in the capital of a major civilization, and the sum of the `cityStrength` of its buildings times 100 divided by 500 (rounded down) more, so Walls (5) add 1 and Walls plus Castle (12) add 2.
- [^ResDemand]: The countdowns of a city's demand for a resource. false: a new city demands one after 15 + random(10) turns (10 more in a new capital), and the We Love the King Day lasts 20 turns, both scaled by the game speed. true: as in Civ V (`CvCity::DoSeedResourceDemandedCountdown`, `DoTestResourceDemanded`) the capital adds 25 turns, and neither number is scaled by the game speed.
- [^ImpOnRes]: Improvements on a tile with a resource. false: any improvement the terrain allows. true: as in Civ V (`CvPlot::canHaveImprovement`) a tile whose resource the civilization can see takes only an improvement that improves that resource, or one with the unique "Can be built on tiles with any resource". Improvements already on the map when a game is loaded stay.
- [^ElectionSpeed]: false: `cityStateElectionTurns` turns between city-state elections on every game speed. true: as in Civ V (`CvGame::GetTurnsBetweenMinorCivElections`) the number is multiplied by the modifier of the game speed (rounded down), so Marathon (3) has an election every 45 turns.
- [^G]: Whether the start of a civilization or City-State is improved with the rules of Civ V (how many bonus resources, when a hill or a horse/iron is added, stone on grass starts).
    The rules name the terrains and resources of the Civ V rulesets (Grassland, Plains, Hill, Wheat, Cattle, ...), so it only fits them. false: the generic rules, which use the yields of the tiles.
- [^T]: How the map generator widens the coast. Empty: `maxCoastExtension` passes, each ocean tile next to a coast tile becomes coast with 1 in 2. Otherwise ocean next to land becomes coast, then for each entry one pass in which an ocean tile next to a coast tile becomes coast with 1 in that number (Civ V: `[4, 4]`, so coast reaches 3 tiles from land at most).
- [^SeaOil]: A strategic resource that is also found in the sea (it needs a `Deposits in [Coast] tiles always provide [n] resources` unique), placed after the land deposits: half of the units on land divided by n deposits, on coast tiles away from each other. Civ V: Oil. The resource then needs no `Generated with weight` rule for Coast. Empty: no such rule.
- [^CivVTerrain]: Whether the map generator makes the base terrain of land and its features as the Civ V map scripts do: by latitude (snow from 75 % of the way to the pole, tundra from 60 %, grassland near the equator, desert and plains in the bands between from fractal noise), then the rivers and lakes of the scripts (four passes of sources by hills, inland land and a budget of edges per landmass; rivers turn towards wet low ground and keep their first heading), and jungle, forest, marsh, oases, flood plains, ice and atolls from fractal thresholds. The temperature and rainfall options of the scripts come from `temperatureShift` (beyond +-0.15) and `vegetationRichness` (below 0.3, above 0.5) of the map parameters. It names the terrains and features of the Civ V rulesets (Grassland, Plains, Desert, Tundra, Snow, Forest, Jungle, Marsh, Oasis, Flood plains, Ice, Hill). false: the temperature and humidity ranges of the terrain definitions.
- [^NoCoastMountains]: With `terrainGeneratorLikeCivV`, whether a mountain on a land tile next to water becomes hills (with the base terrain the latitude gives), as Vox Populi's `FeatureGenerator:AddFeatures` does. false: Brave New World's, mountains stay on the coast.
- [^VpLux]: With `luxuryPlacementLikeCivV`, whether the luxuries at the civs' starts are placed as Vox Populi's `AssignStartingPlots.lua` does: 2 at each start (3 with a Legendary Start) with no extra ones for a poor region, and Ivory placed as a special case, at least 2 and `ceil(civs x 0.75)` in all (like marble). false: Brave New World's 1 at each start (2 with a Legendary Start) plus one for each failed fertility test.
- [^CivVLux]: Whether luxuries are assigned and placed as the Civ V map scripts do: each region (in the order of its type) gets one luxury by the weights of the resources (`Generated with weight [n] <in [type] Regions>`), each luxury at most 1, 2 or 3 times (by civ count) and at most 8 kinds in all; water luxuries need a start by the sea and 12 coast tiles; three kinds go to the City-States (`Generated near City States with weight [n]`), marble is special, some kinds are left out by world size and the rest is placed anywhere. It names the luxuries of Civ V. false: the generic placement.
- [^VpRegions]: Whether the civilizations are dealt to the continents as Vox Populi's map script does: continents with less than a quarter of the biggest one's fertility are ignored, and a civilization that opens a continent is followed by a second one on it (the last civilization cannot open one), so on a Duel map both civilizations share a continent. Falls back to the biggest continent, or to rectangles over the whole map, when too few continents are fertile enough. false: each civilization goes to the continent with the best fertility divided by one plus the civilizations already there.
- [^VpCityStates]: Whether the city-states are placed as Vox Populi's `AssignStartingPlots.lua` does (`CanPlaceCityStateAt`, `PlaceCityStateInRegion`, `PlaceCityStates`). A site is a land tile that is no mountain (snow is allowed), not on the outermost ring of the map (unless the map wraps, for the sides), with no ice within 1 tile, and with a fertility within 2 tiles of at most 30 (`Plot_GetFertilityInRange`: for each tile within 2, food + production + gold + 2 x (science + culture + faith), plus 0.25 beside fresh water (not on hills), -0.5 on a lake or jungle, -1 on a bare coast tile, +8 for a luxury, +2 for a bonus resource, +ceil(3 x sqrt(amount)) for iron and horses, none for impassable or deep ocean tiles, each divided by its distance). The city-states are placed in the order they were assigned (the regions' share, then the uninhabited landmasses, then the compensation for shared luxuries and poor regions), only on sites free of the impact of any start or city-state: a city-state that finds none in its region is discarded, one assigned to uninhabited landmasses takes a random free site of the coastal ones, then the inland ones (landmasses with fewer than 2 sites do not count as uninhabited land, and a landmass with no site left sends it to a random region). Afterwards the discarded ones get a random free site anywhere on the map; those that still find none are dropped (the game start may seat them elsewhere). false: a city-state that finds no free site is forced into the impact of a start, then onto a start.
- [^VpStartFinder]: Whether the start of each region is chosen as Vox Populi's map script does (`FindStart`, `EvaluateCandidatePlot`): the best scored eligible tile of the center area, else of the middle area (no preference of river tiles over coastal ones over the rest), +80 score next to the sea and +40 on a river or next to fresh water, its own score tables, at least 1, 1 and 3 production tiles in the three rings and no cap on junk tiles; failing both, the eligible tile of the outer area nearest to a corner of the region, and failing all, the best scored tile of the areas. A tile within 8 tiles of another start is never eligible and loses score by the rings of the start's impact. false: Brave New World's, with river tiles before coastal before dry, +40 next to the sea, minimums of 0, 0 and 2 production, at most 9 junk tiles, and the outer tile nearest to the region's center.
- [^CivVAssign]: Whether the start regions are dealt to the civilizations as the Civ V map scripts do: civilizations with a `Coast` start bias take a random region by the sea (those with the unique `Chooses its start region before the other civilizations with a Coast start bias` first), else by a lake, else by a river; those with preferred terrains a random region of that type (one preferred type first, then more, in the order of the types; none of the type left: the region with the most of that terrain); those that avoid terrains a random region without them; the rest at random. false: the generic assignment (the region with most of the wanted terrain). Also for the City-States: they are only sent to uninhabited land as far as the regions did not take them already, and the extra ones go to the regions with the least fertility per land tile, not the least in total.
- [^CivVExtras]: The extra steps of the Civ V resource placement: one bonus resource of the kind of the region on the third ring of every start (two sheep in hills regions), more bonus resources in hills regions, a small deposit of coal, oil or aluminum at 3 in 4 City-States, top-ups of iron, horses, coal, aluminum, oil and uranium by the number of civilizations (on land tiles that can hold them), and no sugar on jungle. It names the resources of Civ V, and replaces the corresponding generic steps.
- [^VoxPopuliStrategics]: Places the strategic resources as `AssignStartingPlots.lua` of Vox Populi does (`PlaceStrategicAndBonusResources`). Iron, horses, coal, oil, aluminum and uranium each have their own plot lists (35 in all, such as flat grassland without a feature or hills with forest), each with its own number of tiles per deposit; a list yields `ceil(size / tiles per deposit)` deposits, so every non-empty list yields at least one whatever the size of the map. Each resource has its own impact layer: a deposit blocks its own tile for all resources and keeps the deposits of its own resource 1 to 4 tiles away where it can. Then follow: a small deposit at 3 in 5 City-States, small deposits on one in 30 land tiles, oil in the sea (a fifth of the units of oil on land, in deposits of 2 and of 3), and the top-ups of the resources by the number of civilizations. The amounts of a deposit are those of `majorDepositAmount` (the lists) and `minorDepositAmount` (the others); the variance of a fifth more or less in 1 of 4 deposits each is applied. It names the strategic resources of Civ V, and replaces the major deposits per terrain (`MajorStrategicFrequency`), the minor deposits, `seaDepositsOfLandResource` and the strategic parts of `resourceExtrasLikeCivV` when set.
- [^CivVWonders]: Natural wonders are chosen and placed as the Civ V map scripts do: as many as the world size gives (2 on Duel up to 7 on Huge; fewer if fewer wonders have a candidate tile), drawn one by one by their `weight` from the wonders that have a candidate tile, and placed in the order drawn. A wonder that fails to be placed is replaced by the next one drawn. The generic way draws the number by map radius first and places the ones with the fewest candidate tiles first.
- [^CivVRuins]: Ancient ruins are placed as the Civ V map scripts place goody huts: on every landmass of at least 3 tiles one ruin per `tilesPerAncientRuinLikeCivV` land tiles (rounded to nearest), on tiles without a resource that are at least 3 tiles from every other ruin and more than 3 tiles from every start. Replaces `ancientRuinCountMultiplier`.
- [^D]: The minimal distance that must be between any two cities, not counting the tiles cities are on
    The number is the amount of tiles between two cities, not counting the tiles the cities are on.
    e.g. "C__C", where "C" is a tile with a city and "_" is a tile without a city, has a distance of 2.
    First constant is for cities on the same landmass, the second is for cities on different continents.
    Minimum 0.
- [^E]: NaturalWonderGenerator uses these to determine the number of Natural Wonders to spawn for a given map size. The number scales linearly with map radius: #wonders = radius * naturalWonderCountMultiplier + naturalWonderCountAddedConstant. The defaults effectively mean radius 10 - 1, 15 - 2, 20 - 3, 30 - 4, 40 - 5 (these were the radii of the sizes Unciv used to offer, Tiny to Huge), radius >=109 - all G&K wonders. The sizes offered now are those of Civ V (Duel, Tiny, Small, Standard, Large, Huge); their radius is that of the hexagon with as many tiles as their grid.
- [^F]: MapGenerator.spreadAncientRuins: number of ruins = suitable tile count * this
- [^H]: MapGenerator.spawnLakesAndCoasts: Water bodies up to this tile count become Lakes
- [^I]: RiverGenerator: river frequency and length bounds
- [^J]: A [UnitUpgradeCost](#unitupgradecost) sub-structure.
- [^K]: Maximum foundable Religions = religionLimitBase + floor(MajorCivCount * religionLimitMultiplier)
- [^L]: Cost of pantheon = pantheonBase + CivsWithReligion * pantheonGrowth
- [^M]: When the AI decides whether to build a work boat, how many tiles to search from the city center for an improvable tile
- [^N]: The maximum rank any spy can reach
- [^O]: How much skill bonus each rank gives
- [^P]: The number of turns a civ has to wait before negotiating for peace
- [^Q]: The number of turns before a revolt is spawned
- [^R]: The number of turns between city-state elections
- [^S]: If set, the Improvement picker will silently skip improvements whose tech requirement is more advanced than your current Era + this value. Example: With a 0, Trade posts will not show until the Medieval Era, with a 1 they will already show in the CLassical Era.
- [^W]: Number of air units that can be stationed in a city, not including carried/transported air units.

#### UnitUpgradeCost

These values are not merged individually, only the entire sub-structure is.

| Attribute     | Type  |    | Notes |
|---------------|-------|----|-------|
| base          | Float | 10 |       |
| perProduction | Float | 2  |       |
| eraMultiplier | Float | 0  |       |
| exponent      | Float | 1  |       |
| roundTo       | Int   | 5  |       |

The formula for the gold cost of a unit upgrade is (rounded down to a multiple of `roundTo`):
        (
            max((`base` + `perProduction` \* (new_unit_cost - old_unit_cost)), 0)
            \* (1 + eraNumber \* `eraMultiplier`) \* `civModifier`
        ) ^ `exponent`
With `civModifier` being the multiplicative aggregate of ["\[relativeAmount\]% Gold cost of upgrading"](../uniques.md#global-uniques) uniques that apply.

#### NeedsConstants

Only read with `cityNeedsLikeVp`. These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's defines (`CoreDefineChanges.sql` of the Community Patch, `DefineChanges.sql` of Vox Populi); percentages of the needs are in percent points, the ones marked "hundredths" or "thousandths" are in that unit.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| unhappyThreshold | Int | 50 | The empire is unhappy while its approval rating (percent) is below this: growth and settlers suffer, units fight 10% worse |
| veryUnhappyThreshold | Int | 35 | Very unhappy below this: units fight 20% worse |
| superUnhappyThreshold | Int | 20 | Super unhappy below this |
| globalGrowthPenaltyPerPoint | Float | 2.5 | Percent growth lost in every city per approval point under `unhappyThreshold`, at most 100 |
| globalSettlerPenaltyPerPoint | Float | 2.5 | Percent production of settlers lost in every city per approval point under `unhappyThreshold` |
| maxUnitProductionPenalty | Int | -75 | The most percent production of a settler or combat unit unhappiness takes, and the most a settler's price rises by |
| localHappinessGrowthPerPoint | Int | 2 | Percent growth per point of a city's happiness over its unhappiness (the delta) |
| localUnhappinessGrowthPerPoint | Int | 10 | Percent growth lost per point of a city's unhappiness over its happiness |
| localUnhappySettlerPenalty | Int | -10 | Percent production of settlers per point of the delta under 0 (the formula multiplies by -1 twice) |
| localUnhappyCombatUnitPenalty | Int | -10 | The same for combat units |
| veryUnhappyMaxCombatPenalty | Int | -20 | Percent combat strength lost while very unhappy; half of it while only unhappy |
| medianPercentile | Int | 50 | The median of the cities' yields per citizen is the element number size x this / 100 of the sorted list |
| techMedianPercentile | Int | 50 | The same for the technologies the major civilizations know |
| medianMinPopulation | Int | 3 | A city needs this many citizens to count for the medians |
| medianRateOfChange | Float | 0.65 | The share of this turn's median in the smoothed one |
| medianBaseModifier | Int | 100 | Percent of the modified median a citizen has to make |
| capitalNeedModifier | Int | 25 | Percent points the needs of the capital rise by |
| citySizeNeedModifier | Int | 0 | Hundredths of a percent point per citizen of the city |
| techNeedPerTechAboveMedian | Int | 100 | Hundredths of a percent point of every need per technology above the median |
| techNeedPerTechBelowMedian | Int | -200 | The same per technology below it (negative: the needs fall) |
| techNeedPercentResearched | Int | 0 | Percent points per percent of the technologies researched (in hundredths) |
| empireSizeNeedPerCity | Int | 500 | Hundredths of a percent point per city but the capital and the puppets |
| empireSizeNeedPerPopulation | Int | 125 | Thousandths of a percent point per citizen outside puppets |
| empireSizeNeedCap | Int | 100000 | The most the two can add |
| unhappinessPerReligiousMinority | Float | 0.5 | Unhappy citizens per citizen that does not follow the majority religion |
| unhappinessPerStarvingPop | Float | 1 | Per food a city is short of |
| unhappinessPerPillagedTile | Float | 0.5 | Per pillaged improvement |
| unhappinessPerIsolatedPop | Float | 0.34 | Per citizen of a city not connected to the capital |
| unhappinessPerSpecialist | Int | 100 | Hundredths of an unhappy citizen per specialist |
| puppetCitizensPerUnhappiness | Int | 4 | Citizens of a puppet per unhappy one |
| unhappinessPerOccupiedPopulation | Float | 1 | Unhappy citizens per citizen of an occupied city (1.34 in Brave New World) |
| happinessPerLuxury | Int | 2 | Happiness of each luxury the empire has (Vox Populi: 2) |
| happinessPerNaturalWonder | Int | 1 | Happiness of each natural wonder |
| puppetYieldMultiplier | Int | 30 | Percent of the yields of a puppet (read by the conquest rules) |
| resistanceTurnsPopulationNumerator | Int | 2 | The turns of resistance after a conquest are population x this / the next (read by the conquest rules) |
| resistanceTurnsPopulationDenominator | Int | 3 | |
| annexHalvesResistance | Boolean | true | Annexing a city in resistance halves what is left (read by the conquest rules) |
| revoltCounterMin | Int | 10 | Turns a very unhappy empire waits before a city revolts (read by the revolt rules) |
| uprisingRebelsBase | Int | 100 | An uprising spawns (this + (cities + a random number below the number of cities) x the next) / 100 rebel units |
| uprisingRebelsPerCity | Int | 20 | See above |
| goldenAgeThresholdInitial | Int | 1000 | The first golden age's threshold (read by the golden age rules) |
| goldenAgeThresholdBase | Int | 400 | The base of the later ones |
| goldenAgeThresholdPerGoldenAge | Int | 2000 | Added for each golden age had |
| goldenAgeThresholdPerCityPercent | Int | 5 | Percent more per city |
| goldenAgeVisibleDivisor | Int | 5 | The shown threshold is rounded to a multiple of this |
| cityStrengthThresholdForBonuses | Int | 10 | The city strength from which a policy gives happiness per city (read by the military rules) |
| warWearinessPopulationPercentCap | Int | 34 | The most percent of its population war weariness can cost (read by the military rules) |

#### PurchaseConstants

These values are not merged individually, only the entire sub-structure is. The defaults are Brave New World's `GlobalDefines.xml`; Vox Populi 5.4.8 has 4 and 0.88 (`DefineChanges.sql`, `GOLD_PURCHASE_GOLD_PER_PRODUCTION`, `HURRY_GOLD_PRODUCTION_EXPONENT`).

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| goldPerProduction | Float | 30 | The gold price of anything with a production cost is `(production x goldPerProduction) ^ exponent`, times the `hurryModifier` of the game speed, times the `hurryCostModifier` of the building or unit |
| exponent | Float | 0.75 | See above |
| visibleDivisor | Int | 10 | Prices of buildings and units are rounded down to a multiple of this, and the price of a building is at least this |

#### TradeRouteVpConstants

The defines of Vox Populi's trade route model (`tradeRouteModelLikeVp`). These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| internationalTradeBase | Int | 80 | The base of an international route is this times (era + 2), in hundredths of gold |
| cityGoldDivisor | Int | 80 | The gold per turn of each end, times 100, divided by this, is added |
| seaModifier | Int | 25 | Percent more on routes by water |
| landModifier | Int | 0 | Percent more on routes over land |
| differentResourceValue | Int | 5 | Percent for each resource that only one of the two cities has |
| scienceDivisorTimes100 | Int | 125 | Divisor of the science of a route, in hundredths |
| cultureDivisorTimes100 | Int | 125 | The same for culture |
| baseFoodValue | Int | 600 | Hundredths of food of a domestic food route before additions |
| baseProductionValue | Int | 600 | The same for production |
| cityStateAllyGold | Int | 2 | Gold per era from a route to an allied City-State |
| cityStateFriendGold | Int | 1 | The same for a friend |
| allyScienceDelta | Int | 1 | Added to the techs difference of a route to an allied City-State |
| allyCultureDelta | Int | 1 | The same for policies |
| openBordersModifier | Int | 20 | Percent more when both civilizations have open borders, half of it when one has |
| riverModifier | Int | 25 | Percent more on land routes when a city is on a river |
| proximityMax | Int | 50 | The most percent a route between close cities loses |
| targetTurns | Int | 30 | Turns a route lasts before the game speed's `tradeRouteSpeedModifier` |

#### CityStateInfluenceConstants

The defines of `cityStateInfluenceLikeVp`, in hundredths of influence where the original uses them. These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| dropTimes100 | Int | 100 | `MINOR_FRIENDSHIP_DROP_PER_TURN`: the decay of a turn above the resting point |
| dropHostileTimes100 | Int | 150 | `MINOR_FRIENDSHIP_DROP_PER_TURN_HOSTILE`: for a hostile City-State |
| dropAggressorTimes100 | Int | 200 | `MINOR_FRIENDSHIP_DROP_PER_TURN_AGGRESSOR`: against a civilization that has attacked City-States |
| recoveryTimes100 | Int | 100 | `MINOR_FRIENDSHIP_NEGATIVE_INCREASE_PER_TURN`: the recovery of a turn below the resting point |
| sharedReligionRatePercent | Int | 50 | `MINOR_FRIENDSHIP_RATE_MOD_SHARED_RELIGION`: recovery this much faster, decay half of it slower, with the religion of the civilization |
| scalingDecayExponent | Float | 1.5 | `MINOR_INFLUENCE_SCALING_DECAY_EXPONENT`: the decay of 100 or more points over the resting point is this power of the hundreds of points over it |
| scalingDecayStart | Int | 50 | Points over the resting point from which the decay grows by 0.02 for each point (until `scalingDecayFull`) |
| scalingDecayFull | Int | 100 | Points over the resting point from which the exponent applies |
| damagedCapitalDecayPercent | Int | 300 | `MINOR_FRIENDSHIP_DROP_PER_TURN_DAMAGED_CAPITAL_MULTIPLIER`: the decay towards a protector while the capital is damaged |
| anchorProtected | Int | 5 | `MINOR_FRIENDSHIP_ANCHOR_MOD_PROTECTED`: the resting point of a protector |
| anchorWary | Int | -20 | `MINOR_FRIENDSHIP_ANCHOR_MOD_WARY_OF`: the resting point against a civilization the City-State is wary of |
| tradeRouteBonusRoutes | Int | 5 | The most routes to City-States that multiply the trade route bonus of policies |
| unitGift | Int | 15 | `FRIENDSHIP_PER_UNIT_GIFTED`: the influence of a military unit gift, less 1 for each military unit the City-State has (at most half of it) without a proxy war |
| unitGiftProxyWarMultiplier | Int | 2 | What the gift is multiplied by while the giver is at peace with the City-State and it is at war with another major civilization the giver has met |
| barbarianKilled | Int | 15 | `FRIENDSHIP_PER_BARB_KILLED` |
| civilianReturned | Int | 50 | `RETURN_CIVILIAN_FRIENDSHIP` |
| unitIntruding | Int | 5 | `FRIENDSHIP_PER_UNIT_INTRUDING`: taken each turn for each military unit in the borders of a City-State that is not a friend |
| liberationPerEra | Int | 105 | `MINOR_LIBERATION_FRIENDSHIP`: the liberator gets this times its era over the highest influence of the others or its own |
| liberationMinimum | Int | 70 | At least this, enough to be the ally |
| liberationRestingPoint | Int | 100 | `MINOR_LIBERATION_RESTING_INFLUENCE`: what a liberation adds to the resting point of the liberator |
| jerkTurns | Int | 50 | `BALANCE_CS_WAR_COOLDOWN_RATE`: the turns (times the game speed's production cost percentage) after an attack in which the resting point is lowered |
| recentlyLiberatedTurns | Int | 50 | The turns after its liberation in which a tribute from a City-State counts against the score |

#### CityStateBullyConstants

The defines of `cityStateBullyLikeVp`, with the penalties as positive numbers. These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| blockedTurns | Int | 10 | The turns after a tribute in which there is no new one (times the game speed's production cost percentage) |
| cooldownTurns | Int | 30 | The turns in which a tribute counts against the score, `recentlyBulliedPerTurn` for each turn left |
| recentlyBulliedPerTurn | Int | 10 | |
| globalMilitaryShare | Int | 50 | The percent of the share of the military might that counts |
| goldBase | Int | 50 | `MINOR_BULLY_GOLD`: the gold of a tribute at the start |
| goldGrowth | Int | 1000 | `MINOR_BULLY_GOLD_GROWTH_FACTOR`: what the gold grows by over the game |
| goldThreshold | Int | 20 | `MINOR_CIV_GOLD_TRIBUTE_THRESHOLD`: the score gold needs |
| heavyThreshold | Int | 100 | `MINOR_CIV_HEAVY_TRIBUTE_THRESHOLD`: the score a heavy tribute needs |
| dropGoldTimes100 | Int | 3000 | `MINOR_FRIENDSHIP_DROP_BULLY_GOLD_SUCCESS`: the influence a demand for gold costs, in hundredths |
| dropHeavyTimes100 | Int | 6000 | `MINOR_FRIENDSHIP_DROP_BULLY_WORKER_SUCCESS` |
| allyPenalty | Int | 15 | Taken off the score if another civilization is the ally of the City-State |
| protectorPenalty | Int | 15 | For each other civilization that protects it |
| hostilePenalty | Int | 10 | For a hostile City-State |
| militaristicPenalty | Int | 10 | For a Militaristic City-State |
| liberatedPenalty | Int | 100 | Taken off the score for a City-State liberated within `recentlyLiberatedTurns` |
| scoreCap | Int | 100 | The highest score |

#### CityStateQuestConstants

The defines of `cityStateQuestsLikeVp`. These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| eraScalerPercent | Int | 50 | The growth of a reward in percent for each era after the first (the 0-based era number counts, Ancient is 0) |
| protectionBoost | Int | 15 | `BALANCE_INFLUENCE_BOOST_PROTECTION_MINOR`: added to the modifier if the civilization protects the City-State |
| friendlyModifier | Int | 25 | `MINOR_CIV_QUEST_REWARD_FRIENDLY`: added to the modifier for a Friendly City-State |
| hostileModifier | Int | -25 | `MINOR_CIV_QUEST_REWARD_HOSTILE`: added for a Hostile one |
| firstPossibleTurn | Int | 4 | `MINOR_CIV_PERSONAL_QUEST_FIRST_POSSIBLE_TURN` and the global one: times the square root of the speed's percentage over 10 (4 on Standard, 6 on Marathon) |
| firstTurnRandom | Int | 0 | The random turns of the first countdown |
| personalMinTurnsBetween | Int | 10 | The turns between personal quests, then a random 0 to `personalRandTurnsBetween` less one, times the speed |
| personalRandTurnsBetween | Int | 20 | |
| globalMinTurnsBetween | Int | 25 | The same for the global quests |
| globalRandTurnsBetween | Int | 20 | |
| hostileRandMultiplier | Int | 200 | The percent a Hostile City-State multiplies the random turns by |
| typeMultipliers | Map | Vox Populi's | The percent of the reward of a kind (Gold, Science, Culture, Faith, GoldenAgePoints, Food, Production, Tourism, GreatPersonPoints, GreatPersonPointsEverywhere, GeneralPoints, AdmiralPoints, Experience) by City-State type; no entry is 100 |
| personalityMultipliers | Map | Vox Populi's | The same by personality (Neutral, Irrational) |

#### CityStateProtectionConstants

The defines of `cityStateProtectionLikeVp`. These values are not merged individually, only the entire sub-structure is. The defaults are Vox Populi 5.4.8's.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| brokenTurns | Int | 20 | `PLEDGE_BROKEN_MINIMUM_TURNS`: the turns after a broken pledge in which it cannot be made again (times the game speed's production cost percentage) |
| bullyingTurns | Int | 30 | `PLEDGE_BROKEN_MINIMUM_TURNS_BULLYING`: the same after a tribute demanded from the City-State, unless the civilization can bully friendly City-States |
| minimumDuration | Int | 50 | `BALANCE_MINOR_PROTECTION_MINIMUM_DURATION`: the turns a pledge cannot be withdrawn |
| warningTurns | Int | 12 | The turns (times the same percentage) a pledge that no longer qualifies has to qualify again |
| reminderTurns | Int | 4 | A reminder every this many turns of the warning |
| dishonourPerEra | Int | 20 | `MINOR_FRIENDSHIP_DROP_DISHONOR_PLEDGE_TO_PROTECT`: the influence a broken pledge costs for each era of the civilization |
| minimumInfluence | Int | 0 | `FRIENDSHIP_THRESHOLD_CAN_PLEDGE_TO_PROTECT` |
| militaryRequirement | Boolean | true | `BALANCE_MINOR_PROTECTION_REQUIREMENTS`: the median of the military and the ally, trade route or possible route |

#### EspionageConstants

The defines of spy points (`spyPointsLikeVp`) and of how long a killed spy stays dead. These values are not merged individually, only the entire sub-structure is. The threshold is `100 x spyToPlayerRatio / (city-states + majorPlayerMultiplier x majors)` of the players the game started with (at least one of each), kept between `thresholdMin` and `thresholdMax`.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| spyToPlayerRatio | Int | 20 | `BALANCE_SPY_TO_PLAYER_RATIO` |
| majorPlayerMultiplier | Int | 2 | `BALANCE_SPY_POINT_MAJOR_PLAYER_MULTIPLIER` |
| thresholdMin | Int | 100 | `BALANCE_SPY_POINT_THRESHOLD_MIN`: Vox Populi 100 (Community Patch 33) |
| thresholdMax | Int | 100 | `BALANCE_SPY_POINT_THRESHOLD_MAX`: 100. With both at 100 a spy costs 100 points on every map |
| pointUnit | Int | 1 | `ESPIONAGE_SPY_POINT_UNIT`: the points of 1 in the tables of Vox Populi, applied by the ruleset generator |
| respawnTurns | Int | 5 | `BALANCE_SPY_RESPAWN_TIMER`: turns a killed spy stays dead. Brave New World 5, Vox Populi 10. Read without `spyPointsLikeVp` too |
| networkPointsBase | Int | 50 | `ESPIONAGE_NP_BASE`: the Network Points a spy gathers a turn before anything else (`espionageLikeVp`) |
| networkPointsPerSpyRank | Int | 0 | `ESPIONAGE_NP_PER_SPY_RANK`, for each rank above the first |
| networkPointsPerInfluenceLevel | Int | 15 | `ESPIONAGE_NP_CULTURAL_INFLUENCE`: for each level of cultural influence over the city's owner above Exotic |
| networkPointsMaxTechDifference | Int | 10 | `ESPIONAGE_NP_MAX_NUM_TECH`: the technologies the owner is ahead by that count |
| networkPointsPerTechBehind | Int | 0 | `ESPIONAGE_NP_PER_TECHNOLOGY_BEHIND` |
| networkPointsReductionPerSecurity | Int | 100 | `ESPIONAGE_NP_REDUCTION_PER_SECURITY_POINT`: percent of the points a spy gathering intel loses for each point of security, over 100 |
| securityBase | Int | 20 | `ESPIONAGE_SECURITY_BASE`: the security of a city before the difficulty, citizens, trade routes and unhappiness (`espionageLikeVp`) |
| securityMax | Int | 100 | `ESPIONAGE_MAX_NUM_SECURITY_POINTS` |
| securityPerCitizen | Int | -2 | `ESPIONAGE_SECURITY_PER_POPULATION` |
| securityPerTradeRoute | Int | -5 | `ESPIONAGE_SECURITY_PER_TRADE_ROUTE`: for each route the civilization has that starts or ends in the city |
| securityPerExcessUnhappiness | Int | -4 | `ESPIONAGE_SECURITY_PER_EXCESS_UNHAPPINESS`: for each point the city's happiness is below 0 |
| travelBaseTurns | Int | 1 | `iSpyTurnsToTravel`: the turns to travel before the distance |
| travelTurnsByProximity | List of Int | 1, 2, 3, 4 | The turns more for a city of a civilization that is a neighbour, close, far and distant. Halved (at least 1) when the city's owner gives us open borders |
| missionCooldownTurns | Int | 15 | `ESPIONAGE_TURNS_BETWEEN_CITY_STATE_ELECTIONS`: the turns a spy that did a mission waits for its next, times the game speed's modifier |

#### MonopolyConstants

These values are not merged individually, only the entire sub-structure is. Only read for resources that are `isMonopoly`.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| globalThreshold | Int | 50 | Percent of the copies of a luxury on the map that a civilization must own (more than this) for a global monopoly |
| strategicThreshold | Int | 25 | The same for a strategic monopoly of a strategic resource |

#### CityGrowthConstants

These values are not merged individually, only the entire sub-structure is. The food a city of population p needs for its next citizen is `base + (p - 1) * multiplier` (cut to an integer) `+ floor((p - 1) ^ exponent)`, times the game speed and starting era percentages (and the handicap's, for the AI).

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| base | Int | 15 | `BASE_CITY_GROWTH_THRESHOLD` |
| multiplier | Float | 8 | `CITY_GROWTH_MULTIPLIER`: Brave New World 8, Vox Populi 12 |
| exponent | Float | 1.5 | `CITY_GROWTH_EXPONENT`: Brave New World 1.5, Vox Populi 2.22 |

#### BorderCultureCostConstants

These values are not merged individually, only the entire sub-structure is. With `cultureCostLikeCivV` the culture a city needs for its next tile is `first + int((level * laterPlotMultiplier) ^ exponent)`, level being the number of tiles it has claimed by culture, before the city-state, policy, game speed and rounding rules.

| Attribute | Type | Default | Notes |
|-----------|------|---------|-------|
| first | Int | 15 | `CULTURE_COST_FIRST_PLOT`: Brave New World 15, Vox Populi 20 |
| laterPlotMultiplier | Int | 10 | `CULTURE_COST_LATER_PLOT_MULTIPLIER`: Brave New World 10, Vox Populi 12 |
| exponent | Float | 1.1 | `CULTURE_COST_LATER_PLOT_EXPONENT`: Brave New World 1.1, Vox Populi 1.35 |

## AI.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/AI.json)

The data the computer players' strategic AI reads: the AI tables of the original game (Civilization V's XML), generated from it.
It is one object with these attributes; every list entry is identified by its `name` (item flavors by `kind` and `name`).
When an extension mod has an AI.json, its defines and entries replace those of the base with the same name, and new ones are added.
A ruleset without the file gets defaults: the define values of Brave New World, the four grand strategies without flavors,
no economic/military/city strategies, and item flavors derived from each object's stats and type.

| Attribute           | Type   | Notes                                                                                                                 |
|---------------------|--------|-----------------------------------------------------------------------------------------------------------------------|
| defines             | Object | Global defines of the original by name (`AI_GS_RAND_ROLL`, `DEFAULT_FLAVOR_VALUE`, ...)                                |
| postDefines         | Object | Names of objects the original refers to through post-defines                                                          |
| grandStrategies     | List   | `name`, `victoryType` (the victory it pursues), `flavors`, `flavorMods`, `yields`                                       |
| economicStrategies  | List   | `name`, `noMinorCivs`, `checkTriggerTurnCount`, `minimumNumTurnsExecuted`, `firstTurnExecuted`, `weightThreshold`, `techPrereq`, `techObsolete`, `advisor`, `advisorCounsel`, `advisorCounselImportance`, `playerFlavors`, `cityFlavors`, `personalityFlavorThresholdMods` |
| militaryStrategies  | List   | As economic strategies, plus `onlyMinorCivs`, `updateCitySpecializations`                                              |
| cityStrategies      | List   | As economic strategies without `firstTurnExecuted` and player/city flavors, plus `permanent` and `flavors`             |
| citySpecializations | List   | `name`, `yieldType`, `subtype`, `isDefault`, `isWonder`, `mustBeCoastal`, `isOperationUnitProvider`, `flavors`                |
| minorCivFlavors     | List   | `name` (a city-state nation), `flavors`                                                                                |
| itemFlavors         | List   | `kind` (Building, Unit, Technology, Policy, Process, Project, Resource, Improvement), `name`, `flavors`                 |
| unitAiTypes         | List   | `name` (a unit), `defaultUnitAi`, `unitAiTypes`                                                                        |
| handicaps           | List   | `name` (a difficulty), `values` (the AI columns of the original's handicaps, and PolicyNumOptionsConsidered, TechNumOptionsConsidered, CityProductionNumOptionsConsidered) |
| policyBranches      | List   | `name` (a policy branch), `aiDelayNoReligion`, `aiDelayNoCulture`, `aiDelayNoScience`, `aiDelayNoCityStates` (the branch is worthless to the AI in such a game), `firstAdopterFreePolicies`, `secondAdopterFreePolicies` (free tenets the AI expects from an ideology) |

Flavor objects map flavor names to integers. The flavors are, in the original's order: Offense, Defense, CityDefense, MilitaryTraining, Recon,
Ranged, Mobile, Naval, NavalRecon, NavalGrowth, NavalTileImprovement, Air, Expansion, Growth, TileImprovement, Infrastructure, Production,
Gold, Science, Culture, Happiness, GreatPeople, Wonder, Religion, Diplomacy, Spaceship, WaterConnection, Nuke, Antiair, UseNuke, Espionage,
AirCarrier, I_LandTradeRoute, I_SeaTradeRoute, Archaeology, I_TradeOrigin, I_TradeDestination, Airlift.
A strategy is only ever active if the game has a test for it (strategies are referred to by their original names, e.g. `ECONOMICAISTRATEGY_GS_CULTURE`).

## GlobalUniques.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/GlobalUniques.json)

GlobalUniques defines uniques that apply globally. e.g. Vanilla rulesets define the effects of Unhappiness here.

Base ruleset Mods can omit the file, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/GlobalUniques.json).
Alternatively, they can supply a file with an empty object (`{}`), meaning that no global Uniques exist for that Mod.

It has the following structure:

| Attribute   | Type            | Default         | Notes                                                                                       |
|-------------|-----------------|-----------------|---------------------------------------------------------------------------------------------|
| name        | String          | "GlobalUniques" | The name field is not used, but still must be set (the Ruleset validator might display it). |
| uniques     | List of Strings | empty           | List of [unique abilities](../uniques.md) that apply globally                               |
| unitUniques | List of Strings | empty           | List of [unique abilities](../uniques.md) that applies to each unit                         |

When extension rulesets define GlobalUniques, all uniques are merged. At the moment there is no way to change/remove uniques set by a base mod.

## Tutorials.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Tutorials.json)

**Note a Base Ruleset mod can define a "welcome page" here by adding a "Tutorial" with a name equal to the name of the mod!**
As an exception to the general rule, this file in a Base Ruleset mod will not _replace_ the default, but add to it like extension mods do.
Also, place it under `<mod>/jsons/` normally even if the original is found one level above the vanilla jsons.

Each tutorial has the following structure:

| Attribute       | Type            | Default  | Notes                                            |
|-----------------|-----------------|----------|--------------------------------------------------|
| name            | String          | Required | Entry name                                       |
| category        | String          | Optional | The category where this tutorial will be displayed. "Tutorials" is the default. |
| civilopediaText | List            | Optional | See [civilopediaText chapter](#civilopedia-text) |
| steps           | List of Strings | Optional | Plain text                                       |

If an entry contains both `steps` and `civilopediaText` attributes, the `civilopediaText` is shown first.
Tutorials shown as Popup can show an show an external image (not part of the texture atlases) if there is an image unter ExtraImages (directly under assets or the Mod folder) having the same name.
This is searched for, meaning the mod defining the Tutorial is irrelevant, mods can override builtin ExtraImages, and case sensitivity depends on the OS.

## VictoryTypes.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/VictoryTypes.json)

These files contain which victories this mod provides, and what milestones must be reached for someone to win a victory.
Most of the file contains of strings that are shown to the user in the victory screen, with the rest being the requirements for winning.

Base ruleset Mods can omit the file or supply an empty list, even though at least one victory type is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/VictoryTypes.json).

Each victory have the following structure:

| Attribute              | Type            | Default  | Notes                                                                                      |
|------------------------|-----------------|----------|--------------------------------------------------------------------------------------------|
| name                   | String          | Required | Name of the victory                                                                        |
| victoryScreenHeader    | String          | none     | Shown in the footer of the victory in the `our status` in the victory screen               |
| victoryString          | String          | none     | Shown in the footer of the victory screen when you won the game with this victory          |
| defeatString           | String          | none     | Shown in the footer of the victory screen when someone else won the game with this victory |
| hiddenInVictoryScreen  | Boolean         | false    | Whether progress of this victory is hidden in the victory screen                           |
| requiredSpaceshipParts | List of Strings | empty    | What spaceship parts must be added to the capital for the corresponding milestone          |
| Milestones             | List of Strings | Required | List of milestones that must be accomplished to win, [see below](#milestones)              |
| uniques                | List of Strings | empty    | List of [unique abilities](../uniques.md) this victory has, [see below](#victory-uniques)  |
| civilopediaText        | List            | Optional | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)              |

### Victory uniques

A victory can carry uniques, like any other ruleset object.

`Only available <...>` and `Unavailable <...>` decide **who** may achieve it. They are evaluated against a
civilization, so any [civFilter](../Unique-parameters.md#civfilter) or condition on the game works.
A civilization that does not pass them never wins the victory, is not offered it in the victory screen
as one of its own goals, is not listed among the contenders for it, and does not work towards it.
Its milestones themselves are untouched.

```json
[
    {
        "name": "Reach the goal",
        "uniques": ["Only available <for [Human player] Civilizations>"],
        "milestones": ["Build [Monument]"]
    }
]
```

`Will not be chosen for new games` decides whether the victory is **offered** in the new game options,
exactly as it does for a nation. Such a victory gets no checkbox, and the "all victories" default never
enables it - a base ruleset that ships a game or a [scenario](../Scenarios.md) enables it there itself.

This is a separate unique on purpose: while a game is being set up there is no civilization to evaluate
`Only available` / `Unavailable` against, so a victory restricted to a nation, a player type or a game
speed stays selectable unless the mod says otherwise. Conditionals on `Will not be chosen for new games`
are never fulfilled for the same reason - use it plain.

### Milestones

Currently the following milestones are supported:

| Milestone                          | Requirement                                                                                                  |
|------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Build [building]                   | Build the building [building] in any city                                                                    |
| Anyone should build [building]     | Anyone must build the building [building] for all players to have this milestone                             |
| Add all [comment] in capital       | Add all units in the `requiredSpaceshipParts` field of this victory to the capital                           |
| Destroy all players                | You must be the only major civilization with any cities left                                                 |
| Capture all capitals               | Capture all the original capitals of major civilizations in the game                                         |
| Complete [amount] Policy branches  | Fully complete at least [amount] policy branches                                                             |
| Win diplomatic vote                | At any point in the game win a diplomatic vote (UN). You may lose afterwards and still retain this milestone |
| Become the world religion          | Have your religion be the majority religion in a majority of cities of all major civs                        |
| Be [Influential] over all other civilizations| Tourism has made this civilization at least that influential (Exotic, Familiar, Popular, Influential or Dominant) over every other living major civilization, those it has not met included. See the `influencePercent...` constants|
| Follow an ideology with a content populace | Have an ideology and a Content public opinion, checked at the time (Vox Populi's culture victory) |
| Have highest score after max turns | Basically time victory. Enables the 'max turn' slider and calculates score when that amount is reached       |
| Have at least [amount] [countable] | Have at least [amount] of the given `countable`. Unlike the milestone below, this does not depend on the other players, so a ruleset can require several concrete things - a building, a policy, worked tiles - to be achieved in any order. |
| Have more [countable] than each player's [countable] | Have your given `countable` be more than every other Civilization's `countable` to achieve this victory. This is useful to simulate a victory similar to the Cultural Victory in Brave New World. |

## Civilopedia text

Any 'thing' defined in json and listed in the Civilopedia can supply extra text, specifically for the Civilopedia. This can be used to explain special considerations better when the automatically generated display is insufficient, or for 'flavour', background stories and the like. Such text can be formatted and linked to other Civilopedia entries, within limits.

An example of the format is:

```json
"civilopediaText": [
    { "text": "Ancient ruins provide a one-time random bonus when explored" },
    { "separator": true },
    {
        "text": "This line is red and links to the Scout including icons",
        "link": "Unit/Scout",
        "color": "red"
    },
    {
        "text": "A big fat header sporting a golden star",
        "header": 1,
        "starred": true,
        "color": "#ffeb7f"
    },
],
```

List of attributes - note not all combinations are valid:

| Attribute    | Type    | Description                                                                                                                       |
|--------------|---------|-----------------------------------------------------------------------------------------------------------------------------------|
| `text`       | String  | Text to display                                                                                                                   |
| `link`       | String  | Create link and icon, format: Category/Name or _external_ link ('http://','https://','mailto:')                                   |
| `icon`       | String  | Show icon without linking, format: Category/Name                                                                                  |
| `extraImage` | String  | Display an Image instead of text. Can be a path found in a texture atlas or or the name of a png or jpg in the ExtraImages folder |
| `imageSize`  | Float   | Size in world units of the [extraImage], the smaller coordinate is calculated preserving aspect ratio. available width            |
| `header`     | Integer | Header level. 1 means double text size and decreases from there                                                                   |
| `size`       | Integer | Text size, is 18. Use `size` or `header` but not both                                                                             |
| `indent`     | Integer | Indent level. 0 means text will follow icons, 1 aligns to the right of all icons, each further step is 30 units                   |
| `padding`    | Float   | Vertical padding between rows, 5 units                                                                                            |
| `color`      | String  | Sets text color, accepts names or 6/3-digit web colors (e.g. #FFA040)                                                             |
| `separator`  | Boolean | Renders a separator line instead of text. Can be combined only with `color` and `size` (line width, default 2)                    |
| `starred`    | Boolean | Decorates text with a star icon - if set, it receives the `color` instead of the text                                             |
| `centered`   | Boolean | Centers the line (and turns off automatic wrap). For an `extraImage`, turns on crop-to-content to equalize transparent borders    |
| `iconCrossed` | Boolean | Paint a red X over the `icon` or `link` image |

The lines from json will 'surround' the automatically generated lines such that the latter are inserted just above the first json line carrying a link, if any. If no json lines have links, they will be inserted between the automatic title and the automatic info. This method may, however, change in the future.

Note: `text` now also supports inline color markup. Insert `«color»` to start coloring text, `«»` to stop. `color` can be a name or 6/8-digit hex notation like `#ffa040` (different from the `color` attribute notation only by not allowing 3-digit codes, but allowing the alpha channel).
Effectively, the `«»` markers are replaced with `[]` _after_ translation and then passed to [gdx markup language](https://libgdx.com/wiki/graphics/2d/fonts/color-markup-language).

Note: Using an ExtraImages folder in a mod was not working until version 4.11.5

## RGB colors list

Certain objects can be specified to have its own unique color. The colors are defined by a list of 3× Integer in this order: red, green, blue. The range of color is from \[0, 0, 0\] (black) to \[255, 255, 255\] (white).

Note: The default of some objects are [gdx color classes](https://javadoc.io/doc/com.badlogicgames.gdx/gdx/latest/com/badlogic/gdx/graphics/Color.html). The values of the constants are as follows:

| name  | value           |
|-------|-----------------|
| gold  | [225, 215, 0]   |
| white | [255, 255, 255] |
| black | [0, 0, 0]       |

# Uniques
An overview of uniques can be found [here](../Developers/Uniques.md)

Simple unique parameters are explained by mouseover. Complex parameters are explained in [Unique parameter types](Unique-parameters.md)

## Triggerable uniques
!!! note ""

    Uniques that have immediate, one-time effects. These can be added to techs to trigger when researched, to policies to trigger when adopted, to eras to trigger when reached, to buildings to trigger when built. Alternatively, you can add a TriggerCondition to them to make them into Global uniques that activate upon a specific event.They can also be added to units to grant them the ability to trigger this effect as an action, which can be modified with UnitActionModifier and UnitTriggerCondition conditionals.

??? example  "Gain a free [buildingName] [cityFilter]"
	Free buildings CANNOT be self-removing - this leads to an endless loop of trying to add the building

	Example: "Gain a free [Library] [in all cities]"

	Applicable to: Triggerable, Global

??? example  "Remove [buildingFilter] [cityFilter]"
	Example: "Remove [Culture] [in all cities]"

	Applicable to: Triggerable, Global

??? example  "Sell [buildingFilter] buildings [cityFilter]"
	Example: "Sell [Culture] buildings [in all cities]"

	Applicable to: Triggerable, Global

??? example  "Free [unit] appears"
	Example: "Free [Musketman] appears"

	Applicable to: Triggerable

??? example  "[positiveAmount] free [unit] units appear"
	Example: "[3] free [Musketman] units appear"

	Applicable to: Triggerable

??? example  "A [unit] rebels"
	Example: "A [Musketman] rebels"

	Applicable to: Triggerable

??? example  "[positiveAmount] [unit]s rebel"
	Example: "[3] [Musketman]s rebel"

	Applicable to: Triggerable

??? example  "Free Social Policy"
	Applicable to: Triggerable

??? example  "[positiveAmount] Free Social Policies"
	Example: "[3] Free Social Policies"

	Applicable to: Triggerable

??? example  "[positiveAmount] Free Tenets"
	Tenets are the policies of an ideology. A free tenet can be spent on those only, and does not make the next policy dearer

	Example: "[3] Free Tenets"

	Applicable to: Triggerable

??? example  "Empire enters golden age"
	Applicable to: Triggerable

??? example  "Empire enters a [positiveAmount]-turn Golden Age"
	Example: "Empire enters a [3]-turn Golden Age"

	Applicable to: Triggerable

??? example  "Free Great Person"
	Applicable to: Triggerable

??? example  "Archaeological sites appear around the world"
	Only once in a game: the first time this is triggered. 5 to 8 sites for each major civilization (see `archaeologySitesPerCivMin` and following in ModConstants) become resources of the ruleset's archaeological site resources, on tiles with a history of battle, ruins, razed cities or camps. Put it on the technology that reveals them

	Applicable to: Triggerable

??? example  "Excavate what was found here"
	For the improvement that an archaeologist builds on a site: when it is complete, its civilization chooses between an artifact or writing for a Great Work slot, culture, or a Landmark, and the archaeologist is used up

	Applicable to: Triggerable

??? example  "[positiveAmount] Tourism towards all civilizations we have met"
	Added to the influence of this civilization on each of them, not changed by modifiers

	Example: "[3] Tourism towards all civilizations we have met"

	Applicable to: Triggerable

??? example  "Gain the [greatWork] Great Work"
	The work is placed in a free slot of the city that triggers it, or else of the nearest city that has one. Nothing happens if there is none

	Example: "Gain the [Mona Lisa] Great Work"

	Applicable to: Triggerable

??? example  "[amount] population [cityFilter]"
	Example: "[3] population [in all cities]"

	Applicable to: Triggerable

??? example  "[amount] population in a random city"
	Example: "[3] population in a random city"

	Applicable to: Triggerable

??? example  "Discover [tech]"
	Example: "Discover [Agriculture]"

	Applicable to: Triggerable

??? example  "Adopt [policy/belief]"
	Example: "Adopt [Oligarchy]"

	Applicable to: Triggerable

??? example  "Remove [policyFilter]"
	Example: "Remove [Oligarchy]"

	Applicable to: Triggerable

??? example  "Remove [policyFilter] and refund [amount]% of its cost"
	Example: "Remove [Oligarchy] and refund [3]% of its cost"

	Applicable to: Triggerable

??? example  "Free Technology"
	Applicable to: Triggerable

??? example  "[positiveAmount] Free Technologies"
	Example: "[3] Free Technologies"

	Applicable to: Triggerable

??? example  "[positiveAmount] free random researchable Tech(s) from the [eraFilter]"
	Example: "[3] free random researchable Tech(s) from the [Ancient era]"

	Applicable to: Triggerable

??? example  "Reveals the entire map"
	Applicable to: Triggerable

??? example  "Gain a free [beliefType] belief"
	Example: "Gain a free [Follower] belief"

	Applicable to: Triggerable

??? example  "Triggers voting for the Diplomatic Victory"
	Applicable to: Triggerable

??? example  "Instantly consumes [positiveAmount] [stockpiledResource]"
	Example: "Instantly consumes [3] [Mana]"

	Applicable to: Triggerable

??? example  "Instantly provides [positiveAmount] [stockpiledResource]"
	Example: "Instantly provides [3] [Mana]"

	Applicable to: Triggerable

??? example  "Set [stockpile] to [countable]"
	Example: "Set [Mana] to [1000]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Triggerable

??? example  "Instantly gain [amount] [stockpile]"
	Example: "Instantly gain [3] [Mana]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Triggerable

??? example  "Gain [amount] [stat]"
	Gold, Science, Culture and Faith go to the civilization (Culture also fills the border culture of the city of the trigger, if there is one). Food and Production go to a city: the one of the trigger, else the capital (Food is added to its stored food, which does not fall under 0; Production to its current construction while that is unfinished, else to its overflow, and never lowers either under 0), and without a city the unique does nothing. Happiness can not be gained this way. With `(modified by game speed)` the amount is multiplied by the speed's `statCostModifiers` entry, or, with the ModConstants `instantYieldsUseInstantYieldPercent`, by the speed's `instantYieldModifier`, and cut off at the whole number (Vox Populi's `CvPlayer::doInstantYield`)

	Example: "Gain [3] [Culture]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Triggerable

??? example  "Gain [relativeAmount]% of the triggering amount as [stat]"
	For triggers that carry an amount: the citizens a city gains (`upon population growth`), the price paid (`upon buying something with [stat]`), the production cost of a completed unit (`upon completing a [baseUnitFilter] unit`), the strength of the defeated unit (`upon defeating a [mapUnitFilter] unit`), the new luxuries (`upon gaining a luxury resource`). The amount is multiplied by the percentage and cut off at the whole number (Vox Populi's `amount x percent / 100`), then handled as `Gain [amount] [stat]` is. Does nothing for a trigger without an amount

	Example: "Gain [+20]% of the triggering amount as [Culture]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Triggerable

??? example  "Gain [amount]-[amount] [civWideStat]"
	Example: "Gain [3]-[3] [Gold]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Triggerable

??? example  "Gain [positiveAmount] Golden Age points"
	Added to the progress towards the next Golden Age (not changed by the game speed, as in the original's league project rewards)

	Example: "Gain [3] Golden Age points"

	Applicable to: Triggerable

??? example  "Permanently gain [stats]"
	The civilization-wide stats (for example `+3 Happiness`) are added for the rest of the game, as a unique of the civilization that is never removed

	Example: "Permanently gain [+1 Gold, +2 Production]"

	Applicable to: Triggerable

??? example  "Gain [positiveAmount] turns of Science"
	The recorded Science from the previous turns goes to current research or overflow, without Great Scientist or research agreement boosts

	Example: "Gain [3] turns of Science"

	Applicable to: Triggerable

??? example  "Gain [amount] Influence with all City-States we have met"
	Example: "Gain [3] Influence with all City-States we have met"

	Applicable to: Triggerable

??? example  "Gain enough Faith for a Pantheon"
	Applicable to: Triggerable

??? example  "Gain enough Faith for [positiveAmount]% of a Great Prophet"
	Example: "Gain enough Faith for [3]% of a Great Prophet"

	Applicable to: Triggerable

??? example  "Research [relativeAmount]% of [tech]"
	Example: "Research [+20]% of [Agriculture]"

	Applicable to: Triggerable

??? example  "Gain control over [tileFilter] tiles in a [nonNegativeAmount]-tile radius"
	Example: "Gain control over [Farm] tiles in a [3]-tile radius"

	Applicable to: Triggerable

??? example  "Gain control over [positiveAmount] tiles [cityFilter]"
	Example: "Gain control over [3] tiles [in all cities]"

	Applicable to: Triggerable

??? example  "Reveal up to [positiveAmount/'all'] [tileFilter] within a [positiveAmount] tile radius"
	Example: "Reveal up to [3] [Farm] within a [3] tile radius"

	Applicable to: Triggerable

??? example  "Triggers the following global alert: [comment]"
	Supported on Policies and Technologies.

	For other targets, the generated Notification may not read nicely, and will likely not support translation. Reason: Your [comment] gets a generated introduction, other triggers usually notify _you_, not _others_, and that difference is currently handled by mapping text.

	Conditionals evaluate in the context of the civilization having the Unique, not the recipients of the alerts.

	Example: "Triggers the following global alert: [comment]"

	Applicable to: Triggerable

??? example  "Promotes all spies [positiveAmount] time(s)"
	Example: "Promotes all spies [3] time(s)"

	Applicable to: Triggerable

??? example  "Gain an extra spy"
	One spy; with the ModConstants `spyPointsLikeVp` it is one threshold of spy points (a spy, and the points kept below the threshold stay)

	Applicable to: Triggerable

??? example  "Gain [amount] Spy Points"
	Only with the ModConstants `spyPointsLikeVp`. A spy is made for each time the points reach the threshold (100 in Vox Populi); a negative amount is a debt that the next points pay first. The original's `Buildings.ExtraSpies`, `Policies.FreeSpy` and `Units.ExtraSpies` (CvPlayer::CreateSpies)

	Example: "Gain [3] Spy Points"

	Applicable to: Triggerable

??? example  "Turn this tile into a [terrainName] tile"
	Example: "Turn this tile into a [Forest] tile"

	Applicable to: Triggerable

??? example  "Add [resource] to this tile"
	Example: "Add [Iron] to this tile"

	Applicable to: Triggerable

??? example  "Remove [resourceFilter] resources from this tile"
	Example: "Remove [Strategic] resources from this tile"

	Applicable to: Triggerable

??? example  "Remove [improvementFilter] improvements from this tile"
	Example: "Remove [All Road] improvements from this tile"

	Applicable to: Triggerable

??? example  "[mapUnitFilter] units gain the [promotion] promotion"
	Works only with promotions that are valid for the unit's type - or for promotions that do not specify any.

	Example: "[Wounded] units gain the [Shock I] promotion"

	Applicable to: Triggerable

??? example  "Provides the cheapest [stat] building in your first [positiveAmount] cities for free"
	Example: "Provides the cheapest [Culture] building in your first [3] cities for free"

	Applicable to: Triggerable

??? example  "Provides a [buildingName] in your first [positiveAmount] cities for free"
	Example: "Provides a [Library] in your first [3] cities for free"

	Applicable to: Triggerable

??? example  "Triggers a [event] event"
	Example: "Triggers a [Inspiration] event"

	Applicable to: Triggerable

??? example  "Mark tutorial [comment] complete"
	Example: "Mark tutorial [comment] complete"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable

??? example  "Play [comment] sound"
	See [Images and Audio](Images-and-Audio.md#sounds) for a list of available sounds.

	Example: "Play [comment] sound"

	This unique is automatically hidden from users.

	Applicable to: Triggerable

??? example  "Get the leader title of [leaderTitle]"
	Example: "Get the leader title of [Sovereign [leaderName] the Great]"

	This unique is automatically hidden from users.

	Applicable to: Triggerable

??? example  "Choose a music track for [param], [param], [param]"
	Parameters are unchecked, strings not matching existing tracks or flags are ignored.

	See [Context-sensitive music](Images-and-Audio.md#context-sensitive-music-overview)

	The first parameter is the track name prefix, e.g. a Civilization name or "this civ".

	The second parameter is a list of zero or more suffixes, comma-separated, used to specify a "mood", like Peace, War, Ambient etc. First track that matches wins.

	The third parameter is a list of zero or more flags: PrefixMustMatch, SuffixMustMatch, SlowFade, PlaySingle, PlayDefaultFile.

	Example: "Choose a music track for [Unknown], [Unknown], [Unknown]"

	This unique is automatically hidden from users.

	Applicable to: Triggerable

??? example  "Suppress warning [validationWarning]"
	Allows suppressing specific validation warnings. Errors, deprecation warnings, or warnings about untyped and non-filtering uniques should be heeded, not suppressed, and are therefore not accepted. Note that this can be used in ModOptions, in the uniques a warning is about, or as modifier on the unique triggering a warning - but you still need to be specific. Even in the modifier case you will need to specify a sufficiently selective portion of the warning text as parameter.

	Example: "Suppress warning [Tinman is supposed to automatically upgrade at tech Clockwork, and therefore Servos for its upgrade Mecha may not yet be researched! -or- *is supposed to automatically upgrade*]"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable, Terrain, Speed, ModOptions, MetaModifier

## UnitTriggerable uniques
!!! note ""

    Uniques that have immediate, one-time effects on a unit.They can be added to units (on unit, unit type, or promotion) to grant them the ability to trigger this effect as an action, which can be modified with UnitActionModifier and UnitTriggerCondition conditionals.

??? example  "Perform a concert tour: [positiveAmount] times our Tourism towards the civilization whose territory we are in, [positiveAmount]% of that towards each other civilization we have met"
	The unit has to be in the territory of another living major civilization. The first amount is made at least 100, and is multiplied twice by the game speed, as in the original

	Example: "Perform a concert tour: [3] times our Tourism towards the civilization whose territory we are in, [3]% of that towards each other civilization we have met"

	Applicable to: UnitTriggerable

??? example  "Create a Great Work"
	The unit creates the Great Work that goes with its name (see `creator` in GreatWorks.json), in a free slot of a city of the civilization. The unit must be in or next to one of the civilization's cities. Can only be used by units that have a name from a unit name group

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] heals [positiveAmount] HP"
	Example: "[This Unit] heals [3] HP"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] takes [positiveAmount] damage"
	Example: "[This Unit] takes [3] damage"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] gains [amount] XP"
	Example: "[This Unit] gains [3] XP"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] upgrades for free"
	Example: "[This Unit] upgrades for free"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] upgrades for free including special upgrades"
	Example: "[This Unit] upgrades for free including special upgrades"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] gains the [promotion] promotion"
	Example: "[This Unit] gains the [Shock I] promotion"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] loses the [promotion] promotion"
	Example: "[This Unit] loses the [Shock I] promotion"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] gains [positiveAmount] movement"
	Example: "[This Unit] gains [3] movement"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] loses [positiveAmount] movement"
	Example: "[This Unit] loses [3] movement"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] gains the [promotion] status for [positiveAmount] turn(s)"
	Statuses are temporary promotions. They do not stack, and reapplying a specific status take the highest number - so reapplying a 3-turn on a 1-turn makes it 3, but doing the opposite will have no effect. Turns left on the status decrease at the *start of turn*, so bonuses applied for 1 turn are stll applied during other civ's turns.

	Example: "[This Unit] gains the [Shock I] status for [3] turn(s)"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] loses the [promotion] status"
	Example: "[This Unit] loses the [Shock I] status"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] is destroyed"
	Example: "[This Unit] is destroyed"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] takes over the City-State owning the tile it's standing on"
	Only allows taking over City-States not at war with the unit's root nation. Does not require a friendship or an alliance with the target City-State.

	Example: "[This Unit] takes over the City-State owning the tile it's standing on"

	Applicable to: UnitTriggerable

??? example  "[unitTriggerTarget] gets a name from the [unitNameGroup] group"
	Example: "[This Unit] gets a name from the [Scientist] group"

	Applicable to: UnitTriggerable

## Global uniques
!!! note ""

    Uniques that apply globally. Civs gain the abilities of these uniques from nation uniques, reached eras, researched techs, adopted policies, built buildings, religion 'founder' uniques, owned resources, and ruleset-wide global uniques.

??? example  "[stats]"
	Example: "[+1 Gold, +2 Production]"

	Applicable to: Global, Terrain, Improvement

??? example  "[stats] [cityFilter]"
	Example: "[+1 Gold, +2 Production] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every specialist [cityFilter]"
	Example: "[+1 Gold, +2 Production] from every specialist [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every [specialist] [cityFilter]"
	Adds the stats only for the named specialist in matching cities

	Example: "[+1 Gold, +2 Production] from every [Merchant] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] population [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] population [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] population proportionally [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] population proportionally [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] citizens in the empire proportionally [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] citizens in the empire proportionally [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] for each major religion with followers in the city [cityFilter]"
	Counts the religions of which at least one citizen of the city is a follower, pantheons not included. Whole numbers of the stats only, as in the original (CvCity::GetYieldPerReligionTimes100)

	Example: "[+1 Gold, +2 Production] for each major religion with followers in the city [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] social policies adopted"
	Only works for civ-wide stats

	Example: "[+1 Gold, +2 Production] per [3] social policies adopted"

	Applicable to: Global

??? example  "[stats] per every [positiveAmount] [civWideStat]"
	Example: "[+1 Gold, +2 Production] per every [3] [Gold]"

	Applicable to: Global

??? example  "[stats] in cities on [terrainFilter] tiles"
	Example: "[+1 Gold, +2 Production] in cities on [Fresh Water] tiles"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from all [buildingFilter] buildings"
	Example: "[+1 Gold, +2 Production] from all [Culture] buildings"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from [tileFilter] tiles [cityFilter]"
	Example: "[+1 Gold, +2 Production] from [Farm] tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from [tileFilter] tiles without [tileFilter] [cityFilter]"
	Example: "[+1 Gold, +2 Production] from [Farm] tiles without [Farm] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every [tileFilter/specialist/buildingFilter]"
	Example: "[+1 Gold, +2 Production] from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Science from Great Scientist discoveries"
	Applies to the Great Scientist's one-time Science from discovering technology, not to its birth rate

	Example: "[+20]% Science from Great Scientist discoveries"

	Applicable to: Global

??? example  "[stats] from each Trade Route"
	Example: "[+1 Gold, +2 Production] from each Trade Route"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] Tourism [cityFilter]"
	Added to the tourism of the city before the percentages, see ModConstants and the unique `[relativeAmount]% Tourism [cityFilter]`

	Example: "[+20] Tourism [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Tourism [cityFilter]"
	All such bonuses of a city are added up and applied to its tourism, if the sum is positive

	Example: "[+20]% Tourism [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Tourism [cityFilter] unless the target has a [buildingName]"
	As `[relativeAmount]% Tourism [cityFilter]`, but a civilization with the building does not get this part of the tourism

	Example: "[+20]% Tourism [in all cities] unless the target has a [Library]"

	Applicable to: Global

??? example  "[relativeAmount]% Tourism from Great Works [cityFilter]"
	The tourism that the Great Works of the city make, which is `greatWorkBaseTourism` in ModConstants for each, is increased by the sum of these. Theming bonuses are not included

	Example: "[+20]% Tourism from Great Works [in all cities]"

	Applicable to: Global

??? example  "[positiveAmount]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [cityFilter]"
	Counts the culture of the world wonders of the city, and of its worked natural wonder and improved tiles

	Example: "[3]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% Tourism towards civilizations with [tourismRelation]"
	Added to the modifier of the tourism of this civilization towards another, which the original reads from the capital. For `Common Enemies` the amount is given for every other civilization that both are at war with

	Example: "[+20]% Tourism towards civilizations with [Shared Religion]"

	Applicable to: Global

??? example  "[stats] from each Great Work"
	Added to the culture a city gets from each of its Great Works (see `greatWorkBaseCulture` in ModConstants), in every city of the civilization

	Example: "[+1 Gold, +2 Production] from each Great Work"

	Applicable to: Global

??? example  "[stats] from each [greatWorkType] Great Work [cityFilter]"
	The stats once for each Great Work of that type that is in a building of the matching city (the original's `Building_GreatWorkYieldChanges`, `Policy_ArtYieldChanges` and the like, CvCityBuildings::GetYieldFromGreatWorksTimes100). Added to the base stats of the city, before the percentages, like `[stats] from each Great Work`

	Example: "[+1 Gold, +2 Production] from each [Art] Great Work [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Capital's [stats] per [positiveAmount] citizens in the empire"
	In the capital only: the stats for every full second-number citizens that live in all cities of the civilization, puppets included (the original's `Policy_CapitalYieldPerPopChangeEmpire`, CvPlot::calculateYield: the empire's population times the yield per citizen, in whole numbers). Added to the base stats before the percentages

	Example: "Capital's [+1 Gold, +2 Production] per [3] citizens in the empire"

	Applicable to: Global

??? example  "[relativeAmount]% yield from processes [cityFilter]"
	While a matching city makes Gold, Science, Culture, Faith or Food instead of a building or a unit, it converts this many percentage points more of its production than the 25% the conversion starts at (`Building_YieldFromProcessModifier` added to `Process_ProductionYields`, CvCity::GetBaseYieldRateFromProcessTimes100). It does not name the stat: it improves whichever process the city is running

	Example: "[+20]% yield from processes [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% of [stat] [cityFilter] added to [stat]"
	The first stat of a matching city, as the sources add it up before any percentage bonus, times the percentage, is added to the second stat after the percentage bonuses (the original's `Building_YieldFromYieldPercent`, CvCity::UpdateCityYieldFromYield, GetTotalYieldFromYieldTimes100). It is not itself changed or fed back

	Example: "[+20]% of [Culture] [in all cities] added to [Culture]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Theming bonuses [cityFilter]"
	Every such unique multiplies the theming bonuses of the matching cities by 100% plus its amount, one after the other

	Example: "[+20]% Theming bonuses [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% unit gold maintenance"
	Percentage points that are added up over all such uniques and applied to the total gold maintenance of the units of the civilization, after the exponent and before the handicap of the difficulty

	Example: "[+20]% unit gold maintenance"

	Applicable to: Global

??? example  "[relativeAmount]% research of technologies known by any civilization"
	Added up over all such uniques: multiplies the research speed of a technology that any other living civilization already knows by 100% plus the sum (Scholars in Residence)

	Example: "[+20]% research of technologies known by any civilization"

	Applicable to: Global

??? example  "[amount] World Congress Delegates"
	Delegates the civilization gets in every session of the World Congress, in addition to those of the era

	Example: "[3] World Congress Delegates"

	Applicable to: Global

??? example  "1 World Congress Delegate for every [positiveAmount] cities following our religion"
	Vox Populi's `Buildings.FaithToVotes` (V:CvPlayer.cpp:23636-23665, 24527-24599). The cities that follow the civilization's religion are counted in the whole world. Each such unique gives the count times 100 divided by the amount, in whole hundredths; the sum over all of them is floored once, so two uniques of 10 and 15 cities give 3 delegates, not 2. Only with the ModConstants `leagueDelegatesLikeVp`; cached at the start of the civilization's own turn. A civilization without a religion gets nothing

	Example: "1 World Congress Delegate for every [3] cities following our religion"

	Applicable to: Global

??? example  "[positiveAmount] World Congress Delegates for every 2 Declarations of Friendship we have"
	Vox Populi's `Buildings.DoFToVotes` (V:CvPlayer.cpp:23696-23725): half the number of declarations of friendship, rounded down, times the amount. Only with the ModConstants `leagueDelegatesLikeVp`; cached at the start of the civilization's own turn, so a new friendship counts from the next

	Example: "[3] World Congress Delegates for every 2 Declarations of Friendship we have"

	Applicable to: Global

??? example  "[positiveAmount] World Congress Delegates for every original capital of another civilization that we hold"
	Vox Populi's `Buildings.CapitalsToVotes` (V:CvPlayer.cpp:23667-23694): the cities held that were founded as the capital of another major civilization. Only with the ModConstants `leagueDelegatesLikeVp`; cached at the start of the civilization's own turn

	Example: "[3] World Congress Delegates for every original capital of another civilization that we hold"

	Applicable to: Global

??? example  "[positiveAmount] World Congress Delegates for every Defensive Pact we have"
	Vox Populi's `Buildings.DPToVotes` (V:CvPlayer.cpp:23756-23788; the vassals it adds are not built). Only with the ModConstants `leagueDelegatesLikeVp`; cached at the start of the civilization's own turn

	Example: "[3] World Congress Delegates for every Defensive Pact we have"

	Applicable to: Global

??? example  "1 World Congress Delegate for every [positiveAmount] Gold per turn"
	Vox Populi's `Buildings.VotesPerGPT` (V:CvVotingClasses.cpp:4230-4247, CvPlayer.cpp:16050-16052). The amounts of all such uniques are ADDED, then the gross gold per turn is divided by the sum once, and the result is at most a quarter of the City-States originally in the game, but at least 1. Only with the ModConstants `leagueDelegatesLikeVp`; read live. The gross gold is the gold of the cities without the maintenance of their buildings, plus trade and the uniques, without the upkeep of units and roads

	Example: "1 World Congress Delegate for every [3] Gold per turn"

	Applicable to: Global

??? example  "Allows the World Congress to be founded"
	As long as some civilization has a technology or wonder with this unique, the World Congress meets (see Resolutions.json and the eras for its sessions)

	Applicable to: Global

??? example  "[relativeAmount]% [stat]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] from every [tileFilter/buildingFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture] from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Yield from every [tileFilter/buildingFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Yield from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] from City-States"
	Example: "[+20]% [Culture] from City-States"

	Applicable to: Global

??? example  "[relativeAmount]% [stat] from Trade Routes"
	Example: "[+20]% [Culture] from Trade Routes"

	Applicable to: Global

??? example  "Nullifies [stat] [cityFilter]"
	Example: "Nullifies [Culture] [in all cities]"

	Applicable to: Global

??? example  "Nullifies Growth [cityFilter]"
	Example: "Nullifies Growth [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% Production when constructing [buildingFilter] buildings [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Culture] buildings [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production when constructing [baseUnitFilter] units [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Melee] units [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production when constructing [buildingFilter] wonders [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Culture] wonders [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production towards any buildings that already exist in the Capital"
	Example: "[+20]% Production towards any buildings that already exist in the Capital"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Yield from pillaging tiles"
	Example: "[+20]% Yield from pillaging tiles"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Health from pillaging tiles"
	Example: "[+20]% Health from pillaging tiles"

	Applicable to: Global, Unit

??? example  "Military Units gifted from City-States start with [positiveAmount] XP"
	Example: "Military Units gifted from City-States start with [3] XP"

	Applicable to: Global

??? example  "Militaristic City-States grant units [positiveAmount] times as fast when you are at war with a common nation"
	Example: "Militaristic City-States grant units [3] times as fast when you are at war with a common nation"

	Applicable to: Global

??? example  "Gifts of Gold to City-States generate [relativeAmount]% more Influence"
	Example: "Gifts of Gold to City-States generate [+20]% more Influence"

	Applicable to: Global

??? example  "Can spend Gold to annex or puppet a City-State that has been your Ally for [nonNegativeAmount] turns"
	Example: "Can spend Gold to annex or puppet a City-State that has been your Ally for [3] turns"

	Applicable to: Global

??? example  "City-State territory always counts as friendly territory"
	Applicable to: Global

??? example  "Allied City-States will occasionally gift Great People"
	Applicable to: Global

??? example  "[relativeAmount]% City-State Influence degradation"
	Example: "[+20]% City-State Influence degradation"

	Applicable to: Global

??? example  "Wonders need 1 fewer adopted Policy for every [positiveAmount] allied City-States"
	Lowers the number of adopted policies that `Requires [positiveAmount] adopted Policies` asks for by the allied City-States over this number (the original's `Policies.XCSAlliesLowersPolicyNeedWonders`, CvPlayer::canConstruct, V:CvPlayer.cpp:14585-14595). The amounts of several uniques are added, as the original adds the policies' columns

	Example: "Wonders need 1 fewer adopted Policy for every [3] allied City-States"

	Applicable to: Global

??? example  "City-State quest rewards are increased by [relativeAmount]%"
	Adds this percentage to the modifier of the rewards of City-State quests (the original's `Policies.IncreasedQuestRewards`, CvMinorCivAI::GetQuestRewardModifier, V:CvMinorCivAI.cpp:4949-4975). Only with `cityStateQuestsLikeVp`

	Example: "City-State quest rewards are increased by [+20]%"

	Applicable to: Global

??? example  "[amount] Influence per turn with City-States for each of their military units we gifted"
	Only with the ModConstants `cityStateInfluenceLikeVp`: the original's `Traits.MinorInfluencePerGiftedUnit` (Germany, CvMinorCivAI::GetFriendshipChangePerTurnTimes100, V:CvMinorCivAI.cpp:10401, 10426-10437), added to the change of each turn for each living military unit of the City-State that we gave it

	Example: "[3] Influence per turn with City-States for each of their military units we gifted"

	Applicable to: Global

??? example  "[amount] additional Influence with military unit gifts to City-States"
	Only with the ModConstants `cityStateInfluenceLikeVp`: the original's `Policies.MilitaryUnitGiftExtraInfluence` (V:CvMinorCivAI.cpp:15520-15524), added to the influence of a gift of a military unit. `Gain [amount] Influence with a [baseUnitFilter] gift to a City-State` is Brave New World's total instead

	Example: "[3] additional Influence with military unit gifts to City-States"

	Applicable to: Global

??? example  "Resting point for Influence with City-States we are the Ally of is increased by [amount] while we are at war"
	Only with the ModConstants `cityStateInfluenceLikeVp`: the original's `Policies.MinimumAllyInfluenceIncreaseAtWar` (CvMinorCivAI::GetFriendshipAnchorWithMajor, V:CvMinorCivAI.cpp:10701-10704), added to the resting point of the ally of a city-state while the civilization is at war with anyone

	Example: "Resting point for Influence with City-States we are the Ally of is increased by [3] while we are at war"

	Applicable to: Global

??? example  "Resting point for Influence with City-States is increased by [amount]"
	Example: "Resting point for Influence with City-States is increased by [3]"

	Applicable to: Global

??? example  "[amount] Influence per turn with City-States we have a trade route with"
	Added to the per-turn influence change with each City-State that is connected to us by a trade route in either direction, scaled by the gold gift modifier of the game speed. Unlike the normal recovery, it is not capped at the resting point.

	Example: "[3] Influence per turn with City-States we have a trade route with"

	Applicable to: Global

??? example  "Allied City-States provide [stat] equal to [relativeAmount]% of what they produce for themselves"
	Example: "Allied City-States provide [Culture] equal to [+20]% of what they produce for themselves"

	Applicable to: Global

??? example  "[relativeAmount]% resources gifted by City-States"
	Example: "[+20]% resources gifted by City-States"

	Applicable to: Global

??? example  "[relativeAmount]% Happiness from luxury resources gifted by City-States"
	Example: "[+20]% Happiness from luxury resources gifted by City-States"

	Applicable to: Global

??? example  "City-State Influence recovers at twice the normal rate"
	Applicable to: Global

??? example  "Gain [positiveAmount] Influence per turn with City-States you could demand tribute from"
	Example: "Gain [3] Influence per turn with City-States you could demand tribute from"

	Applicable to: Global

??? example  "Units are [relativeAmount]% more effective at intimidating City-States"
	Example: "Units are [+20]% more effective at intimidating City-States"

	Applicable to: Global

??? example  "[relativeAmount]% military strength when demanding tribute from City-States"
	Only with the ModConstants `cityStateBullyLikeVp`: the original's `Traits.CSBullyMilitaryStrengthModifier` (the Zulu, 50), added to both military parts of the score of a demand for tribute (V:CvMinorCivAI.cpp:14108-14111, 14155-14158)

	Example: "[+20]% military strength when demanding tribute from City-States"

	Applicable to: Global

??? example  "Demanding tribute ignores the penalties of the allies and protectors of City-States"
	Only with the ModConstants `cityStateBullyLikeVp`: the original's `Traits.IgnoreBullyPenalties` (the Zulu, V:CvMinorCivAI.cpp:14306)

	Applicable to: Global

??? example  "Tribute demands cost no Influence and do not break our Pledges to Protect"
	Only with the ModConstants `cityStateBullyLikeVp`: the original's `Policies.CanBullyFriendlyCS` (V:CvMinorCivAI.cpp:15033-15045)

	Applicable to: Global

??? example  "Demanding tribute raises our Influence with that City-State by [positiveAmount]% and lowers the Influence of every other civilization by the same percentage"
	Only with the ModConstants `cityStateBullyLikeVp`: the original's `Policies.BullyGlobalCSInfluenceShift` (V:CvMinorCivAI.cpp:14674-14730). The percentages are of the influence each civilization has; the demand then costs our influence nothing

	Example: "Demanding tribute raises our Influence with that City-State by [3]% and lowers the Influence of every other civilization by the same percentage"

	Applicable to: Global

??? example  "[relativeAmount]% growth [cityFilter]"
	Example: "[+20]% growth [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount]% Food is carried over after population increases [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[3]% Food is carried over after population increases [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Food consumption by [populationFilter] [cityFilter]"
	Example: "[+20]% Food consumption by [Followers of this Religion] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% unhappiness from the number of cities"
	Example: "[+20]% unhappiness from the number of cities"

	Applicable to: Global

??? example  "[relativeAmount]% Unhappiness from [populationFilter] [cityFilter]"
	Example: "[+20]% Unhappiness from [Followers of this Religion] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness from each type of luxury resource"
	Example: "[3] Happiness from each type of luxury resource"

	Applicable to: Global

??? example  "Retain [relativeAmount]% of the happiness from a luxury after the last copy has been traded away"
	Example: "Retain [+20]% of the happiness from a luxury after the last copy has been traded away"

	Applicable to: Global

??? example  "[relativeAmount]% of excess happiness converted to [stat]"
	Example: "[+20]% of excess happiness converted to [Culture]"

	Applicable to: Global

??? example  "Reduces [need] by [positiveAmount] [cityFilter]"
	Takes that many unhappy citizens off the need in the matching cities, which can not fall below 0 (the original's `DistressFlatReduction`, `PovertyFlatReduction`, `IlliteracyFlatReduction`, `BoredomFlatReduction` and `ReligiousUnrestFlatReduction`, with their `Global` variants for `in all cities`). Several add up. Distress is the unhappiness of a city's food and production, Poverty of its gold, Illiteracy of its science, Boredom of its culture, Religious Unrest of its religious minorities. Only with the ModConstants `cityNeedsLikeVp`

	Example: "Reduces [Distress] by [3] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Reduces the empire size need modifier by [positiveAmount] [cityFilter]"
	Takes percent points off the rise of every need of the matching cities that the cities and the citizens of the empire cause. It does not go below 0 or above that rise (the original's `EmpireSizeModifierReduction` and `EmpireSizeModifierReductionGlobal`, which hold negative numbers). Several add up. Only with the ModConstants `cityNeedsLikeVp`

	Example: "Reduces the empire size need modifier by [3] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "The first [positiveAmount] specialists cause no unhappiness [cityFilter]"
	In the matching cities that many specialists cost no unhappiness (the original's `NoUnhappfromXSpecialists`, `...Global` and `...Capital`). Several add up. Only with the ModConstants `cityNeedsLikeVp`

	Example: "The first [3] specialists cause no unhappiness [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness from the first [positiveAmount] specialists [cityFilter]"
	Local happiness of the matching cities: the first number for each of their first specialists, as many as the second number (the original's `HappfromXSpecialists`, one happiness per specialist). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness from the first [3] specialists [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per [positiveAmount] citizens [cityFilter]"
	Local happiness of the matching cities: amount x citizens / the second number, rounded towards 0 (the original's `HappinessPerXPopulation` and `HappinessPerXPopulationGlobal`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per [3] citizens [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per [positiveAmount] Great Works [cityFilter]"
	Local happiness of the matching cities: amount for every second-number Great Works in the city (the original's `HappinessPerXGreatWorks`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per [3] Great Works [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per active trade route [cityFilter]"
	Local happiness of the matching cities: amount for each trade route that starts in the city (the original's `HappinessPerActiveTradeRoute`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per active trade route [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% golden age points from happiness"
	The golden age points a civilization makes each turn from its cities' happiness over unhappiness (their positive deltas) grow by this percentage (the original's `GAPFromHappinessModifier`). Several add up. Only with the ModConstants `cityNeedsLikeVp`

	Example: "[+20]% golden age points from happiness"

	Applicable to: Global

??? example  "Cannot build [baseUnitFilter] units"
	Example: "Cannot build [Melee] units"

	Applicable to: Global

??? example  "Enables construction of Spaceship parts"
	Applicable to: Global

??? example  "May buy [baseUnitFilter] units for [nonNegativeAmount] [stat] [cityFilter] at an increasing price ([amount])"
	Example: "May buy [Melee] units for [3] [Culture] [in all cities] at an increasing price ([3])"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings for [nonNegativeAmount] [stat] [cityFilter] at an increasing price ([amount])"
	Example: "May buy [Culture] buildings for [3] [Culture] [in all cities] at an increasing price ([3])"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units for [nonNegativeAmount] [stat] [cityFilter]"
	Example: "May buy [Melee] units for [3] [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings for [nonNegativeAmount] [stat] [cityFilter]"
	Example: "May buy [Culture] buildings for [3] [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units with [stat] [cityFilter]"
	Example: "May buy [Melee] units with [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings with [stat] [cityFilter]"
	Example: "May buy [Culture] buildings with [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units with [stat] for [nonNegativeAmount] times their normal Production cost"
	Example: "May buy [Melee] units with [Culture] for [3] times their normal Production cost"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings with [stat] for [nonNegativeAmount] times their normal Production cost"
	Example: "May buy [Culture] buildings with [Culture] for [3] times their normal Production cost"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing items in cities [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing items in cities [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing [buildingFilter] buildings [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing [Culture] buildings [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing [baseUnitFilter] units [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing [Melee] units [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "Enables conversion of city production to [stat]"
	Example: "Enables conversion of city production to [Culture]"

	Applicable to: Global

??? example  "Production to [stat] conversion in cities changed by [relativeAmount]%"
	Example: "Production to [Culture] conversion in cities changed by [+20]%"

	Applicable to: Global

??? example  "Improves movement speed on roads"
	Applicable to: Global

??? example  "Roads connect tiles across rivers"
	Applicable to: Global

??? example  "[relativeAmount]% maintenance on road & railroads"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% maintenance on road & railroads"

	Applicable to: Global

??? example  "No Maintenance costs for improvements in [tileFilter] tiles"
	Example: "No Maintenance costs for improvements in [Farm] tiles"

	Applicable to: Global

??? example  "[relativeAmount]% construction time for [improvementFilter] improvements"
	Example: "[+20]% construction time for [All Road] improvements"

	Applicable to: Global, Unit

??? example  "Can build [improvementFilter] improvements at a [relativeAmount]% rate"
	Example: "Can build [All Road] improvements at a [+20]% rate"

	Applicable to: Global, Unit

??? example  "[positiveAmount] unique Luxury resources appear under the first [amount] cities founded in different foreign landmasses"
	Example: "[3] unique Luxury resources appear under the first [3] cities founded in different foreign landmasses"

	Applicable to: Global

??? example  "Gain a free [buildingName] [cityFilter]"
	Free buildings CANNOT be self-removing - this leads to an endless loop of trying to add the building

	Example: "Gain a free [Library] [in all cities]"

	Applicable to: Triggerable, Global

??? example  "[relativeAmount]% maintenance cost for [buildingFilter] buildings [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% maintenance cost for [Culture] buildings [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Remove [buildingFilter] [cityFilter]"
	Example: "Remove [Culture] [in all cities]"

	Applicable to: Triggerable, Global

??? example  "Sell [buildingFilter] buildings [cityFilter]"
	Example: "Sell [Culture] buildings [in all cities]"

	Applicable to: Triggerable, Global

??? example  "[relativeAmount] Golden Age points per turn [cityFilter]"
	Added to the golden age meter at the end of each turn for every matching city (the original's YIELD_GOLDEN_AGE_POINTS as a city yield, CvPlayer::GetGoldenAgePointsFromCitiesTimes100). With `<for every [countable]>` it covers the per-specialist, per-building and per-improvement rows

	Example: "[+20] Golden Age points per turn [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] border culture [cityFilter]"
	Example: "[+20] border culture [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] border culture from [tileFilter] tiles [cityFilter]"
	Example: "[+20] border culture from [Farm] tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% border culture [cityFilter]"
	Example: "[+20]% border culture [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Culture cost of natural border growth [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Culture cost of natural border growth [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Gold cost of acquiring tiles [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Gold cost of acquiring tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[nonNegativeAmount] additional tiles claimed when founding a city"
	Example: "[3] additional tiles claimed when founding a city"

	Applicable to: Global

??? example  "Each city founded increases culture cost of policies [relativeAmount]% less than normal"
	Example: "Each city founded increases culture cost of policies [+20]% less than normal"

	Applicable to: Global

??? example  "[relativeAmount]% Culture cost of adopting new Policies"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Culture cost of adopting new Policies"

	Applicable to: Global

??? example  "Each city founded increases Science cost of Technologies [relativeAmount]% less than normal"
	Example: "Each city founded increases Science cost of Technologies [+20]% less than normal"

	Applicable to: Global

??? example  "[relativeAmount]% Science cost of researching new Technologies"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Science cost of researching new Technologies"

	Applicable to: Global

??? example  "Gain the cheapest researchable Technology known by the city's owner when conquering a city for the first time"
	Applicable to: Global

??? example  "[stats] for every known Natural Wonder"
	Example: "[+1 Gold, +2 Production] for every known Natural Wonder"

	Applicable to: Global

??? example  "[stats] for discovering a Natural Wonder (bonus enhanced to [stats] if first to discover it)"
	Example: "[+1 Gold, +2 Production] for discovering a Natural Wonder (bonus enhanced to [+1 Gold, +2 Production] if first to discover it)"

	Applicable to: Global

??? example  "[relativeAmount] Trade Route slots"
	Example: "[+20] Trade Route slots"

	Applicable to: Global

??? example  "[relativeAmount]% Trade Route slots"
	The sum of all [TradeRouteSlots] is multiplied by 100% plus all of these, and rounded down

	Example: "[+20]% Trade Route slots"

	Applicable to: Global

??? example  "[relativeAmount] range for [tradeRouteFilter] trade routes"
	Added to the base range of the trade unit, before [TradeRouteRangePercent]. Only the filters `Land` and `Water` are meaningful

	Example: "[+20] range for [Land] trade routes"

	Applicable to: Global

??? example  "[relativeAmount]% range for [tradeRouteFilter] trade routes"
	Counts for routes starting in the city this takes effect in (use `<in this city>` for buildings). Only the filters `Land` and `Water` are meaningful

	Example: "[+20]% range for [Land] trade routes"

	Applicable to: Global

??? example  "[relativeAmount] Gold on [tradeRouteFilter] trade routes"
	Added to the gold an international route brings its origin city, before the bonus for sea or rivers is applied. As a building effect use `<in this city>`, which makes it count for routes starting in that city only

	Example: "[+20] Gold on [Land] trade routes"

	Applicable to: Global

??? example  "[relativeAmount] Gold on incoming [tradeRouteFilter] trade routes for their origin"
	Added to the gold the origin of an international route gets when it ends in the city this takes effect in (use `<in this city>` for buildings). Buildings count only for routes between major civilizations

	Example: "[+20] Gold on incoming [Land] trade routes for their origin"

	Applicable to: Global

??? example  "[relativeAmount] Gold from incoming [tradeRouteFilter] trade routes"
	Added to the gold the destination of an international route gets, when it ends in the city this takes effect in (use `<in this city>` for buildings). Buildings count only for routes between major civilizations

	Example: "[+20] Gold from incoming [Land] trade routes"

	Applicable to: Global

??? example  "[relativeAmount]% Gold from resource diversity on trade routes"
	A route gets gold for each luxury or strategic resource that only one of its two cities has. Applies to the city the route starts in

	Example: "[+20]% Gold from resource diversity on trade routes"

	Applicable to: Global

??? example  "[relativeAmount]% yields from trade routes within your civilization"
	Example: "[+20]% yields from trade routes within your civilization"

	Applicable to: Global

??? example  "[stats] for each trade route that starts in this city"
	Added to the city for every trade route of its owner that starts in it and every route that ends in it, counted as the original's `Belief_YieldPerActiveTR` does (V:CvBeliefClasses.cpp:4332-4350 with CvPlayerTrade::GetNumberOfTradeRoutesCity, V:CvTradeClasses.cpp:4723-4730: the routes from the city and the routes to it, of any civilization, so a route between two of the civilization's cities counts for both). Nothing reads it in a ruleset that does not use it

	Example: "[+1 Gold, +2 Production] for each trade route that starts in this city"

	Applicable to: Global, FollowerBelief

??? example  "[stats] on [tradeRouteFilter] trade routes"
	Added to the yield of a trade route that matches the filter, for the stat it names, before the route's modifiers (Vox Populi's `Belief_TradeRouteYieldChange`, `Policy_TradeRouteYieldChange`, `Trait_TradeRouteYieldChange`, V:CvTradeClasses.cpp:3232-3270): the unique of the city the route starts in. Gold, culture and science for an international route, food and production for a domestic one; with the modifier `<for every [Era scaler]>` it grows with the era. Only read with `tradeRouteModelLikeVp`

	Example: "[+1 Gold, +2 Production] on [Land] trade routes"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% yields from international trade routes"
	Added to the modifier of the yields of the civilization's international trade routes, for the origin (Vox Populi's `Policies.TradeRouteYieldModifier`, POLICYMOD_TRADE_MODIFIER, V:CvTradeClasses.cpp:3705-3707). Only read with `tradeRouteModelLikeVp`

	Example: "[+20]% yields from international trade routes"

	Applicable to: Global

??? example  "No proximity penalty on trade routes"
	The routes of the civilization and the routes to it are not made less valuable the shorter they are than the longest route possible (Vox Populi's `Traits.NoTradeRouteProximityPenalty`, Morocco's `TRAIT_GATEWAY_AFRICA`, V:CvTradeClasses.cpp:3372-3418). Only read with `tradeRouteModelLikeVp`

	Applicable to: Global

??? example  "No destination restriction on trade routes"
	With `tradeRouteModelLikeVp` a civilization may send only one international trade route to a city; the routes of the civilization with this unique, and the routes to its cities, are not restricted (Vox Populi's `Traits.NoTradeRouteDestinationRestriction`, Venice, V:CvTradeClasses.cpp:665-685)

	Applicable to: Global

??? example  "[stats] for each luxury resource exported"
	Added to the civilization's yields every turn for each kind of luxury resource that it sells in a deal (`Trait_YieldFromExport`, Netherlands, V:CvPlayer.cpp:18078-18130); Vox Populi multiplies it by the era, so use the modifier `<for every [Era scaler]>`

	Example: "[+1 Gold, +2 Production] for each luxury resource exported"

	Applicable to: Global

??? example  "[stats] for each luxury resource imported"
	Added to the civilization's yields every turn for each kind of luxury resource that it gets from a deal, from allied City-States or from a siphon (`Trait_YieldFromImport`, V:CvPlayer.cpp:18078-18130); use the modifier `<for every [Era scaler]>` for the era

	Example: "[+1 Gold, +2 Production] for each luxury resource imported"

	Applicable to: Global

??? example  "[relativeAmount]% quantity of [resource]"
	Changes the number of copies of the resource that the civilization owns, in the cities that follow the belief (a follower belief) or everywhere (global), as the original's `Belief_ResourceQuantityModifiers` do (V:CvPlayer.cpp:38843-38865; there the modifier is the amount times the number of cities that follow the belief, at most 25, on the whole supply: here each city has its own amount on the copies it gives)

	Example: "[+20]% quantity of [Iron]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] to the flat bonus of each monopoly"
	Added to every flat yield, happiness or tile bonus that a global monopoly of the civilization gives (the original's `MonopolyModFlat`)

	Example: "[+20] to the flat bonus of each monopoly"

	Applicable to: Global

??? example  "[relativeAmount]% to the percentage bonus of each monopoly"
	Added to every percentage that a global monopoly of the civilization gives (the original's `MonopolyModPercent`): to the percentage bonuses to a stat of a city and to the golden age length

	Example: "[+20]% to the percentage bonus of each monopoly"

	Applicable to: Global

??? example  "City-State resources count towards monopolies"
	The copies of a resource that allied City-States give the civilization are owned copies when its monopolies are counted (the original's `CSResourcesCountForMonopolies`)

	Applicable to: Global

??? example  "[stats] [cityFilter] for every global monopoly"
	Added to the matching cities once for each global monopoly the civilization has (the original's `Building_YieldChangesPerMonopoly`, `Policy_CityYieldPerMonopoly`)

	Example: "[+1 Gold, +2 Production] [in all cities] for every global monopoly"

	Applicable to: Global

??? example  "[relativeAmount]% Great Person generation [cityFilter]"
	Example: "[+20]% Great Person generation [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Gold from Great Merchant trade missions"
	Example: "[+20]% Gold from Great Merchant trade missions"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Influence from diplomatic missions"
	On a unit or a promotion: `UnitPromotions.TradeMissionInfluenceModifier`; on a policy or other global source: `Policies.MissionInfluenceModifier` (Foreign Service 10). Each is its own multiplication, rounded down. Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[+20]% Influence from diplomatic missions"

	Applicable to: Global, Unit, Promotion

??? example  "Great General provides double combat bonus"
	Applicable to: Global, Unit

??? example  "Receive a free Great Person at the end of every [comment] (every 394 years), after researching [tech]. Each bonus person can only be chosen once."
	Example: "Receive a free Great Person at the end of every [comment] (every 394 years), after researching [Agriculture]. Each bonus person can only be chosen once."

	Applicable to: Global

??? example  "Once The Long Count activates, the year on the world screen displays as the traditional Mayan Long Count."
	Applicable to: Global

??? example  "[amount] Unit Supply"
	Example: "[3] Unit Supply"

	Applicable to: Global

??? example  "[amount] Unit Supply per [positiveAmount] population [cityFilter]"
	Example: "[3] Unit Supply per [3] population [in all cities]"

	Applicable to: Global

??? example  "[amount] Unit Supply per city"
	Example: "[3] Unit Supply per city"

	Applicable to: Global

??? example  "[amount] units cost no maintenance"
	Example: "[3] units cost no maintenance"

	Applicable to: Global

??? example  "Units in cities cost no Maintenance"
	Applicable to: Global

??? example  "Enables embarkation for land units"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global

??? example  "Enables [mapUnitFilter] units to enter ocean tiles"
	Example: "Enables [Wounded] units to enter ocean tiles"

	Applicable to: Global

??? example  "Land units may cross [terrainName] tiles after the first [baseUnitFilter] is earned"
	Example: "Land units may cross [Forest] tiles after the first [Melee] is earned"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global

??? example  "Enemy [mapUnitFilter] units must spend [positiveAmount] extra movement points when inside your territory"
	Example: "Enemy [Wounded] units must spend [3] extra movement points when inside your territory"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global

??? example  "New [baseUnitFilter] units start with [amount] XP [cityFilter]"
	Example: "New [Melee] units start with [3] XP [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "All newly-trained [baseUnitFilter] units [cityFilter] receive the [promotion] promotion"
	Example: "All newly-trained [Melee] units [in all cities] receive the [Shock I] promotion"

	Applicable to: Global, FollowerBelief

??? example  "[mapUnitFilter] Units adjacent to this city heal [amount] HP per turn when healing"
	Example: "[Wounded] Units adjacent to this city heal [3] HP per turn when healing"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% XP required for promotions"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% XP required for promotions"

	Applicable to: Global

??? example  "[relativeAmount]% City Strength from defensive buildings"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% City Strength from defensive buildings"

	Applicable to: Global

??? example  "[relativeAmount]% City Strength"
	Multiplies the base, population, technology, garrison and building part of the strength of all your cities, before the terrain bonus and the additive combat modifiers are added. Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% City Strength"

	Applicable to: Global

??? example  "[relativeAmount]% Strength for cities"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Strength for cities"

	Applicable to: Global, FollowerBelief

??? example  "Provides [amount] [resource]"
	Example: "Provides [3] [Iron]"

	Applicable to: Global, FollowerBelief, Improvement

??? example  "[relativeAmount]% [resourceFilter] resource production"
	Example: "[+20]% [Strategic] resource production"

	Applicable to: Global

??? example  "Enables establishment of embassies"
	Applicable to: Global

??? example  "Requires establishing embassies to conduct advanced diplomacy"
	Applicable to: Global

??? example  "Enables Open Borders agreements"
	Applicable to: Global

??? example  "Enables Research agreements"
	Applicable to: Global

??? example  "Science gained from research agreements [relativeAmount]%"
	Example: "Science gained from research agreements [+20]%"

	Applicable to: Global

??? example  "Enables Defensive Pacts"
	Applicable to: Global

??? example  "When declaring friendship, both parties gain a [relativeAmount]% boost to great person generation"
	Example: "When declaring friendship, both parties gain a [+20]% boost to great person generation"

	Applicable to: Global

??? example  "Influence of all other civilizations with all city-states degrades [relativeAmount]% faster"
	Example: "Influence of all other civilizations with all city-states degrades [+20]% faster"

	Applicable to: Global

??? example  "Gain [amount] Influence with a [baseUnitFilter] gift to a City-State"
	Example: "Gain [3] Influence with a [Melee] gift to a City-State"

	Applicable to: Global

??? example  "Resting point for Influence with City-States following this religion [amount]"
	Example: "Resting point for Influence with City-States following this religion [3]"

	Applicable to: Global

??? example  "Notified of new Barbarian encampments"
	Applicable to: Global

??? example  "Receive [relativeAmount]% Gold from Barbarian encampments and pillaging Cities"
	Example: "Receive [+20]% Gold from Barbarian encampments and pillaging Cities"

	Applicable to: Global

??? example  "When conquering an encampment, earn [amount] Gold and recruit a Barbarian unit"
	Example: "When conquering an encampment, earn [3] Gold and recruit a Barbarian unit"

	Applicable to: Global

??? example  "When defeating a [mapUnitFilter] unit, earn [amount] Gold and recruit it"
	Example: "When defeating a [Wounded] unit, earn [3] Gold and recruit it"

	Applicable to: Global

??? example  "May choose [amount] additional [beliefType] beliefs when [foundingOrEnhancing] a religion"
	Example: "May choose [3] additional [Follower] beliefs when [founding] a religion"

	Applicable to: Global

??? example  "May choose [amount] additional belief(s) of any type when [foundingOrEnhancing] a religion"
	Example: "May choose [3] additional belief(s) of any type when [founding] a religion"

	Applicable to: Global

??? example  "[stats] when a city adopts this religion for the first time"
	Example: "[+1 Gold, +2 Production] when a city adopts this religion for the first time"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Global

??? example  "[relativeAmount]% Natural religion spread [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Natural religion spread [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Religion naturally spreads to cities [amount] tiles away"
	Example: "Religion naturally spreads to cities [3] tiles away"

	Applicable to: Global, FollowerBelief

??? example  "May not generate great prophet equivalents naturally"
	Applicable to: Global

??? example  "[relativeAmount]% Faith cost of generating Great Prophet equivalents"
	Example: "[+20]% Faith cost of generating Great Prophet equivalents"

	Applicable to: Global

??? example  "Cities with a majority religion also get the Pantheon belief bonus of the second most popular religion"
	The second most popular religion of a city is the one with the most followers among the major religions other than its majority religion (ties go to the one with more pressure). Its Pantheon belief, the first one if it has several, applies to the city as if it were the belief of the majority religion

	Applicable to: Global

??? example  "If you founded a religion, gain a bonus Reformation belief"
	Once, as soon as the civilization has founded a religion that has no Reformation belief yet: a free belief of the type `Reformation` can be chosen, like with `Gain a free [beliefType] belief`

	Applicable to: Global

??? example  "[relativeAmount]% religious pressure along trade routes that start in our cities"
	Cities linked by a trade route, of any civilization and in either direction, push the religion they follow on each other every turn as if they were close enough (once only if they are). The pressure along a route that starts in a city of the civilization with this unique is changed by this percentage. Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% religious pressure along trade routes that start in our cities"

	Applicable to: Global

??? example  "[relativeAmount]% spy effectiveness [cityFilter]"
	Example: "[+20]% spy effectiveness [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% enemy spy effectiveness [cityFilter]"
	Example: "[+20]% enemy spy effectiveness [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% enemy spy stealing risk [cityFilter]"
	Scales the dice roll (random roll plus the defending counter-intelligence spy's skill) that decides how an enemy spy fares when stealing technology in matching cities of this civilization; a higher roll means more detection, and from 200 the spy is killed. `[+100]` doubles the roll. Does not affect election rigging.

	Example: "[+20]% enemy spy stealing risk [in all cities]"

	Applicable to: Global

??? example  "[relativeAmount]% votes when rigging City-State elections"
	Multiplies the votes our spies get in City-State elections. Note that the original reads this modifier from the City-State's own policies, which would never help the spy's owner; here it is read from the spy's owner.

	Example: "[+20]% votes when rigging City-State elections"

	Applicable to: Global

??? example  "[relativeAmount]% influence from rigged City-State elections"
	Only with the ModConstants `espionageLikeVp`. Multiplies what we gain when we win a rigged election and what the others lose to us (Policies.RigElectionInfluenceModifier, CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15213). Not the votes of [RiggingElectionVotes], which is Brave New World's

	Example: "[+20]% influence from rigged City-State elections"

	Applicable to: Global

??? example  "New spies start with [amount] level(s)"
	Example: "New spies start with [3] level(s)"

	Applicable to: Global

??? example  "Spies in [cityFilter] cities act as though they have [relativeAmount] levels for [spyAction]"
	Temporary effective rank change ([relativeAmount] added to rank, e.g. +1) for spies doing the given action in a matching city. Does not permanently level the spy. Stacks additively, capped by maxSpyRank.

	Example: "Spies in [in all cities] cities act as though they have [+20] levels for [Counter-intelligence]"

	Applicable to: Global

??? example  "[relativeAmount] Network Points per turn for our spies in foreign cities"
	Only with the ModConstants `espionageLikeVp`. Added to the base of every spy's Network Points a turn (Policies.EspionageNetworkPoints and the Enhancer belief's `Beliefs.EspionageNetworkPoints`: CvPlayerEspionage::CalcNetworkPointsPerTurn)

	Example: "[+20] Network Points per turn for our spies in foreign cities"

	Applicable to: Global, FounderBelief

??? example  "[relativeAmount]% Science from the passive bonuses of our spies"
	Only with the ModConstants `espionageLikeVp`. Changes the science the spies' passive bonuses (`SpyStealsSciencePercent`) bring in (CvPlayer::GetSciencePerTurnFromPassiveSpyBonusesTimes100, Policies.PassiveEspionageBonusModifier)

	Example: "[+20]% Science from the passive bonuses of our spies"

	Applicable to: Global

??? example  "[relativeAmount]% [stat] [cityFilter] per [positiveAmount] Spy Points ever accumulated, up to [positiveAmount]%"
	Only with the ModConstants `spyPointsLikeVp`. The percent grows by the first amount for every second amount of spy points the civilization has ever gained (not those it spent on spies), in whole steps of that size, up to the last amount (Policy_YieldModifierFromActiveSpies, CvCity::getBaseYieldRateModifier)

	Example: "[+20]% [Culture] [in all cities] per [3] Spy Points ever accumulated, up to [3]%"

	Applicable to: Global

??? example  "[relativeAmount] City Security [cityFilter]"
	Only with the ModConstants `espionageLikeVp`. Added to the security of matching cities, which lowers the Network Points that enemy spies gather in them (CvCity::CalculateCitySecurity, V:CvCity.cpp:25992-26085). Buildings, policies and wonders give it, and a counterspy focus while its spy counters spies in the city

	Example: "[+20] City Security [in all cities]"

	Applicable to: Global, SpyMission

??? example  "City is sapped"
	Only with the ModConstants `espionageLikeVp`, given by a spy mission with `<for [amount] turns>` (CvCity::GetSappedTurns, V:CvCity.cpp:21936, 33031): the city cannot make a ranged strike and counts as blockaded. The strike and the blockade read it in the military code

	Applicable to: Global

??? example  "Triggers victory"
	Applicable to: Global

??? example  "Triggers a Cultural Victory upon completion"
	Applicable to: Global

??? example  "May buy items in puppet cities"
	Applicable to: Global

??? example  "May not annex cities"
	Applicable to: Global

??? example  ""Borrows" city names from other civilizations in the game"
	Applicable to: Global

??? example  "Cities are razed [amount] times as fast"
	Example: "Cities are razed [3] times as fast"

	Applicable to: Global

??? example  "Receive a tech boost when scientific buildings/wonders are built in capital"
	Applicable to: Global

??? example  "[relativeAmount]% Golden Age length"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Golden Age length"

	Applicable to: Global

??? example  "Population loss from nuclear attacks [relativeAmount]% [cityFilter]"
	Example: "Population loss from nuclear attacks [+20]% [in all cities]"

	Applicable to: Global

??? example  "Damage to garrison from nuclear attacks [relativeAmount]% [cityFilter]"
	Example: "Damage to garrison from nuclear attacks [+20]% [in all cities]"

	Applicable to: Global

??? example  "Rebel units may spawn"
	Applicable to: Global

??? example  "Cannot build [buildingFilter] buildings"
	Example: "Cannot build [Culture] buildings"

	Applicable to: Global

??? example  "Units that can speed up construction of a building can also speed up construction of spaceship parts"
	Applicable to: Global

??? example  "[relativeAmount]% Strength"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Strength"

	Applicable to: Global, Unit

??? example  "[relativeAmount] Strength"
	Example: "[+20] Strength"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Strength decreasing with distance from the capital"
	Example: "[+20]% Strength decreasing with distance from the capital"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% to Flank Attack bonuses"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% to Flank Attack bonuses"

	Applicable to: Global, Unit

??? example  "[amount] additional attacks per turn"
	Example: "[3] additional attacks per turn"

	Applicable to: Global, Unit

??? example  "[amount] Movement"
	Example: "[3] Movement"

	Applicable to: Global, Unit

??? example  "[amount] Sight"
	Example: "[3] Sight"

	Applicable to: Global, Unit, Terrain, Improvement

??? example  "[amount] Range"
	Example: "[3] Range"

	Applicable to: Global, Unit

??? example  "[relativeAmount] Air Interception Range"
	Example: "[+20] Air Interception Range"

	Applicable to: Global, Unit

??? example  "[amount] HP when healing"
	Example: "[3] HP when healing"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Spread Religion Strength"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Spread Religion Strength"

	Applicable to: Global, Unit

??? example  "When spreading religion to a city, gain [amount] times the amount of followers of other religions as [stat]"
	Example: "When spreading religion to a city, gain [3] times the amount of followers of other religions as [Culture]"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% City ranged strike strength"
	Example: "[+20]% City ranged strike strength"

	Applicable to: Global

??? example  "[relativeAmount]% City ranged strike strength with a garrison"
	Example: "[+20]% City ranged strike strength with a garrison"

	Applicable to: Global

??? example  "[amount] city strength per stationed air unit"
	Example: "[3] city strength per stationed air unit"

	Applicable to: Global

??? example  "[relativeAmount]% allied city-state defense"
	Example: "[+20]% allied city-state defense"

	Applicable to: Global

??? example  "[relativeAmount]% city strike strength per previous attack on target"
	Example: "[+20]% city strike strength per previous attack on target"

	Applicable to: Global

??? example  "Cities take [amount] less damage from attacks"
	Example: "Cities take [3] less damage from attacks"

	Applicable to: Global

??? example  "[relativeAmount]% damage taken"
	Example: "[+20]% damage taken"

	Applicable to: Global, Unit

??? example  "[amount]% less damage from cities"
	Example: "[3]% less damage from cities"

	Applicable to: Global, Unit

??? example  "Ranged attacks may be performed over obstacles"
	Applicable to: Global, Unit

??? example  "No defensive terrain bonus"
	Applicable to: Global, Unit

??? example  "No defensive terrain penalty"
	Applicable to: Global, Unit

??? example  "[relativeAmount]% Strength for enemy [mapUnitFilter] units within [amount] tiles"
	Example: "[+20]% Strength for enemy [Wounded] units within [3] tiles"

	Applicable to: Global, Unit

??? example  "Stronger when damaged"
	Applicable to: Global, Unit

??? example  "No damage penalty for wounded units"
	Applicable to: Global, Unit

??? example  "[amount]% wounded unit damage penalty"
	Percentage points added to the share of its lost health that a wounded unit loses in strength and damage dealt (33 by default), so `[-25]` leaves 8. The sum is not reduced below 0. Does not apply to cities, and `No damage penalty for wounded units` still removes the penalty entirely.

	Example: "[3]% wounded unit damage penalty"

	Applicable to: Global

??? example  "Unable to capture cities"
	Applicable to: Global, Unit

??? example  "Unable to pillage tiles"
	Applicable to: Global, Unit

??? example  "No movement cost to pillage"
	Applicable to: Global, Unit

??? example  "May heal outside of friendly territory"
	Applicable to: Global, Unit

??? example  "All healing effects doubled"
	Does not stack

	Applicable to: Global, Unit

??? example  "Heals [amount] damage if it kills a unit"
	Example: "Heals [3] damage if it kills a unit"

	Applicable to: Global, Unit

??? example  "Can only heal by pillaging"
	Applicable to: Global, Unit

??? example  "[relativeAmount]% maintenance costs"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% maintenance costs"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Gold cost of upgrading"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Gold cost of upgrading"

	Applicable to: Global, Unit

??? example  "Earn [amount]% of the damage done to [combatantFilter] units as [stockpile]"
	Example: "Earn [3]% of the damage done to [City] units as [Mana]"

	Applicable to: Global, Unit

??? example  "Upon capturing a city, receive [amount] times its [stat] production as [stockpile] immediately"
	Example: "Upon capturing a city, receive [3] times its [Culture] production as [Mana] immediately"

	Applicable to: Global, Unit

??? example  "Earn [amount]% of killed [mapUnitFilter] unit's [costOrStrength] as [stockpile]"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats.

	Example: "Earn [3]% of killed [Wounded] unit's [Cost] as [Mana]"

	Applicable to: Global, Unit

??? example  "[amount] XP gained from combat"
	Example: "[3] XP gained from combat"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% XP gained from combat"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% XP gained from combat"

	Applicable to: Global, Unit

??? example  "[greatPerson] is earned [relativeAmount]% faster"
	Example: "[Great General] is earned [+20]% faster"

	Applicable to: Global, Unit

??? example  "Ignores Zone of Control"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "Cannot enter [tileFilter] tiles"
	Example: "Cannot enter [Farm] tiles"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "[nonNegativeAmount] Movement point cost to disembark"
	Example: "[3] Movement point cost to disembark"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "[nonNegativeAmount] Movement point cost to embark"
	Example: "[3] Movement point cost to embark"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

## Nation uniques
??? example  "Starts with [tech]"
	Example: "Starts with [Agriculture]"

	Applicable to: Nation

??? example  "Starts with [policy] adopted"
	Example: "Starts with [Oligarchy] adopted"

	Applicable to: Nation

??? example  "Chooses its start region before the other civilizations with a Coast start bias"
	This unique is automatically hidden from users.

	Applicable to: Nation

??? example  "Start bias [terrainFilter]"
	Same effect as a Nation startBias field entry. Merged with the startBias field and, for city-states, with matching uniques on their CityStateType. Conditionals run against GameInfo only during map generation / start placement (no Civilization — it may be only partially initialized). Do not use conditionals that require tiles, cities, or units.

	Example: "Start bias [Fresh Water]"

	Applicable to: Nation, CityState

??? example  "All units move through Forest and Jungle Tiles in friendly territory as if they have roads. These tiles can be used to establish City Connections upon researching the Wheel."
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Nation

??? example  "Units ignore terrain costs when moving into any tile with Hills"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Nation

??? example  "Excluded from map editor"
	This unique is automatically hidden from users.

	Applicable to: Nation, Terrain, Improvement, Resource

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Will not be chosen for new games"
	Unconditional - it is evaluated while a game is being set up, where there is nothing to evaluate conditionals against.

	Applicable to: Nation, Victory

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Personality uniques
??? example  "Will not build [baseUnitFilter/buildingFilter]"
	Example: "Will not build [Melee]"

	Applicable to: Personality

??? example  "[relativeAmount]% weight to [baseUnitFilter/buildingFilter] for AI decisions"
	Example: "[+20]% weight to [Melee] for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: Personality

## Era uniques
??? example  "World Congress sessions: every [positiveAmount] turns, [amount] delegates per civilization, [amount] for the host and [amount] per allied City-State"
	Parameters of the sessions of the World Congress while this is the most advanced era that a civilization is in; later eras without this unique use the last one. The interval is given for Standard game speed and is scaled by the speed of the game

	Example: "World Congress sessions: every [3] turns, [3] delegates per civilization, [3] for the host and [3] per allied City-State"

	Applicable to: Era

??? example  "The World Congress becomes the United Nations"
	From this era on, the World Congress is called the United Nations and is the one that elects the World Leader

	Applicable to: Era

??? example  "Starting in this era disables religion"
	Applicable to: Era

??? example  "Every major Civilization gains a spy once a civilization enters this era"
	Applicable to: Era

## Resolution uniques
!!! note ""

    Uniques of a resolution of the World Congress, which apply to every civilization while it is in effect

??? example  "Elects the host of the World Congress"
	Marks the resolution which the World Congress proposes itself to choose its host. The civilization with the most votes becomes the host

	Applicable to: Resolution

??? example  "Elects the World Leader"
	Marks the resolution which the World Congress proposes itself to choose the World Leader. A civilization that wins it wins the Diplomatic Victory

	Applicable to: Resolution

??? example  "Cannot be proposed while [resolution] is in effect or proposed"
	Only with the ModConstants `leagueSessionRulesLikeVp`. The resolution cannot be proposed to be enacted while the named one is in effect or on the table, whatever the choice. Vox Populi hard-codes these pairs by their effect (CvLeague::CanProposeEnact, V:CvVotingClasses.cpp:2909-3150: World War against the unit maintenance of Standing Army Tax, Arts against Sciences Funding (only with MOD_BALANCE_VP), more against less tourism); here a ruleset lists them, and it is enough that ONE of the two names the other (the conflict works both ways, as VP's checks do). Not VP's own form: a data form is this port's departure

	Example: "Cannot be proposed while [Standing Army Tax] is in effect or proposed"

	Applicable to: Resolution

??? example  "Cannot be proposed while [resolution] is in effect or proposed for the same choice"
	Only with the ModConstants `leagueSessionRulesLikeVp`. As `Cannot be proposed while [resolution] is in effect or proposed`, but only when the other one has the same choice (Open Door against Sphere of Influence for the same City-State, V:CvVotingClasses.cpp:3025-3096)

	Example: "Cannot be proposed while [Standing Army Tax] is in effect or proposed for the same choice"

	Applicable to: Resolution

??? example  "Can only be proposed while [resolution] is in effect"
	Only with the ModConstants `leagueSessionRulesLikeVp`. The resolution is not valid, so it cannot be proposed and drops off the table, while the named one is not in effect. Vox Populi: the diplomatic victory resolution needs the `TriggerResolution` of the United Nations session (World Ideology), `CvLeague::IsResolutionEffectsValid`, V:CvVotingClasses.cpp:3237-3270

	Example: "Can only be proposed while [Standing Army Tax] is in effect"

	Applicable to: Resolution

??? example  "Can only be proposed by the ally of the chosen civilization"
	Only with the ModConstants `leagueSessionRulesLikeVp`. The proposer must be the ally of the civilization it chose (Sphere of Influence: the City-State's ally, `CvLeague::CanProposeEnact`, V:CvVotingClasses.cpp:3054-3068)

	Applicable to: Resolution

??? example  "Opens the door of the chosen City-State to every civilization"
	Open Door (`bOpenDoor`, V:CvVotingClasses.cpp:1425-1440): when it is enacted every living major civilization meets the chosen City-State if it has not, and its influence with it is SET to 40, up or down; the City-State can have no ally while the resolution is in effect, and has none after the enactment. When it is repealed, or stops being valid, the City-State can have an ally again (V:1921-1927); the influence stays where it was. The resolution must have the proposerDecision CityState. Only with the ModConstants `leagueSessionRulesLikeVp`

	Applicable to: Resolution

??? example  "Puts the chosen City-State in the sphere of influence of the proposer"
	Sphere of Influence (`bSphereOfInfluence`, V:CvVotingClasses.cpp:1442-1454): when it is enacted the City-State makes peace with the proposer and the proposer is its permanent ally, whatever the influence. When it is repealed, or stops being valid, the permanent ally is gone (V:1928-1929). The resolution must have the proposerDecision CityState. Only with the ModConstants `leagueSessionRulesLikeVp`

	Applicable to: Resolution

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

## SpyBonus uniques
!!! note ""

    Uniques of a passive bonus of a spy, which the spy's owner gets when the spy has stored enough Network Points in a city

??? example  "Reveals the city screen to the spy's owner"
	The spy's owner can look into the city's screen (SpyPassiveBonuses.RevealCityScreen)

	Applicable to: SpyBonus

??? example  "[positiveAmount]% of the city's Science goes to the spy's owner"
	Every turn the spy's owner gets this percent of the science the city makes, summed over the owner's spies (SpyPassiveBonuses.SciencePercentAdded)

	Example: "[3]% of the city's Science goes to the spy's owner"

	Applicable to: SpyBonus

??? example  "[positiveAmount] more tile(s) of vision around the city"
	The spy's owner sees this many more rings of tiles around the city (SpyPassiveBonuses.TilesRevealed), on top of the one a set up spy sees

	Example: "[3] more tile(s) of vision around the city"

	Applicable to: SpyBonus

??? example  "[positiveAmount]% of Congress votes can be traded with this civilization"
	A diplomat's bonus (SpyPassiveBonusesDiplomat.VoteTradePercent), read by the World Congress

	Example: "[3]% of Congress votes can be traded with this civilization"

	Applicable to: SpyBonus

??? example  "[positiveAmount]% Gold on trade routes between us"
	A diplomat's bonus (SpyPassiveBonusesDiplomat.TradeRouteGoldBonus), read by the trade route model

	Example: "[3]% Gold on trade routes between us"

	Applicable to: SpyBonus

??? example  "The spy uncovers intrigue"
	A diplomat's bonus (SpyPassiveBonusesDiplomat.ReceiveIntrigues): the diplomat finds out what the civilization plans

	Applicable to: SpyBonus

??? example  "The spy reveals the true approaches of this civilization"
	A diplomat's bonus (SpyPassiveBonusesDiplomat.RevealTrueApproaches)

	Applicable to: SpyBonus

## SpyMission uniques
!!! note ""

    Uniques of a spy mission, which happen when the mission is done

??? example  "[relativeAmount] City Security [cityFilter]"
	Only with the ModConstants `espionageLikeVp`. Added to the security of matching cities, which lowers the Network Points that enemy spies gather in them (CvCity::CalculateCitySecurity, V:CvCity.cpp:25992-26085). Buildings, policies and wonders give it, and a counterspy focus while its spy counters spies in the city

	Example: "[+20] City Security [in all cities]"

	Applicable to: Global, SpyMission

??? example  "Steal [positiveAmount] random technology/technologies the target knows and we can research"
	A spy mission's effect (CvCity::DoEventChoice, StealNumTechs): the spy's owner learns this many technologies the city's owner has and the owner could research now, chosen at random. The mission cannot be done when there are fewer

	Example: "Steal [3] random technology/technologies the target knows and we can research"

	Applicable to: SpyMission

??? example  "Needs [spyMissionRequirement]"
	A condition of a spy mission beside its technology, city filter and the focus that blocks it (CvCity::IsCityEventChoiceValid, NoFreshWater V:CvCity.cpp:4191, HasTradeConnection 4412, EnemyUnhappy 4721, RequiresNotPuppet): the city has no fresh water, it is the start or the end of a trade route of any civilization, its owner is unhappy, or it is no puppet, or `the city` for a condition that is only its modifiers. The unique's conditionals are tested with the target city, so `Needs [the city] <in cities with a [Temple]>` is how a mission asks for a building and `<in cities without a [Medical Lab]>` for the lack of one. The mission cannot be done where any of its `Needs` is not met

	Example: "Needs [no fresh water]"

	Applicable to: SpyMission

??? example  "Steal [positiveAmount]% of the treasury of the target"
	A spy mission's effect (CvCity::DoEventChoice, StealFromTreasuryPercent, V:CvCity.cpp:6616-6630): the spy's owner takes this percent of the gold of the city's owner, divided by the game speed's production cost modifier (more on a faster speed), rounded down

	Example: "Steal [3]% of the treasury of the target"

	Applicable to: SpyMission

??? example  "Siphon [positiveAmount]% of the [stat] of the city for [positiveAmount] turns"
	A spy mission's effect (CvCity::DoEventChoice, YieldSiphon, V:CvCity.cpp:6822-6843): for the given turns the spy's owner gains, and the city's owner loses, each turn the amount the city yielded at the moment of the mission, whole points, not changed by the owner's modifiers afterwards. Each side keeps it as a temporary `[stats]` unique of the civilization (flat, added after the cities, like the passive spy science)

	Example: "Siphon [3]% of the [Culture] of the city for [3] turns"

	Applicable to: SpyMission

??? example  "Pillage [positiveAmount]% of the [improvementFilter] improvements in the city's tiles"
	A spy mission's effect (CvCity::DoEventChoice, PillageFortificationsChance, V:CvCity.cpp:7097-7122): every unpillaged improvement of the city's tiles, except in the city centre, that matches the filter (the ruleset tags the improvements it counts as fortifications) is pillaged with the chance, rolled for each tile unless it is 100

	Example: "Pillage [3]% of the [All Road] improvements in the city's tiles"

	Applicable to: SpyMission

??? example  "Pillage [positiveAmount]% of the improved resource tiles in the city's tiles"
	A spy mission's effect (CvCity::DoEventChoice, PillageResourceTilesChance, V:CvCity.cpp:7047-7074): every tile of the city with a resource whose improvement is unpillaged and connects it is pillaged with the chance, rolled for each tile unless it is 100

	Example: "Pillage [3]% of the improved resource tiles in the city's tiles"

	Applicable to: SpyMission

??? example  "Pillage [positiveAmount]% of the roads in the city's tiles"
	A spy mission's effect (CvCity::DoEventChoice, PillageRoadsChance, V:CvCity.cpp:7075-7096): every unpillaged road or railroad of the city's tiles, except in the city centre, is pillaged with the chance, rolled for each tile unless it is 100

	Example: "Pillage [3]% of the roads in the city's tiles"

	Applicable to: SpyMission

## Tech uniques
??? example  "[positiveAmount] World Congress Delegates for every capital that hosts one of our diplomats"
	Vox Populi's `Technologies.ExtraVotesPerDiplomat` (V:CvTeam.cpp:8045, CvVotingClasses.cpp:4154-4166). The amounts of all researched technologies are ADDED. Replaces the Brave New World rule that names Globalization in the code

	Example: "[3] World Congress Delegates for every capital that hosts one of our diplomats"

	Applicable to: Tech

??? example  "Starting tech"
	Applicable to: Tech

??? example  "Can be continually researched"
	Applicable to: Tech

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Cannot be hurried"
	Applicable to: Tech, Building

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Policy uniques
??? example  "Is an ideology"
	Marks a policy branch as an ideology: a civilization has at most one (the others of the rules must make them exclusive, as `Only available <before adopting [policy]>`), adopting it costs nothing and is not a purchase, and its tenets are the policies of the branch that have a `Tenet Level [amount]` tag. Tenets of a level above 1 are limited by the rules of the ruleset, see the Brave New World ideologies for the usual way. See also `[positiveAmount] Free Tenets`

	Applicable to: Policy

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## FounderBelief uniques
!!! note ""

    Uniques for Founder and Enhancer type Beliefs, that will apply to the founder of this religion

??? example  "[relativeAmount] Tourism from every building that can only be bought with [stat] [cityFilter]"
	Read from the founder beliefs of the majority religion of each city, not of the founder of the religion. Added to the tourism of the city before the percentages, like `[relativeAmount] Tourism [cityFilter]`. A building counts if it is in the city, cannot be built, and some belief of the ruleset lets you buy it with the stat (CvCityBuildings::GetNumBuildingsFromFaith)

	Example: "[+20] Tourism from every building that can only be bought with [Culture] [in all cities]"

	Applicable to: FounderBelief

??? example  "[stats] for each global city following this religion"
	Example: "[+1 Gold, +2 Production] for each global city following this religion"

	Applicable to: FounderBelief

??? example  "[stats] from every [positiveAmount] global followers [cityFilter]"
	Example: "[+1 Gold, +2 Production] from every [3] global followers [in all cities]"

	Applicable to: FounderBelief

??? example  "[stats] from every [positiveAmount] global cities following this religion [cityFilter]"
	Counts the cities of every civilization that have the religion of the founder as their majority religion and match the filter, rounded down to a multiple of the amount, so that `[+1 Happiness] from every [2] global cities following this religion [in all cities]` gives 1 for 2 or 3 cities

	Example: "[+1 Gold, +2 Production] from every [3] global cities following this religion [in all cities]"

	Applicable to: FounderBelief

??? example  "[relativeAmount]% [stat] from every follower, up to [relativeAmount]%"
	Example: "[+20]% [Culture] from every follower, up to [+20]%"

	Applicable to: FounderBelief, FollowerBelief

??? example  "[mapUnitFilter] units convert adjacent Barbarian units"
	When such a unit of the founder of the religion moves next to a Barbarian unit, or a Barbarian unit moves next to it, the Barbarian unit (the best defender of its tile) joins the founder, with no movement left. A Barbarian camp under it is cleared, without the gold (CvBeliefHelpers::ConvertBarbarianUnit)

	Example: "[Wounded] units convert adjacent Barbarian units"

	Applicable to: FounderBelief

??? example  "Spreading religion erodes [positiveAmount]% of the pressure it adds from the other religions in the city"
	Read from the founder beliefs of the religion that is spread. Applies to units that do not remove the other religions altogether (see `Removes other religions when spreading religion`). Pantheons and the citizens of no religion are not eroded

	Example: "Spreading religion erodes [3]% of the pressure it adds from the other religions in the city"

	Applicable to: FounderBelief

??? example  "[positiveAmount]% of the pressure of this religion in a city remains when other religions are removed from it"
	Read from the founder beliefs of the religion that would be removed, which keeps that share of its pressure instead of losing all of it when a unit removes other religions (Inquisitors, Great Prophets)

	Example: "[3]% of the pressure of this religion in a city remains when other religions are removed from it"

	Applicable to: FounderBelief

??? example  "Spies exert [positiveAmount] times the natural religious pressure on the cities they occupy"
	Every turn, each living spy of the founder of a religion adds this multiple of the pressure that a nearby city exerts to the religion in the city the spy is in, if the religion already has a presence there

	Example: "Spies exert [3] times the natural religious pressure on the cities they occupy"

	Applicable to: FounderBelief

??? example  "[relativeAmount] Network Points per turn for our spies in foreign cities"
	Only with the ModConstants `espionageLikeVp`. Added to the base of every spy's Network Points a turn (Policies.EspionageNetworkPoints and the Enhancer belief's `Beliefs.EspionageNetworkPoints`: CvPlayerEspionage::CalcNetworkPointsPerTurn)

	Example: "[+20] Network Points per turn for our spies in foreign cities"

	Applicable to: Global, FounderBelief

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "[relativeAmount]% weight to this choice for AI decisions"
	Example: "[+20]% weight to this choice for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: FounderBelief, FollowerBelief, Building, Promotion, EventChoice

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## FollowerBelief uniques
!!! note ""

    Uniques for Pantheon and Follower type beliefs, that will apply to each city where the religion is the majority religion

??? example  "[stats] [cityFilter]"
	Example: "[+1 Gold, +2 Production] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every specialist [cityFilter]"
	Example: "[+1 Gold, +2 Production] from every specialist [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every [specialist] [cityFilter]"
	Adds the stats only for the named specialist in matching cities

	Example: "[+1 Gold, +2 Production] from every [Merchant] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] population [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] population [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] population proportionally [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] population proportionally [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] per [positiveAmount] citizens in the empire proportionally [cityFilter]"
	Example: "[+1 Gold, +2 Production] per [3] citizens in the empire proportionally [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] for each major religion with followers in the city [cityFilter]"
	Counts the religions of which at least one citizen of the city is a follower, pantheons not included. Whole numbers of the stats only, as in the original (CvCity::GetYieldPerReligionTimes100)

	Example: "[+1 Gold, +2 Production] for each major religion with followers in the city [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] in cities on [terrainFilter] tiles"
	Example: "[+1 Gold, +2 Production] in cities on [Fresh Water] tiles"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from all [buildingFilter] buildings"
	Example: "[+1 Gold, +2 Production] from all [Culture] buildings"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from [tileFilter] tiles [cityFilter]"
	Example: "[+1 Gold, +2 Production] from [Farm] tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from [tileFilter] tiles without [tileFilter] [cityFilter]"
	Example: "[+1 Gold, +2 Production] from [Farm] tiles without [Farm] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from every [tileFilter/specialist/buildingFilter]"
	Example: "[+1 Gold, +2 Production] from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from each Trade Route"
	Example: "[+1 Gold, +2 Production] from each Trade Route"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] Tourism [cityFilter]"
	Added to the tourism of the city before the percentages, see ModConstants and the unique `[relativeAmount]% Tourism [cityFilter]`

	Example: "[+20] Tourism [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Tourism [cityFilter]"
	All such bonuses of a city are added up and applied to its tourism, if the sum is positive

	Example: "[+20]% Tourism [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] from each [greatWorkType] Great Work [cityFilter]"
	The stats once for each Great Work of that type that is in a building of the matching city (the original's `Building_GreatWorkYieldChanges`, `Policy_ArtYieldChanges` and the like, CvCityBuildings::GetYieldFromGreatWorksTimes100). Added to the base stats of the city, before the percentages, like `[stats] from each Great Work`

	Example: "[+1 Gold, +2 Production] from each [Art] Great Work [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% yield from processes [cityFilter]"
	While a matching city makes Gold, Science, Culture, Faith or Food instead of a building or a unit, it converts this many percentage points more of its production than the 25% the conversion starts at (`Building_YieldFromProcessModifier` added to `Process_ProductionYields`, CvCity::GetBaseYieldRateFromProcessTimes100). It does not name the stat: it improves whichever process the city is running

	Example: "[+20]% yield from processes [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% of [stat] [cityFilter] added to [stat]"
	The first stat of a matching city, as the sources add it up before any percentage bonus, times the percentage, is added to the second stat after the percentage bonuses (the original's `Building_YieldFromYieldPercent`, CvCity::UpdateCityYieldFromYield, GetTotalYieldFromYieldTimes100). It is not itself changed or fed back

	Example: "[+20]% of [Culture] [in all cities] added to [Culture]"

	Applicable to: Global, FollowerBelief

??? example  "1 World Congress Delegate for every [positiveAmount] [improvementName] we own"
	Vox Populi's `Belief_VotePerXImprovementOwned` (Holy Land; V:CvBeliefClasses.cpp:4956-4974, CvPlayer.cpp:23818-23835). The fractions of all such uniques of the same improvement are ADDED exactly (1/2 and 1/3 give 5/6), multiplied by the number owned, and truncated once

	Example: "1 World Congress Delegate for every [3] [Village] we own"

	Applicable to: FollowerBelief

??? example  "[relativeAmount]% [stat]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] from every [tileFilter/buildingFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% [Culture] from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Yield from every [tileFilter/buildingFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Yield from every [Farm]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% [stat] from every follower, up to [relativeAmount]%"
	Example: "[+20]% [Culture] from every follower, up to [+20]%"

	Applicable to: FounderBelief, FollowerBelief

??? example  "[relativeAmount]% Production when constructing [buildingFilter] buildings [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Culture] buildings [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production when constructing [baseUnitFilter] units [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Melee] units [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production when constructing [buildingFilter] wonders [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Production when constructing [Culture] wonders [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Production towards any buildings that already exist in the Capital"
	Example: "[+20]% Production towards any buildings that already exist in the Capital"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% growth [cityFilter]"
	Example: "[+20]% growth [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount]% Food is carried over after population increases [cityFilter]"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[3]% Food is carried over after population increases [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Food consumption by [populationFilter] [cityFilter]"
	Example: "[+20]% Food consumption by [Followers of this Religion] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Unhappiness from [populationFilter] [cityFilter]"
	Example: "[+20]% Unhappiness from [Followers of this Religion] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Reduces [need] by [positiveAmount] [cityFilter]"
	Takes that many unhappy citizens off the need in the matching cities, which can not fall below 0 (the original's `DistressFlatReduction`, `PovertyFlatReduction`, `IlliteracyFlatReduction`, `BoredomFlatReduction` and `ReligiousUnrestFlatReduction`, with their `Global` variants for `in all cities`). Several add up. Distress is the unhappiness of a city's food and production, Poverty of its gold, Illiteracy of its science, Boredom of its culture, Religious Unrest of its religious minorities. Only with the ModConstants `cityNeedsLikeVp`

	Example: "Reduces [Distress] by [3] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Reduces the empire size need modifier by [positiveAmount] [cityFilter]"
	Takes percent points off the rise of every need of the matching cities that the cities and the citizens of the empire cause. It does not go below 0 or above that rise (the original's `EmpireSizeModifierReduction` and `EmpireSizeModifierReductionGlobal`, which hold negative numbers). Several add up. Only with the ModConstants `cityNeedsLikeVp`

	Example: "Reduces the empire size need modifier by [3] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "The first [positiveAmount] specialists cause no unhappiness [cityFilter]"
	In the matching cities that many specialists cost no unhappiness (the original's `NoUnhappfromXSpecialists`, `...Global` and `...Capital`). Several add up. Only with the ModConstants `cityNeedsLikeVp`

	Example: "The first [3] specialists cause no unhappiness [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness from the first [positiveAmount] specialists [cityFilter]"
	Local happiness of the matching cities: the first number for each of their first specialists, as many as the second number (the original's `HappfromXSpecialists`, one happiness per specialist). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness from the first [3] specialists [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per [positiveAmount] citizens [cityFilter]"
	Local happiness of the matching cities: amount x citizens / the second number, rounded towards 0 (the original's `HappinessPerXPopulation` and `HappinessPerXPopulationGlobal`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per [3] citizens [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per [positiveAmount] Great Works [cityFilter]"
	Local happiness of the matching cities: amount for every second-number Great Works in the city (the original's `HappinessPerXGreatWorks`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per [3] Great Works [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[amount] Happiness per active trade route [cityFilter]"
	Local happiness of the matching cities: amount for each trade route that starts in the city (the original's `HappinessPerActiveTradeRoute`). Only with the ModConstants `cityNeedsLikeVp`

	Example: "[3] Happiness per active trade route [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units for [nonNegativeAmount] [stat] [cityFilter] at an increasing price ([amount])"
	Example: "May buy [Melee] units for [3] [Culture] [in all cities] at an increasing price ([3])"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings for [nonNegativeAmount] [stat] [cityFilter] at an increasing price ([amount])"
	Example: "May buy [Culture] buildings for [3] [Culture] [in all cities] at an increasing price ([3])"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units for [nonNegativeAmount] [stat] [cityFilter]"
	Example: "May buy [Melee] units for [3] [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings for [nonNegativeAmount] [stat] [cityFilter]"
	Example: "May buy [Culture] buildings for [3] [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units with [stat] [cityFilter]"
	Example: "May buy [Melee] units with [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings with [stat] [cityFilter]"
	Example: "May buy [Culture] buildings with [Culture] [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "May buy [baseUnitFilter] units with [stat] for [nonNegativeAmount] times their normal Production cost"
	Example: "May buy [Melee] units with [Culture] for [3] times their normal Production cost"

	Applicable to: Global, FollowerBelief

??? example  "May buy [buildingFilter] buildings with [stat] for [nonNegativeAmount] times their normal Production cost"
	Example: "May buy [Culture] buildings with [Culture] for [3] times their normal Production cost"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing items in cities [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing items in cities [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing [buildingFilter] buildings [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing [Culture] buildings [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "[stat] cost of purchasing [baseUnitFilter] units [relativeAmount]%"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[Culture] cost of purchasing [Melee] units [+20]%"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% maintenance cost for [buildingFilter] buildings [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% maintenance cost for [Culture] buildings [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] Golden Age points per turn [cityFilter]"
	Added to the golden age meter at the end of each turn for every matching city (the original's YIELD_GOLDEN_AGE_POINTS as a city yield, CvPlayer::GetGoldenAgePointsFromCitiesTimes100). With `<for every [countable]>` it covers the per-specialist, per-building and per-improvement rows

	Example: "[+20] Golden Age points per turn [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] border culture [cityFilter]"
	Example: "[+20] border culture [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount] border culture from [tileFilter] tiles [cityFilter]"
	Example: "[+20] border culture from [Farm] tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% border culture [cityFilter]"
	Example: "[+20]% border culture [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Culture cost of natural border growth [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Culture cost of natural border growth [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Gold cost of acquiring tiles [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Gold cost of acquiring tiles [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "[stats] for each trade route that starts in this city"
	Added to the city for every trade route of its owner that starts in it and every route that ends in it, counted as the original's `Belief_YieldPerActiveTR` does (V:CvBeliefClasses.cpp:4332-4350 with CvPlayerTrade::GetNumberOfTradeRoutesCity, V:CvTradeClasses.cpp:4723-4730: the routes from the city and the routes to it, of any civilization, so a route between two of the civilization's cities counts for both). Nothing reads it in a ruleset that does not use it

	Example: "[+1 Gold, +2 Production] for each trade route that starts in this city"

	Applicable to: Global, FollowerBelief

??? example  "[stats] on [tradeRouteFilter] trade routes"
	Added to the yield of a trade route that matches the filter, for the stat it names, before the route's modifiers (Vox Populi's `Belief_TradeRouteYieldChange`, `Policy_TradeRouteYieldChange`, `Trait_TradeRouteYieldChange`, V:CvTradeClasses.cpp:3232-3270): the unique of the city the route starts in. Gold, culture and science for an international route, food and production for a domestic one; with the modifier `<for every [Era scaler]>` it grows with the era. Only read with `tradeRouteModelLikeVp`

	Example: "[+1 Gold, +2 Production] on [Land] trade routes"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% quantity of [resource]"
	Changes the number of copies of the resource that the civilization owns, in the cities that follow the belief (a follower belief) or everywhere (global), as the original's `Belief_ResourceQuantityModifiers` do (V:CvPlayer.cpp:38843-38865; there the modifier is the amount times the number of cities that follow the belief, at most 25, on the whole supply: here each city has its own amount on the copies it gives)

	Example: "[+20]% quantity of [Iron]"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Great Person generation [cityFilter]"
	Example: "[+20]% Great Person generation [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "New [baseUnitFilter] units start with [amount] XP [cityFilter]"
	Example: "New [Melee] units start with [3] XP [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "All newly-trained [baseUnitFilter] units [cityFilter] receive the [promotion] promotion"
	Example: "All newly-trained [Melee] units [in all cities] receive the [Shock I] promotion"

	Applicable to: Global, FollowerBelief

??? example  "[mapUnitFilter] Units adjacent to this city heal [amount] HP per turn when healing"
	Example: "[Wounded] Units adjacent to this city heal [3] HP per turn when healing"

	Applicable to: Global, FollowerBelief

??? example  "[relativeAmount]% Strength for cities"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Strength for cities"

	Applicable to: Global, FollowerBelief

??? example  "Provides [amount] [resource]"
	Example: "Provides [3] [Iron]"

	Applicable to: Global, FollowerBelief, Improvement

??? example  "[relativeAmount]% Natural religion spread [cityFilter]"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Natural religion spread [in all cities]"

	Applicable to: Global, FollowerBelief

??? example  "Religion naturally spreads to cities [amount] tiles away"
	Example: "Religion naturally spreads to cities [3] tiles away"

	Applicable to: Global, FollowerBelief

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Earn [amount]% of [mapUnitFilter] unit's [costOrStrength] as [stockpile] when killed within 4 tiles of a city following this religion"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats.

	Example: "Earn [3]% of [Wounded] unit's [Cost] as [Mana] when killed within 4 tiles of a city following this religion"

	Applicable to: FollowerBelief

??? example  "[relativeAmount]% weight to this choice for AI decisions"
	Example: "[+20]% weight to this choice for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: FounderBelief, FollowerBelief, Building, Promotion, EventChoice

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Building uniques
??? example  "[positiveAmount]% of [stat] from every [improvementFilter/buildingFilter] in the city added to [resource]"
	Example: "[3]% of [Culture] from every [All Road] in the city added to [Iron]"

	Applicable to: Building

??? example  "[amount] Influence from diplomatic missions of units from this city"
	Added to the mission of a unit whose origin city has the building (`Buildings.DiplomatInfluenceBoost`, CvCity::GetDiplomatInfluenceBoost; the Wat 10). Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[3] Influence from diplomatic missions of units from this city"

	Applicable to: Building

??? example  "Consumes [amount] [resource]"
	Example: "Consumes [3] [Iron]"

	Applicable to: Building, Unit, Improvement

??? example  "Costs [amount] [stockpiledResource]"
	These resources are removed *when work begins* on the construction. Do not confuse with "costs [amount] [stockpiledResource]" (lowercase 'c'), the Unit Action Modifier.

	Example: "Costs [3] [Mana]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Building, Unit, Improvement

??? example  "Unbuildable"
	Blocks from being built, possibly by conditional. However it can still appear in the menu and be bought with other means such as Gold or Faith

	Applicable to: Building, Unit, Improvement

??? example  "Cannot be purchased"
	Applicable to: Building, Unit

??? example  "Can be purchased with [stat] [cityFilter]"
	Example: "Can be purchased with [Culture] [in all cities]"

	Applicable to: Building, Unit

??? example  "Can be purchased for [amount] [stat] [cityFilter]"
	Example: "Can be purchased for [3] [Culture] [in all cities]"

	Applicable to: Building, Unit

??? example  "Limited to [amount] per Civilization"
	Example: "Limited to [3] per Civilization"

	Applicable to: Building, Unit

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Excess Food converted to Production when under construction"
	Applicable to: Building, Unit

??? example  "Requires at least [amount] population"
	Example: "Requires at least [3] population"

	Applicable to: Building, Unit

??? example  "Requires [positiveAmount] adopted Policies"
	The civilization must have adopted this many policies, not counting the branches and their finishers (the original's `Buildings.NumPoliciesNeeded` of the wonders, CvPlayer::canConstruct, V:CvPlayer.cpp:14578-14650); not for City-States and barbarians. Lowered by `Wonders need 1 fewer adopted Policy for every [positiveAmount] allied City-States`

	Example: "Requires [3] adopted Policies"

	Applicable to: Building

??? example  "Triggers a global alert upon build start"
	Applicable to: Building, Unit

??? example  "Triggers a global alert upon completion"
	Applicable to: Building, Unit

??? example  "Cost increases by [amount] per owned city"
	Example: "Cost increases by [3] per owned city"

	Applicable to: Building, Unit

??? example  "Cost increases by [amount] when built"
	Example: "Cost increases by [3] when built"

	Applicable to: Building, Unit

??? example  "[amount]% production cost"
	Intended to be used with conditionals to dynamically alter construction costs. Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[3]% production cost"

	Applicable to: Building, Unit

??? example  "Can only be built"
	Meant to be used together with conditionals, like "Can only be built &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also NOT block Upgrade and Transform actions. See also OnlyAvailable.

	Applicable to: Building, Unit

??? example  "Must have an owned [tileFilter] within [amount] tiles"
	Example: "Must have an owned [Farm] within [3] tiles"

	Applicable to: Building

??? example  "[stats] on domestic trade routes that start in this city"
	Added to the yield of every food or production route of the civilization that starts in the city, before the route's percentage modifiers (Vox Populi's `Building_YieldFromInternalTR`, V:CvTradeClasses.cpp:4157, 4217). Only this city's own buildings count, whatever the unique says about the scope. Only read with `tradeRouteModelLikeVp`

	Example: "[+1 Gold, +2 Production] on domestic trade routes that start in this city"

	Applicable to: Building

??? example  "[stats] while any trade route passes through this city"
	Added to the city while the path of any trade route of any civilization goes over the city's tile (Vox Populi's `Building_YieldChangesFromPassingTR`, V:CvCity.cpp:23328 with CvPlot::IsTradeUnitRoute, V:CvTradeClasses.cpp:1376-1402: the path, not the place the trade unit stands now). Only this city's own buildings count

	Example: "[+1 Gold, +2 Production] while any trade route passes through this city"

	Applicable to: Building

??? example  "Enables nuclear weapon"
	Applicable to: Building

??? example  "Must be on [tileFilter]"
	Example: "Must be on [Farm]"

	Applicable to: Building

??? example  "Must not be on [tileFilter]"
	Example: "Must not be on [Farm]"

	Applicable to: Building

??? example  "Must be next to [tileFilter]"
	Example: "Must be next to [Farm]"

	Applicable to: Building, Improvement

??? example  "Must not be next to [tileFilter]"
	Example: "Must not be next to [Farm]"

	Applicable to: Building

??? example  "Unsellable"
	Applicable to: Building

??? example  "Obsolete with [tech]"
	Example: "Obsolete with [Agriculture]"

	Applicable to: Building, Improvement, Resource

??? example  "Indicates the capital city"
	Applicable to: Building

??? example  "Moves to new capital when capital changes"
	Applicable to: Building

??? example  "Allows airlifts to and from this city"
	A land unit that has not moved this turn and stands in, or next to, a city with this can be airlifted to a free land tile in, or next to, another city of the same civilization with this, unless an enemy unit is adjacent to that tile. Airlifting uses up the unit's movement.

	Applicable to: Building

??? example  "Provides 1 extra copy of each improved luxury resource near this City"
	Applicable to: Building

??? example  "Destroyed when the city is captured"
	Applicable to: Building

??? example  "Never destroyed when the city is captured"
	Applicable to: Building

??? example  "Has a [nonNegativeAmount]% chance of surviving when the city is captured"
	The chance, rolled once per building when a city is conquered, that this building is not destroyed (Buildings.ConquestProb). 0 destroys it, 100 keeps it. Buildings with `Destroyed when the city is captured` or `Never destroyed when the city is captured`, wonders and the capital's buildings are not rolled. Conditionals are not evaluated. Only read with the ModConstants switch `conquestLikeVp`; without a unique the chance is `conquestConstants.buildingSurvivalPercent`.

	Example: "Has a [3]% chance of surviving when the city is captured"

	Applicable to: Building

??? example  "[relativeAmount]% Gold given to enemy if city is captured"
	Example: "[+20]% Gold given to enemy if city is captured"

	Applicable to: Building

??? example  "Removes extra unhappiness from annexed cities"
	Applicable to: Building

??? example  "Happiness from this building is not limited by the population of the city"
	Only matters with the ModConstants `localHappinessCappedByPopulation`, which limits the happiness of the buildings of a city to its population. Civ V's `UnmoddedHappiness` buildings (Circus Maximus, Notre Dame, Eiffel Tower, Prora) are exempt from that.

	Applicable to: Building

??? example  "Connects trade routes over water"
	Applicable to: Building

??? example  "Has [positiveAmount] [greatWorkSlotType] Great Work slots"
	Great Works of that type can be placed in the building, one in each slot. Several of these uniques add their slots together, in the order they are written

	Example: "Has [3] [Art/Artifact] Great Work slots"

	Applicable to: Building

??? example  "Allows [stat] trade routes between your cities"
	In this city, food or production routes to other cities of the same civilization can start. Only Food and Production are meaningful for [stat].

	Example: "Allows [Culture] trade routes between your cities"

	Applicable to: Building

??? example  "Automatically built in all cities where it is buildable"
	Applicable to: Building

??? example  "Creates a [improvementName] improvement on a specific tile"
	When choosing to construct this building, the player must select a tile where the improvement can be built. Upon building completion, the tile will gain this improvement. Limited to one per building.

	Example: "Creates a [Village] improvement on a specific tile"

	This unique does not support conditionals.

	Applicable to: Building

??? example  "[amount] city strength per [amount] world wonders"
	Example: "[3] city strength per [3] world wonders"

	Applicable to: Building

??? example  "Can carry [amount] extra [mapUnitFilter] units"
	For buildings, supports using `Air` for `mapUnitFilter` to increase city air unit capacity.

	Example: "Can carry [3] extra [Wounded] units"

	Applicable to: Building, Unit

??? example  "[amount] air defense"
	The damage this unit (the sum over its own uniques and promotions) or this city (the sum over its buildings) does to an air unit that attacks it, before the attacker's interception damage reduction and a random addition. Only read with the ModConstants switch `airDefenseLikeVp`.

	Example: "[3] air defense"

	Applicable to: Building, Unit

??? example  "Spaceship part"
	Applicable to: Building, Unit

??? example  "Cannot be hurried"
	Applicable to: Tech, Building

??? example  "[relativeAmount]% weight to this choice for AI decisions"
	Example: "[+20]% weight to this choice for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: FounderBelief, FollowerBelief, Building, Promotion, EventChoice

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Shown while unbuilable"
	This unique is automatically hidden from users.

	Applicable to: Building, Unit

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## UnitAction uniques
!!! note ""

    Uniques that affect a unit's actions, and can be modified by UnitActionModifiers

??? example  "Founds a new city"
	Applicable to: UnitAction

??? example  "Founds a new puppet city"
	Applicable to: UnitAction

??? example  "Can instantly construct a [improvementFilter] improvement"
	Example: "Can instantly construct a [All Road] improvement"

	Applicable to: UnitAction

??? example  "Can Spread Religion"
	Applicable to: UnitAction

??? example  "Can remove other religions from cities"
	Applicable to: UnitAction

??? example  "May found a religion"
	Applicable to: UnitAction

??? example  "May enhance a religion"
	Applicable to: UnitAction

??? example  "Can transform to [unit]"
	By default consumes all movement

	Example: "Can transform to [Musketman]"

	Applicable to: UnitAction

## Unit uniques
!!! note ""

    Uniques that can be added to units, unit types, or promotions

??? example  "[relativeAmount]% Yield from pillaging tiles"
	Example: "[+20]% Yield from pillaging tiles"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Health from pillaging tiles"
	Example: "[+20]% Health from pillaging tiles"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% construction time for [improvementFilter] improvements"
	Example: "[+20]% construction time for [All Road] improvements"

	Applicable to: Global, Unit

??? example  "Can build [improvementFilter] improvements at a [relativeAmount]% rate"
	Example: "Can build [All Road] improvements at a [+20]% rate"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Gold from Great Merchant trade missions"
	Example: "[+20]% Gold from Great Merchant trade missions"

	Applicable to: Global, Unit

??? example  "Can undertake a diplomatic mission with City-State"
	Only with the ModConstants `diplomaticMissionsLikeVp`: the unit can do the action 'Conduct Diplomatic Mission' in the territory of a City-State it is not at war with (CvUnit::canTrade, V:CvUnit.cpp:11990-12022, which asks for a gold blast or a mission influence). It is used up, and gives the City-State influence as in [com.unciv.logic.civilization.diplomacy.DiplomaticMission.influence]

	Applicable to: Unit

??? example  "[amount] Influence from diplomatic missions"
	The base influence of a diplomatic mission (the original's `UnitPromotions.DiploMissionInfluence`, summed over the unit's promotions: Emissary 40, Envoy 50, Diplomat 60, Ambassador 70, Great Diplomat 100, the free promotions of buildings 10-15). Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[3] Influence from diplomatic missions"

	Applicable to: Unit, Promotion

??? example  "[amount] Influence from diplomatic missions for every era"
	Added once per era number of the owner (`Units.NumInfPerEra`: Great Diplomat 15, Tadodaho 15), before the percentages. Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[3] Influence from diplomatic missions for every era"

	Applicable to: Unit

??? example  "[relativeAmount]% Influence from diplomatic missions"
	On a unit or a promotion: `UnitPromotions.TradeMissionInfluenceModifier`; on a policy or other global source: `Policies.MissionInfluenceModifier` (Foreign Service 10). Each is its own multiplication, rounded down. Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[+20]% Influence from diplomatic missions"

	Applicable to: Global, Unit, Promotion

??? example  "[amount] to the resting point of the influence of the City-State, for the civilization of the unit, from its diplomatic mission"
	`Units.RestingPointChange` (Tadodaho 45), added to the resting point of the civilization's influence with the City-State where the mission is done (`DiplomacyManager.restingPointChange`, V:CvUnit.cpp:12094-12098). Only with the ModConstants `diplomaticMissionsLikeVp` and `cityStateInfluenceLikeVp`, which reads it

	Example: "[3] to the resting point of the influence of the City-State, for the civilization of the unit, from its diplomatic mission"

	Applicable to: Unit

??? example  "Lowers the influence of every other civilization that knows the City-State by as much as it gives, with its diplomatic mission"
	The Great Diplomat (`Units.NumInfPerEra` above 0 under `MOD_BALANCE_VP`, V:CvUnit.cpp:12086 and 12101-12179): the influence of the mission is taken from each other major civilization that has met the City-State, and their ally status is updated after ours has risen. Only with the ModConstants `diplomaticMissionsLikeVp`

	Applicable to: Unit

??? example  "Great General provides double combat bonus"
	Applicable to: Global, Unit

??? example  "Uses unit supply"
	Marks a non-military unit as one that counts against the civilization's unit supply, as the original's MilitarySupport does for the Caravan, the Cargo Ship and the Work Boat. Units with a combat strength use supply without it; the other civilians never do.

	Applicable to: Unit

??? example  "Does not use unit supply"
	The unit does not count against the civilization's unit supply, whether the unit type or one of its promotions has it (CvUnit::isNoSupply). Conditionals are not evaluated. Only read with the ModConstants switch `unitSupplyLikeVp`.

	Applicable to: Unit

??? example  "Consumes [amount] [resource]"
	Example: "Consumes [3] [Iron]"

	Applicable to: Building, Unit, Improvement

??? example  "Costs [amount] [stockpiledResource]"
	These resources are removed *when work begins* on the construction. Do not confuse with "costs [amount] [stockpiledResource]" (lowercase 'c'), the Unit Action Modifier.

	Example: "Costs [3] [Mana]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Building, Unit, Improvement

??? example  "Unbuildable"
	Blocks from being built, possibly by conditional. However it can still appear in the menu and be bought with other means such as Gold or Faith

	Applicable to: Building, Unit, Improvement

??? example  "Cannot be purchased"
	Applicable to: Building, Unit

??? example  "Can be purchased with [stat] [cityFilter]"
	Example: "Can be purchased with [Culture] [in all cities]"

	Applicable to: Building, Unit

??? example  "Can be purchased for [amount] [stat] [cityFilter]"
	Example: "Can be purchased for [3] [Culture] [in all cities]"

	Applicable to: Building, Unit

??? example  "Can only be purchased in cities with [buildingName]"
	Buying the unit with gold needs the building (or one that replaces it) in the city; building it is not affected (the original's `Unit_BuildingClassPurchaseRequireds`, CvCity::IsCanPurchase)

	Example: "Can only be purchased in cities with [Library]"

	Applicable to: Unit

??? example  "Limited to [amount] per Civilization"
	Example: "Limited to [3] per Civilization"

	Applicable to: Building, Unit

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Excess Food converted to Production when under construction"
	Applicable to: Building, Unit

??? example  "Requires at least [amount] population"
	Example: "Requires at least [3] population"

	Applicable to: Building, Unit

??? example  "Triggers a global alert upon build start"
	Applicable to: Building, Unit

??? example  "Triggers a global alert upon completion"
	Applicable to: Building, Unit

??? example  "Cost increases by [amount] per owned city"
	Example: "Cost increases by [3] per owned city"

	Applicable to: Building, Unit

??? example  "Cost increases by [amount] when built"
	Example: "Cost increases by [3] when built"

	Applicable to: Building, Unit

??? example  "[amount]% production cost"
	Intended to be used with conditionals to dynamically alter construction costs. Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[3]% production cost"

	Applicable to: Building, Unit

??? example  "Can only be built"
	Meant to be used together with conditionals, like "Can only be built &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also NOT block Upgrade and Transform actions. See also OnlyAvailable.

	Applicable to: Building, Unit

??? example  "May create improvements on water resources"
	Applicable to: Unit

??? example  "Can build [improvementFilter/terrainFilter] improvements on tiles"
	Example: "Can build [All Road] improvements on tiles"

	Applicable to: Unit

??? example  "May choose the reward when entering Ancient Ruins"
	Applicable to: Unit, Promotion

??? example  "Can be added to [comment] in the Capital"
	Example: "Can be added to [comment] in the Capital"

	Applicable to: Unit

??? example  "Prevents spreading of religion to the city it is next to"
	Applicable to: Unit

??? example  "Removes other religions when spreading religion"
	Applicable to: Unit

??? example  "May Paradrop to [tileFilter] tiles up to [positiveAmount] tiles away"
	Example: "May Paradrop to [Farm] tiles up to [3] tiles away"

	Applicable to: Unit

??? example  "Can perform Air Sweep"
	Applicable to: Unit

??? example  "Can speed up construction of a building"
	Applicable to: Unit

??? example  "Can speed up the construction of a wonder"
	Applicable to: Unit

??? example  "Can hurry technology research"
	Applicable to: Unit

??? example  "Can generate a large amount of culture"
	Applicable to: Unit

??? example  "Can establish trade routes with a base range of [positiveAmount] tiles, travelling [positiveAmount] tiles per turn"
	Such a unit is used up when it establishes a route, and a new one appears in the city of origin when the route ends. Trade routes over land or water follow the domain of the unit. The number of routes is limited by [TradeRouteSlots].

	Example: "Can establish trade routes with a base range of [3] tiles, travelling [3] tiles per turn"

	Applicable to: Unit

??? example  "Can undertake a trade mission with City-State, giving a large sum of gold and [amount] Influence"
	Example: "Can undertake a trade mission with City-State, giving a large sum of gold and [3] Influence"

	Applicable to: Unit

??? example  "Can sell [positiveAmount] exotic goods"
	Each unit can sell this many times beside peaceful foreign territory. Each sale earns 100–400 Gold and 10–30 XP, scaling with distance from the owner's capital divided by half the sum of the map width and height (CvUnit::sellExoticGoods).

	Example: "Can sell [3] exotic goods"

	Applicable to: Unit

??? example  "Automation is a primary action"
	This unique is automatically hidden from users.

	Applicable to: Unit

??? example  "[relativeAmount]% Strength"
	Multiple bonuses stack additively: +50% + +50% = +100%

	Example: "[+20]% Strength"

	Applicable to: Global, Unit

??? example  "[relativeAmount] Strength"
	Example: "[+20] Strength"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Strength decreasing with distance from the capital"
	Example: "[+20]% Strength decreasing with distance from the capital"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% to Flank Attack bonuses"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% to Flank Attack bonuses"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Strength for enemy [mapUnitFilter] units in adjacent [tileFilter] tiles"
	Example: "[+20]% Strength for enemy [Wounded] units in adjacent [Farm] tiles"

	Applicable to: Unit

??? example  "[relativeAmount]% Strength bonus for [mapUnitFilter] units within [amount] tiles"
	Example: "[+20]% Strength bonus for [Wounded] units within [3] tiles"

	Applicable to: Unit

??? example  "[relativeAmount]% Strength bonus for [mapUnitFilter] units within [amount] tiles attacking a city this unit is adjacent to"
	Applies to attacks of your units that are of the same domain as this unit and not on this unit's tile, against a city next to this unit. The best bonus counts, bonuses of several units do not add up.

	Example: "[+20]% Strength bonus for [Wounded] units within [3] tiles attacking a city this unit is adjacent to"

	Applicable to: Unit

??? example  "Heavy Charge"
	When attacking a unit in melee: +50% Strength if it cannot fall back (all three tiles behind it, away from the attacker, are blocked), and if the attack does more damage than it takes the defender, if it survives, falls back and the attacker advances.

	Applicable to: Unit

??? example  "[amount] additional attacks per turn"
	Example: "[3] additional attacks per turn"

	Applicable to: Global, Unit

??? example  "[amount] Movement"
	Example: "[3] Movement"

	Applicable to: Global, Unit

??? example  "[amount] Sight"
	Example: "[3] Sight"

	Applicable to: Global, Unit, Terrain, Improvement

??? example  "[amount] Range"
	Example: "[3] Range"

	Applicable to: Global, Unit

??? example  "[relativeAmount] Air Interception Range"
	Example: "[+20] Air Interception Range"

	Applicable to: Global, Unit

??? example  "[amount] HP when healing"
	Example: "[3] HP when healing"

	Applicable to: Global, Unit

??? example  "[amount] maximum hit points"
	Added to the unit's 100 maximum hit points; conditionals are not evaluated (the sum is kept with the unit's uniques). Only read with the ModConstants switch `unitMaxHitPointsLikeVp`; the lost health stays the same when the maximum changes (CvUnit::changeMaxHitPointsChange leaves the damage as it is).

	Example: "[3] maximum hit points"

	Applicable to: Unit

??? example  "Before engaging in combat performs an extra ranged attack with [amount]% of melee combat strength"
	Example: "Before engaging in combat performs an extra ranged attack with [3]% of melee combat strength"

	Applicable to: Unit

??? example  "[relativeAmount]% Spread Religion Strength"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Spread Religion Strength"

	Applicable to: Global, Unit

??? example  "When spreading religion to a city, gain [amount] times the amount of followers of other religions as [stat]"
	Example: "When spreading religion to a city, gain [3] times the amount of followers of other religions as [Culture]"

	Applicable to: Global, Unit

??? example  "Can only attack [combatantFilter] units"
	Example: "Can only attack [City] units"

	Applicable to: Unit

??? example  "Can only attack [tileFilter] tiles"
	Example: "Can only attack [Farm] tiles"

	Applicable to: Unit

??? example  "Cannot attack"
	Applicable to: Unit

??? example  "Must set up to ranged attack"
	Applicable to: Unit

??? example  "Self-destructs when attacking"
	Applicable to: Unit

??? example  "Attacks bypass garrison damage absorption"
	Applicable to: Unit

??? example  "[relativeAmount]% defense for this and adjacent cities"
	Example: "[+20]% defense for this and adjacent cities"

	Applicable to: Unit

??? example  "[relativeAmount]% damage taken"
	Example: "[+20]% damage taken"

	Applicable to: Global, Unit

??? example  "[amount]% less damage from cities"
	Example: "[3]% less damage from cities"

	Applicable to: Global, Unit

??? example  "Eliminates combat penalty for attacking across a coast"
	Applicable to: Unit

??? example  "May attack when embarked"
	Applicable to: Unit

??? example  "Eliminates combat penalty for attacking over a river"
	Applicable to: Unit

??? example  "Blast radius [amount]"
	Example: "Blast radius [3]"

	Applicable to: Unit

??? example  "Ranged attacks may be performed over obstacles"
	Applicable to: Global, Unit

??? example  "Nuclear weapon of Strength [amount]"
	Example: "Nuclear weapon of Strength [3]"

	Applicable to: Unit

??? example  "No defensive terrain bonus"
	Applicable to: Global, Unit

??? example  "No defensive terrain penalty"
	Applicable to: Global, Unit

??? example  "Is a mounted unit"
	Applicable to: Unit

??? example  "[relativeAmount]% Strength for enemy [mapUnitFilter] units within [amount] tiles"
	Example: "[+20]% Strength for enemy [Wounded] units within [3] tiles"

	Applicable to: Global, Unit

??? example  "Stronger when damaged"
	Applicable to: Global, Unit

??? example  "No damage penalty for wounded units"
	Applicable to: Global, Unit

??? example  "Uncapturable"
	Applicable to: Unit

??? example  "Becomes [baseUnitFilter] when captured"
	The unit name the unit becomes for the capturer (the default unit of the capture class; the capturer's own unique replacement is looked up through `getEquivalentUnit`). Only read with the ModConstants switch `captureClassLikeVp`; with barbarians involved the unit stays as it is (CvUnit::getCaptureDefinition).

	Example: "Becomes [Melee] when captured"

	Applicable to: Unit

??? example  "Withdraws before melee combat"
	Applicable to: Unit

??? example  "Unable to capture cities"
	Applicable to: Global, Unit

??? example  "Unable to pillage tiles"
	Applicable to: Global, Unit

??? example  "[amount] extra flanking power"
	Each flank-supporting unit counts as 1 plus the sum of its `extra flanking power` (VP CvUnit::GetFlankPower), so the flanking bonus of the unit it surrounds is 10% x that sum. Only read with the ModConstants switch `flankPowerLikeVp`.

	Example: "[3] extra flanking power"

	Applicable to: Unit

??? example  "Ignores terrain cost in [tileFilter] tiles"
	Entering a matching tile costs 1 movement instead of the terrain's cost (the river crossing still takes the whole turn), before the unit's half-movement and extra movement cost uniques apply. Only read with the ModConstants switch `terrainMovementPromotionsLikeVp`.

	Example: "Ignores terrain cost in [Farm] tiles"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Half movement in [tileFilter] tiles"
	The mirror of `Double movement in`: the cost of entering a matching tile is doubled unless the unit also has double movement there. Only read with the ModConstants switch `terrainMovementPromotionsLikeVp`.

	Example: "Half movement in [Farm] tiles"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "[amount] extra movement cost in [tileFilter] tiles"
	Entering a matching tile costs this much more, after the unit's other movement cost modifiers. Only the most specific matching kind counts, in VP's order: a Mountain, a Hill, the base terrain, a terrain feature. Only read with the ModConstants switch `terrainMovementPromotionsLikeVp`.

	Example: "[3] extra movement cost in [Farm] tiles"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Gains XP from scouting"
	Each tile the unit newly reveals to its civilization is worth 1 point, a visible resource scoutXpRandomValue / 4, ancient ruins scoutXpRandomValue / 3 and a natural wonder scoutXpRandomValue (ModConstants, 12 as in VP), and every `scoutXpDenominator` points are 1 experience point, scaled by the game speed. City-states and barbarians do not earn it. Only read with the ModConstants switch `scoutingXpLikeVp`.

	Applicable to: Unit

??? example  "[amount] XP when pillaging"
	Experience for pillaging an improvement (not a road), scaled by the game speed; barbarians get none. Also makes the AI pillage (CvUnit::shouldPillage). Only read with the ModConstants switch `pillageXpLikeVp`.

	Example: "[3] XP when pillaging"

	Applicable to: Unit

??? example  "Can be gifted by city-states"
	A militaristic city-state may be given this unit as its unique unit whoever it belongs to, even when no civilization of the game could build it, and is somewhat more likely to be; no civilization but the barbarians can build it. Only read with the ModConstants switch `cityStateGiftUnitFlagsLikeVp`.

	Applicable to: Unit

??? example  "Cannot be gifted by city-states"
	A city-state never gives this unit, neither as its unique unit nor as the military unit it gives its patron. Only read with the ModConstants switch `cityStateGiftUnitFlagsLikeVp`.

	Applicable to: Unit

??? example  "Destroys [cityFilter] cities instead of capturing"
	The unit will destroy [cityFilter] cities instead of capturing them, also allows non-melee units to destroy cities.Capital cities (including city states) are immune to this effect.

	Example: "Destroys [in all cities] cities instead of capturing"

	Applicable to: Unit

??? example  "No movement cost to pillage"
	Applicable to: Global, Unit

??? example  "Can move after attacking"
	Applicable to: Unit

??? example  "Transfer Movement to [mapUnitFilter]"
	Example: "Transfer Movement to [Wounded]"

	Applicable to: Unit

??? example  "Can move immediately once bought"
	Applicable to: Unit

??? example  "May heal outside of friendly territory"
	Applicable to: Global, Unit

??? example  "All healing effects doubled"
	Does not stack

	Applicable to: Global, Unit

??? example  "Heals [amount] damage if it kills a unit"
	Example: "Heals [3] damage if it kills a unit"

	Applicable to: Global, Unit

??? example  "Heals [amount] damage if it kills a non-Barbarian unit"
	Example: "Heals [3] damage if it kills a non-Barbarian unit"

	Applicable to: Unit

??? example  "Takes [amount] damage at turn end in enemy territory"
	Example: "Takes [3] damage at turn end in enemy territory"

	Applicable to: Unit

??? example  "Can only heal by pillaging"
	Applicable to: Global, Unit

??? example  "Unit will heal every turn, even if it performs an action"
	Applicable to: Unit

??? example  "All adjacent units heal [amount] HP when healing"
	Example: "All adjacent units heal [3] HP when healing"

	Applicable to: Unit

??? example  "Units on this tile heal [amount] HP when healing"
	Example: "Units on this tile heal [3] HP when healing"

	Applicable to: Unit

??? example  "[amount] HP healing for [mapUnitFilter] units on [tileFilter] tiles within [amount] tiles"
	Example: "[3] HP healing for [Wounded] units on [Farm] tiles within [3] tiles"

	Applicable to: Unit

??? example  "[amount] HP healing in neutral territory for [mapUnitFilter] units on [tileFilter] tiles within [amount] tiles"
	Example: "[3] HP healing in neutral territory for [Wounded] units on [Farm] tiles within [3] tiles"

	Applicable to: Unit

??? example  "[amount] HP healing in enemy territory for [mapUnitFilter] units on [tileFilter] tiles within [amount] tiles"
	Example: "[3] HP healing in enemy territory for [Wounded] units on [Farm] tiles within [3] tiles"

	Applicable to: Unit

??? example  "No Sight"
	Applicable to: Unit

??? example  "Can see over obstacles"
	Applicable to: Unit

??? example  "Can carry [amount] [mapUnitFilter] units"
	Using several of these uniques with complex overlapping filters is supported.

	Example: "Can carry [3] [Wounded] units"

	Applicable to: Unit

??? example  "Can carry [amount] extra [mapUnitFilter] units"
	For buildings, supports using `Air` for `mapUnitFilter` to increase city air unit capacity.

	Example: "Can carry [3] extra [Wounded] units"

	Applicable to: Building, Unit

??? example  "Cannot be carried by [mapUnitFilter] units"
	Example: "Cannot be carried by [Wounded] units"

	Applicable to: Unit

??? example  "Counts [amount] against the air unit capacity"
	Only read with the ModConstants switch `airBasingLikeVp`, for air units standing in a city untransported; an air unit without it counts 1. A carrier's cargo slots are counted by units and ignore it.

	Example: "Counts [3] against the air unit capacity"

	Applicable to: Unit

??? example  "[amount] air defense"
	The damage this unit (the sum over its own uniques and promotions) or this city (the sum over its buildings) does to an air unit that attacks it, before the attacker's interception damage reduction and a random addition. Only read with the ModConstants switch `airDefenseLikeVp`.

	Example: "[3] air defense"

	Applicable to: Building, Unit

??? example  "[amount]% chance to evade interception"
	An air attacker with it rolls this chance (summed, at most 100) before the interceptor rolls its own, and is not intercepted if it succeeds. Only read with the ModConstants switch `airInterceptionLikeVp`.

	Example: "[3]% chance to evade interception"

	Applicable to: Unit

??? example  "[relativeAmount]% chance to intercept air attacks"
	Example: "[+20]% chance to intercept air attacks"

	Applicable to: Unit

??? example  "Damage taken from interception reduced by [relativeAmount]%"
	Example: "Damage taken from interception reduced by [+20]%"

	Applicable to: Unit

??? example  "[relativeAmount]% Damage when intercepting"
	Example: "[+20]% Damage when intercepting"

	Applicable to: Unit

??? example  "[amount] extra interceptions may be made per turn"
	Example: "[3] extra interceptions may be made per turn"

	Applicable to: Unit

??? example  "Cannot be intercepted"
	Applicable to: Unit

??? example  "Cannot intercept [mapUnitFilter] units"
	Example: "Cannot intercept [Wounded] units"

	Applicable to: Unit

??? example  "[relativeAmount]% Strength when performing Air Sweep"
	Example: "[+20]% Strength when performing Air Sweep"

	Applicable to: Unit

??? example  "[relativeAmount]% maintenance costs"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% maintenance costs"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% Gold cost of upgrading"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% Gold cost of upgrading"

	Applicable to: Global, Unit

??? example  "Earn [amount]% of the damage done to [combatantFilter] units as [stockpile]"
	Example: "Earn [3]% of the damage done to [City] units as [Mana]"

	Applicable to: Global, Unit

??? example  "Upon capturing a city, receive [amount] times its [stat] production as [stockpile] immediately"
	Example: "Upon capturing a city, receive [3] times its [Culture] production as [Mana] immediately"

	Applicable to: Global, Unit

??? example  "Earn [amount]% of killed [mapUnitFilter] unit's [costOrStrength] as [stockpile]"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats.

	Example: "Earn [3]% of killed [Wounded] unit's [Cost] as [Mana]"

	Applicable to: Global, Unit

??? example  "May capture killed [mapUnitFilter] units"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats.

	Example: "May capture killed [Wounded] units"

	Applicable to: Unit

??? example  "Replaced by a random promotion from [promotionList] after combat"
	Example: "Replaced by a random promotion from [Shock I, Drill I] after combat"

	Applicable to: Unit

??? example  "[amount] XP gained from combat"
	Example: "[3] XP gained from combat"

	Applicable to: Global, Unit

??? example  "[relativeAmount]% XP gained from combat"
	Multiple bonuses stack multiplicatively: +50% + +50% = x1.5 * x1.5 = +125%

	Example: "[+20]% XP gained from combat"

	Applicable to: Global, Unit

??? example  "Can be earned through combat"
	Applicable to: Unit

??? example  "[greatPerson] is earned [relativeAmount]% faster"
	Example: "[Great General] is earned [+20]% faster"

	Applicable to: Global, Unit

??? example  "Invisible to others"
	Applicable to: Unit

??? example  "Invisible to non-adjacent units"
	Applicable to: Unit

??? example  "Can see invisible [mapUnitFilter] units"
	Example: "Can see invisible [Wounded] units"

	Applicable to: Unit

??? example  "May upgrade to [unit] through ruins-like effects"
	Example: "May upgrade to [Musketman] through ruins-like effects"

	Applicable to: Unit

??? example  "Can upgrade to [unit]"
	Example: "Can upgrade to [Musketman]"

	Applicable to: Unit

??? example  "Destroys tile improvements when attacking"
	Applicable to: Unit

??? example  "Cannot move"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Double movement in [terrainFilter]"
	Example: "Double movement in [Fresh Water]"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "All tiles cost 1 movement"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "May travel on Water tiles without embarking"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Can pass through impassable tiles"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Ignores terrain cost"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Ignores Zone of Control"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "Rough terrain penalty"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Can enter ice tiles"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "Cannot embark"
	Applicable to: Unit

??? example  "Cannot enter ocean tiles"
	Applicable to: Unit

??? example  "Cannot enter [tileFilter] tiles"
	Example: "Cannot enter [Farm] tiles"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "May enter foreign tiles without open borders"
	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "May enter foreign tiles without open borders, but loses [amount] religious strength each turn it ends there"
	Example: "May enter foreign tiles without open borders, but loses [3] religious strength each turn it ends there"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Unit

??? example  "[nonNegativeAmount] Movement point cost to disembark"
	Example: "[3] Movement point cost to disembark"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "[nonNegativeAmount] Movement point cost to embark"
	Example: "[3] Movement point cost to embark"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	Applicable to: Global, Unit

??? example  "Never appears as a Barbarian unit"
	This unique is automatically hidden from users.

	Applicable to: Unit

??? example  "Religious Unit"
	Applicable to: Unit

??? example  "Spaceship part"
	Applicable to: Building, Unit

??? example  "Takes your religion over the one in their birth city"
	Applicable to: Unit

??? example  "Great Person - [comment]"
	Example: "Great Person - [comment]"

	Applicable to: Unit

??? example  "Is part of Great Person group [comment]"
	Great people in the same group increase teach other's costs when gained. Gaining one will make all others in the same group cost more GPP.

	Example: "Is part of Great Person group [comment]"

	Applicable to: Unit

??? example  "Can excavate archaeological sites"
	The unit can dig up sites if it can also build the improvement, and the AI sends it to them. Needed for what its finds are put to

	Applicable to: Unit

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Shown while unbuilable"
	This unique is automatically hidden from users.

	Applicable to: Building, Unit

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## UnitType uniques
??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Promotion uniques
??? example  "[amount] Influence from diplomatic missions"
	The base influence of a diplomatic mission (the original's `UnitPromotions.DiploMissionInfluence`, summed over the unit's promotions: Emissary 40, Envoy 50, Diplomat 60, Ambassador 70, Great Diplomat 100, the free promotions of buildings 10-15). Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[3] Influence from diplomatic missions"

	Applicable to: Unit, Promotion

??? example  "[relativeAmount]% Influence from diplomatic missions"
	On a unit or a promotion: `UnitPromotions.TradeMissionInfluenceModifier`; on a policy or other global source: `Policies.MissionInfluenceModifier` (Foreign Service 10). Each is its own multiplication, rounded down. Only with the ModConstants `diplomaticMissionsLikeVp`

	Example: "[+20]% Influence from diplomatic missions"

	Applicable to: Global, Unit, Promotion

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "May choose the reward when entering Ancient Ruins"
	Applicable to: Unit, Promotion

??? example  "Requires [promotion] promotion"
	Example: "Requires [Shock I] promotion"

	Applicable to: Promotion

??? example  "Requires at least [amount] attack range"
	Example: "Requires at least [3] attack range"

	Applicable to: Promotion

??? example  "Requires at most [amount] attack range"
	Example: "Requires at most [3] attack range"

	Applicable to: Promotion

??? example  "Only for mounted units"
	Applicable to: Promotion

??? example  "Lost when the unit is gifted"
	A unit that is gifted to another civilization loses this promotion, and gets it back only if its unit type gives it for free. Only read with the ModConstants switch `giftPromotionLossLikeVp`.

	Applicable to: Promotion

??? example  "Not shown on world screen"
	This unique is automatically hidden from users.

	Applicable to: Promotion, Resource

??? example  "Doing so will consume this opportunity to choose a Promotion"
	Applicable to: Promotion

??? example  "This Promotion is free"
	Applicable to: Promotion

??? example  "[relativeAmount]% weight to this choice for AI decisions"
	Example: "[+20]% weight to this choice for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: FounderBelief, FollowerBelief, Building, Promotion, EventChoice

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Terrain uniques
??? example  "[stats]"
	Example: "[+1 Gold, +2 Production]"

	Applicable to: Global, Terrain, Improvement

??? example  "[amount] Sight"
	Example: "[3] Sight"

	Applicable to: Global, Unit, Terrain, Improvement

??? example  "Must be adjacent to [amount] [simpleTerrain] tiles"
	Example: "Must be adjacent to [3] [Elevated] tiles"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Must be adjacent to [amount] to [amount] [simpleTerrain] tiles"
	Example: "Must be adjacent to [3] to [3] [Elevated] tiles"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Must not be on [amount] largest landmasses"
	Example: "Must not be on [3] largest landmasses"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Must be on [amount] largest landmasses"
	Example: "Must be on [3] largest landmasses"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Occurs on latitudes from [amount] to [amount] percent of distance equator to pole"
	Example: "Occurs on latitudes from [3] to [3] percent of distance equator to pole"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Occurs in groups of [amount] to [amount] tiles"
	Example: "Occurs in groups of [3] to [3] tiles"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Neighboring tiles will convert to [baseTerrain/terrainFeature]"
	Supports conditionals that need only a Tile as context and nothing else, like `<with [n]% chance>`, and applies them per neighbor.

	If your mod renames Coast or Lakes, do not use this with one of these as parameter, as the code preventing artifacts won't work.

	Example: "Neighboring tiles will convert to [Grassland]"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Grants [stats] to the first civilization to discover it"
	Example: "Grants [+1 Gold, +2 Production] to the first civilization to discover it"

	Applicable to: Terrain

??? example  "Units ending their turn on this terrain take [amount] damage"
	Example: "Units ending their turn on this terrain take [3] damage"

	Due to performance considerations, this unique is cached, thus conditionals that may change within a turn may not work.

	This unique does not support conditionals.

	Applicable to: Terrain

??? example  "Grants [promotion] ([comment]) to adjacent [mapUnitFilter] units for the rest of the game"
	Example: "Grants [Shock I] ([comment]) to adjacent [Wounded] units for the rest of the game"

	Applicable to: Terrain

??? example  "[amount] Strength for cities built on this terrain"
	Example: "[3] Strength for cities built on this terrain"

	Applicable to: Terrain

??? example  "Provides a one-time bonus of [stats] to the closest city when cut down"
	Example: "Provides a one-time bonus of [+1 Gold, +2 Production] to the closest city when cut down"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	This unique's effect can be modified with &lt;(modified by game progress up to [relativeAmount]%)&gt;

	Applicable to: Terrain

??? example  "Vegetation"
	Applicable to: Terrain, Improvement

??? example  "[stats] while this tile is in your borders"
	Civilization-wide stats for each tile with this terrain inside the borders of one of your cities, whether it is worked or not (the original's in-border happiness of Old Faithful and the Fountain of Youth)

	Example: "[+1 Gold, +2 Production] while this tile is in your borders"

	Applicable to: Terrain

??? example  "Tile provides yield without assigned population"
	Applicable to: Terrain, Improvement

??? example  "Nullifies all other stats this tile provides"
	Applicable to: Terrain

??? example  "Only [improvementFilter] improvements may be built on this tile"
	Example: "Only [All Road] improvements may be built on this tile"

	Applicable to: Terrain

??? example  "Blocks line-of-sight from tiles at same elevation"
	Applicable to: Terrain

??? example  "Has an elevation of [amount] for visibility calculations"
	Example: "Has an elevation of [3] for visibility calculations"

	Applicable to: Terrain

??? example  "Blocks line-of-sight as if [amount] elevation higher"
	Added to the elevation of this tile when it hides the tiles behind it and when it is looked at, but not to the elevation of a unit standing on it or to what counts for the ring just beyond a unit's sight range (the see-through level of a feature in the original, CvPlot::seeThroughLevel). "Blocks line-of-sight from tiles at same elevation" is the same with 1

	Example: "Blocks line-of-sight as if [3] elevation higher"

	Applicable to: Terrain

??? example  "Always Fertility [amount] for Map Generation"
	Example: "Always Fertility [3] for Map Generation"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "[amount] to Fertility for Map Generation"
	Example: "[3] to Fertility for Map Generation"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "A Region is formed with at least [fraction]% [simpleTerrain] tiles, with priority [amount]"
	Example: "A Region is formed with at least [0.5]% [Elevated] tiles, with priority [3]"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "A Region is formed with at least [fraction]% [simpleTerrain] tiles and [simpleTerrain] tiles, with priority [amount]"
	Example: "A Region is formed with at least [0.5]% [Elevated] tiles and [Elevated] tiles, with priority [3]"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "A Region can not contain more [simpleTerrain] tiles than [simpleTerrain] tiles"
	Example: "A Region can not contain more [Elevated] tiles than [Elevated] tiles"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "A Region can not contain more [simpleTerrain] tiles than [fraction]% of its [simpleTerrain] tiles"
	Example: "A Region can not contain more [Elevated] tiles than [0.5]% of its [Elevated] tiles"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "A Region formed by two types needs at least [fraction]% tiles of this type"
	Example: "A Region formed by two types needs at least [0.5]% tiles of this type"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Base Terrain on this tile is not counted for Region determination"
	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Starts in regions of this type receive an extra [resource]"
	Example: "Starts in regions of this type receive an extra [Iron]"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Never receives any resources"
	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Becomes [terrainName] when adjacent to [terrainFilter]"
	Example: "Becomes [Forest] when adjacent to [Fresh Water]"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Considered [terrainQuality] when determining start locations"
	Example: "Considered [Undesirable] when determining start locations"

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Doesn't generate naturally"
	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Occurs at temperature between [fraction] and [fraction] and humidity between [fraction] and [fraction]"
	Example: "Occurs at temperature between [0.5] and [0.5] and humidity between [0.5] and [0.5]"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Occurs in chains at high elevations"
	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Occurs in groups around high elevations"
	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Every [amount] tiles with this terrain will receive a major deposit of a strategic resource."
	Example: "Every [3] tiles with this terrain will receive a major deposit of a strategic resource."

	This unique is automatically hidden from users.

	Applicable to: Terrain

??? example  "Rare feature"
	Applicable to: Terrain

??? example  "[amount]% Chance to be destroyed by nukes"
	Example: "[3]% Chance to be destroyed by nukes"

	Applicable to: Terrain

??? example  "Fresh water"
	Applicable to: Terrain

??? example  "Rough terrain"
	Applicable to: Terrain

??? example  "Coastal Water"
	Marks water tiles as Coast - all other water tiles count as Ocean. These distinctions are relevant e.g. for map generator or the ability to navigate here.

	Note that terrain filters do not recognize this distinction, filtering for "Coast" or "Ocean" will only look for a terrain of that name.

	Also note that for compatibility reasons, terrains named "Coast" are assuned to have this Unique even if it's missing. This may be removed in a future version.

	A tile marked this way marks adjacent land tiles as "Coastal", so they fulfill the terrain filter, and cities built there can build ships, Harbor, etc.

	Applicable to: Terrain

??? example  "Excluded from map editor"
	This unique is automatically hidden from users.

	Applicable to: Nation, Terrain, Improvement, Resource

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Suppress warning [validationWarning]"
	Allows suppressing specific validation warnings. Errors, deprecation warnings, or warnings about untyped and non-filtering uniques should be heeded, not suppressed, and are therefore not accepted. Note that this can be used in ModOptions, in the uniques a warning is about, or as modifier on the unique triggering a warning - but you still need to be specific. Even in the modifier case you will need to specify a sufficiently selective portion of the warning text as parameter.

	Example: "Suppress warning [Tinman is supposed to automatically upgrade at tech Clockwork, and therefore Servos for its upgrade Mecha may not yet be researched! -or- *is supposed to automatically upgrade*]"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable, Terrain, Speed, ModOptions, MetaModifier

## Improvement uniques
??? example  "[stats]"
	Example: "[+1 Gold, +2 Production]"

	Applicable to: Global, Terrain, Improvement

??? example  "[stats] for every era since the site of this improvement was formed"
	Counts the eras the civilization has gone through since the era of what happened on this tile, see archaeology. Nothing if it is the same era or the tile has no history

	Example: "[+1 Gold, +2 Production] for every era since the site of this improvement was formed"

	Applicable to: Improvement

??? example  "Consumes [amount] [resource]"
	Example: "Consumes [3] [Iron]"

	Applicable to: Building, Unit, Improvement

??? example  "Provides [amount] [resource]"
	Example: "Provides [3] [Iron]"

	Applicable to: Global, FollowerBelief, Improvement

??? example  "Costs [amount] [stockpiledResource]"
	These resources are removed *when work begins* on the construction. Do not confuse with "costs [amount] [stockpiledResource]" (lowercase 'c'), the Unit Action Modifier.

	Example: "Costs [3] [Mana]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Building, Unit, Improvement

??? example  "Unbuildable"
	Blocks from being built, possibly by conditional. However it can still appear in the menu and be bought with other means such as Gold or Faith

	Applicable to: Building, Unit, Improvement

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Must be next to [tileFilter]"
	Example: "Must be next to [Farm]"

	Applicable to: Building, Improvement

??? example  "Obsolete with [tech]"
	Example: "Obsolete with [Agriculture]"

	Applicable to: Building, Improvement, Resource

??? example  "[amount] Sight"
	Example: "[3] Sight"

	Applicable to: Global, Unit, Terrain, Improvement

??? example  "[stats] if a trade route crosses this tile on a [improvementName]"
	Vox Populi's `Improvement_RouteYieldChanges`: the improvement gives the stats while a trade route of any civilization passes over the tile along a road or a railroad (name the route's improvement: `Road` or `Railroad`; the railroad's row is read instead of the road's from the fourth era of the civilization the yield is for, V:CvPlot.cpp:10733-10742). What the original also gives for a road or railroad that connects a city to the capital (V:CvPlot.cpp:10744-10760) is not read here

	Example: "[+1 Gold, +2 Production] if a trade route crosses this tile on a [Village]"

	Applicable to: Improvement

??? example  "Vegetation"
	Applicable to: Terrain, Improvement

??? example  "Tile provides yield without assigned population"
	Applicable to: Terrain, Improvement

??? example  "Excluded from map editor"
	This unique is automatically hidden from users.

	Applicable to: Nation, Terrain, Improvement, Resource

??? example  "Can also be built on tiles adjacent to fresh water"
	Applicable to: Improvement

??? example  "[stats] from [tileFilter] tiles"
	Example: "[+1 Gold, +2 Production] from [Farm] tiles"

	Applicable to: Improvement

??? example  "[stats] for each adjacent [tileFilter]"
	Example: "[+1 Gold, +2 Production] for each adjacent [Farm]"

	Applicable to: Improvement

??? example  "[stats] from every [positiveAmount] adjacent [tileFilter]"
	Each matching adjacent tile counts as the stats divided by the number; all such uniques of the improvement are added up and the sum is cut off at the whole number of each stat (the original's `Improvement_YieldPerXAdjacentImprovement` and `..._YieldPerXAdjacentTerrain`, CvPlot::ComputeFractionalYieldFromAdjacentImprovement)

	Example: "[+1 Gold, +2 Production] from every [3] adjacent [Farm]"

	Applicable to: Improvement

??? example  "Ensures a minimum tile yield of [stats]"
	Example: "Ensures a minimum tile yield of [+1 Gold, +2 Production]"

	Applicable to: Improvement

??? example  "Can be built outside your borders"
	Applicable to: Improvement

??? example  "Can only be built in City-State territory"
	Applicable to: Improvement

??? example  "Can be built just outside your borders"
	Applicable to: Improvement

??? example  "Can only be built on [tileFilter] tiles"
	Example: "Can only be built on [Farm] tiles"

	Applicable to: Improvement

??? example  "Cannot be built on [tileFilter] tiles"
	Example: "Cannot be built on [Farm] tiles"

	Applicable to: Improvement

??? example  "Can only be built to improve a resource"
	Applicable to: Improvement

??? example  "Can be built on tiles with any resource"
	Exempts the improvement from the rule of `improvementsOnResourcesLikeCivV` (Improvements.BuildableOnResources of Civ V)

	Applicable to: Improvement

??? example  "Provides [positiveAmount] untradable copies of each Luxury resource connected by this City-State to the builder"
	Example: "Provides [3] untradable copies of each Luxury resource connected by this City-State to the builder"

	Applicable to: Improvement

??? example  "Does not need removal of [terrainFeature]"
	Example: "Does not need removal of [Hill]"

	Applicable to: Improvement

??? example  "Removes removable features when built"
	Applicable to: Improvement

??? example  "Gives a defensive bonus of [relativeAmount]%"
	Does not accept unit-based conditionals

	Example: "Gives a defensive bonus of [+20]%"

	Applicable to: Improvement

??? example  "Costs [amount] [stat] per turn when in your territory"
	Example: "Costs [3] [Culture] per turn when in your territory"

	Applicable to: Improvement

??? example  "Costs [amount] [stat] per turn"
	Example: "Costs [3] [Culture] per turn"

	Applicable to: Improvement

??? example  "Adjacent enemy units ending their turn take [amount] damage"
	Example: "Adjacent enemy units ending their turn take [3] damage"

	Applicable to: Improvement

??? example  "Great Improvement"
	Applicable to: Improvement

??? example  "Provides a random bonus when entered"
	Applicable to: Improvement

??? example  "Marks a barbarian camp"
	When several barbarian camp improvements are available, each new camp chooses one randomly.

	This unique is automatically hidden from users.

	Applicable to: Improvement

??? example  "Unpillagable"
	Applicable to: Improvement

??? example  "Pillaging this improvement yields approximately [stats]"
	Example: "Pillaging this improvement yields approximately [+1 Gold, +2 Production]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	This unique's effect can be modified with &lt;(modified by game progress up to [relativeAmount]%)&gt;

	Applicable to: Improvement

??? example  "Pillaging this improvement yields [stats]"
	Example: "Pillaging this improvement yields [+1 Gold, +2 Production]"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	This unique's effect can be modified with &lt;(modified by game progress up to [relativeAmount]%)&gt;

	Applicable to: Improvement

??? example  "Destroyed when pillaged"
	Applicable to: Improvement

??? example  "Irremovable"
	Applicable to: Improvement

??? example  "Will not be replaced by automated units"
	Applicable to: Improvement

??? example  "Improves [resourceFilter] resource in this tile"
	This is offered as an alternative to the improvedBy field of a resource. The result will be cached within the resource definition when loading a game, without knowledge about terrain, cities, civs, units or time. Therefore, most conditionals will not work, only those **not** dependent on game state.

	Example: "Improves [Strategic] resource in this tile"

	This unique does not support conditionals.

	Applicable to: Improvement

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Resource uniques
??? example  "Obsolete with [tech]"
	Example: "Obsolete with [Agriculture]"

	Applicable to: Building, Improvement, Resource

??? example  "[stats] from each tile with this resource"
	Only in `globalMonopolyUniques` and `strategicMonopolyUniques`: added to every tile of the civilization that has the resource, while it has the monopoly (Vox Populi's `Resource_YieldChangeFromMonopoly`; `MonopolyFlatBonus` is added to every positive stat)

	Example: "[+1 Gold, +2 Production] from each tile with this resource"

	Applicable to: Resource

??? example  "[amount] Happiness from a global monopoly"
	Only in `globalMonopolyUniques`: local happiness that every copy-owning civilization with the monopoly gets (the original's `MonopolyHappiness`; `MonopolyFlatBonus` is added)

	Example: "[3] Happiness from a global monopoly"

	Applicable to: Resource

??? example  "[relativeAmount]% Production of [baseUnitFilter] units in cities with this resource"
	Vox Populi's `Resource_UnitCombatProductionCostModifiersLocal` (option `RESOURCES_PRODUCTION_COST_MODIFIERS`: Ivory against Siege, V:CvCity.cpp:11422-11452). The amount is the change of the production cost, as in the original: -15 makes matching units 15% cheaper in a city that has the resource (whole numbers, after the other cost rules). The modifier `<before the [era]>` is compared with the era of the unit's technology (the current era of the civilization for a unit without one), not with the era of the civilization

	Example: "[+20]% Production of [Melee] units in cities with this resource"

	Applicable to: Resource

??? example  "[relativeAmount]% Production of Wonders in cities with this resource"
	Vox Populi's `Resources.WonderProductionMod` (Marble, Stone), until `WonderProductionModObsoleteEra` (V:CvCity.cpp:18351-18416): added to the production of a wonder in a city that has the resource. The modifier `<before the [era]>` is compared with the era of the wonder's technology (no limit for a wonder without one), not with the era of the civilization

	Example: "[+20]% Production of Wonders in cities with this resource"

	Applicable to: Resource

??? example  "Must not be on [amount] largest landmasses"
	Example: "Must not be on [3] largest landmasses"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Must be on [amount] largest landmasses"
	Example: "Must be on [3] largest landmasses"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Doesn't generate naturally"
	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Occurs at temperature between [fraction] and [fraction] and humidity between [fraction] and [fraction]"
	Example: "Occurs at temperature between [0.5] and [0.5] and humidity between [0.5] and [0.5]"

	This unique is automatically hidden from users.

	Applicable to: Terrain, Resource

??? example  "Excluded from map editor"
	This unique is automatically hidden from users.

	Applicable to: Nation, Terrain, Improvement, Resource

??? example  "Deposits in [tileFilter] tiles always provide [amount] resources"
	Example: "Deposits in [Farm] tiles always provide [3] resources"

	Applicable to: Resource

??? example  "Can only be created by Mercantile City-States"
	Applicable to: Resource

??? example  "Unique Luxury for [civFilter]"
	Example: "Unique Luxury for [City-States]"

	Applicable to: Resource

??? example  "Stockpiled"
	This resource is accumulated each turn, rather than having a set of producers and consumers at a given moment.The current stockpiled amount can be affected with trigger uniques.

	Applicable to: Resource

??? example  "City-level resource"
	This resource is calculated on a per-city level rather than a per-civ level

	Applicable to: Resource

??? example  "Cannot be traded"
	Applicable to: Resource

??? example  "Not shown on world screen"
	This unique is automatically hidden from users.

	Applicable to: Promotion, Resource

??? example  "Generated with weight [amount]"
	The probability for this resource to be chosen is (this resource weight) / (sum weight of all eligible resources). Resources without a unique are given weight `1`

	Example: "Generated with weight [3]"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Minor deposits generated with weight [amount]"
	The probability for this resource to be chosen is (this resource weight) / (sum weight of all eligible resources). Resources without a unique are not generated as minor deposits.

	Example: "Minor deposits generated with weight [3]"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Generated near City States with weight [amount]"
	The probability for this resource to be chosen is (this resource weight) / (sum weight of all eligible resources). Only assignable to luxuries, resources without a unique are given weight `1`

	Example: "Generated near City States with weight [3]"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Special placement during map generation"
	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Generated on every [amount] tiles"
	Example: "Generated on every [3] tiles"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Guaranteed with Strategic Balance resource option"
	Applicable to: Resource

??? example  "AI will sell at [amount] Gold"
	Example: "AI will sell at [3] Gold"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "AI will buy at [amount] Gold"
	Example: "AI will buy at [3] Gold"

	This unique is automatically hidden from users.

	Applicable to: Resource

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Ruins uniques
??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Free [unit] found in the ruins"
	Example: "Free [Musketman] found in the ruins"

	Applicable to: Ruins

??? example  "From a randomly chosen tile [positiveAmount] tiles away from the ruins, reveal tiles up to [positiveAmount] tiles away with [positiveAmount]% chance"
	Example: "From a randomly chosen tile [3] tiles away from the ruins, reveal tiles up to [3] tiles away with [3]% chance"

	Applicable to: Ruins

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Speed uniques
!!! note ""

    Speed uniques will be treated as part of GlobalUniques for the Speed selected in a game

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Suppress warning [validationWarning]"
	Allows suppressing specific validation warnings. Errors, deprecation warnings, or warnings about untyped and non-filtering uniques should be heeded, not suppressed, and are therefore not accepted. Note that this can be used in ModOptions, in the uniques a warning is about, or as modifier on the unique triggering a warning - but you still need to be specific. Even in the modifier case you will need to specify a sufficiently selective portion of the warning text as parameter.

	Example: "Suppress warning [Tinman is supposed to automatically upgrade at tech Clockwork, and therefore Servos for its upgrade Mecha may not yet be researched! -or- *is supposed to automatically upgrade*]"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable, Terrain, Speed, ModOptions, MetaModifier

## Difficulty uniques
!!! note ""

    Difficulty uniques will be treated as part of GlobalUniques for the Difficulty selected in a game

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## CityState uniques
??? example  "Provides military units every ≈[positiveAmount] turns"
	Example: "Provides military units every ≈[3] turns"

	Applicable to: CityState

??? example  "Provides a unique luxury"
	Applicable to: CityState

??? example  "Start bias [terrainFilter]"
	Same effect as a Nation startBias field entry. Merged with the startBias field and, for city-states, with matching uniques on their CityStateType. Conditionals run against GameInfo only during map generation / start placement (no Civilization — it may be only partially initialized). Do not use conditionals that require tiles, cities, or units.

	Example: "Start bias [Fresh Water]"

	Applicable to: Nation, CityState

## ModOptions uniques
??? example  "Diplomatic relationships cannot change"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Can convert gold to science with sliders"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Allow City States to spawn with additional units"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Can trade civilization introductions for [positiveAmount] Gold"
	Example: "Can trade civilization introductions for [3] Gold"

	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Disable religion"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Can only start games from the starting era"
	In this case, 'starting era' means the first defined Era in the entire ruleset.

	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Allow raze capital"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Allow raze holy city"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "City-states search for first city location"
	By default, city-state settlers with no cities yet found on their current tile when valid (predetermined map-gen / editor start). With this unique they use the same nearby-site search as major civs.

	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Suppress warning [validationWarning]"
	Allows suppressing specific validation warnings. Errors, deprecation warnings, or warnings about untyped and non-filtering uniques should be heeded, not suppressed, and are therefore not accepted. Note that this can be used in ModOptions, in the uniques a warning is about, or as modifier on the unique triggering a warning - but you still need to be specific. Even in the modifier case you will need to specify a sufficiently selective portion of the warning text as parameter.

	Example: "Suppress warning [Tinman is supposed to automatically upgrade at tech Clockwork, and therefore Servos for its upgrade Mecha may not yet be researched! -or- *is supposed to automatically upgrade*]"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable, Terrain, Speed, ModOptions, MetaModifier

??? example  "Mod is incompatible with [modFilter]"
	Specifies that your Mod is incompatible with another. Always treated symmetrically, and cannot be overridden by the Mod you are declaring as incompatible.

	Example: "Mod is incompatible with [DeCiv Redux]"

	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Mod requires [modFilter]"
	Specifies that your Extension Mod is only available if any other Mod matching the filter is active.

	Multiple copies of this Unique cannot be used to specify alternatives, they work as 'and' logic. If you need alternates and wildcards can't filter them well enough, please open an issue.

	Example: "Mod requires [DeCiv Redux]"

	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Should only be used as permanent audiovisual mod"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Can be used as permanent audiovisual mod"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Cannot be used as permanent audiovisual mod"
	This unique does not support conditionals.

	Applicable to: ModOptions

??? example  "Mod preselects map [comment]"
	Only meaningful for Mods containing several maps. When this mod is selected on the new game screen's custom maps mod dropdown, the named map will be selected on the map dropdown. Also disables selection by recently modified. Case insensitive.

	Example: "Mod preselects map [comment]"

	This unique does not support conditionals.

	Applicable to: ModOptions

## Event uniques
??? example  "Only the first available choice is offered"
	When triggered, only the first choice (in declaration order) whose conditionals apply is offered. This makes the event equivalent to a `when`/`case` statement.

	Applicable to: Event

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

## EventChoice uniques
??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "[relativeAmount]% weight to this choice for AI decisions"
	Example: "[+20]% weight to this choice for AI decisions"

	This unique is automatically hidden from users.

	Applicable to: FounderBelief, FollowerBelief, Building, Promotion, EventChoice

??? example  "Will not be displayed in Civilopedia"
	Supports conditionals that need only a Game as context and nothing else.

	Most conditionals require at least a Civilization and will **not** work.

	Note that when Civilopedia runs from main menu, conditionals will be ignored.

	This unique is automatically hidden from users.

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

??? example  "Comment [comment]"
	Allows displaying arbitrary text in a Unique listing. Only the text within the '[]' brackets will be displayed, the rest serves to allow Ruleset validation to recognize the intent.

	Example: "Comment [comment]"

	Applicable to: Nation, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, UnitType, Promotion, Terrain, Improvement, Resource, Ruins, Speed, Difficulty, EventChoice

## Victory uniques
!!! note ""

    Uniques that determine whether a victory can be achieved, and by whom

??? example  "Only available"
	Meant to be used together with conditionals, like "Only available &lt;after adopting [policy]&gt; &lt;while the empire is happy&gt;". Only allows Building when ALL conditionals are met. Will also block Upgrade and Transform actions. See also CanOnlyBeBuiltWhen

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Unavailable"
	Meant to be used together with conditionals, like "Unavailable &lt;after generating a Great Prophet&gt;".

	Applicable to: Resolution, Tech, Policy, FounderBelief, FollowerBelief, Building, Unit, Promotion, Improvement, Ruins, Event, EventChoice, Victory

??? example  "Will not be chosen for new games"
	Unconditional - it is evaluated while a game is being set up, where there is nothing to evaluate conditionals against.

	Applicable to: Nation, Victory

## Conditional uniques
!!! note ""

    Modifiers that can be added to other uniques to limit when they will be active

??? example  "&lt;every [positiveAmount] turns&gt;"
	Example: "&lt;every [3] turns&gt;"

	Applicable to: Conditional

??? example  "&lt;before turn number [nonNegativeAmount]&gt;"
	Example: "&lt;before turn number [3]&gt;"

	Applicable to: Conditional

??? example  "&lt;after turn number [nonNegativeAmount]&gt;"
	Example: "&lt;after turn number [3]&gt;"

	Applicable to: Conditional

??? example  "&lt;on [speed] game speed&gt;"
	Example: "&lt;on [Quick] game speed&gt;"

	Applicable to: Conditional

??? example  "&lt;on [difficulty] difficulty&gt;"
	Example: "&lt;on [Prince] difficulty&gt;"

	Applicable to: Conditional

??? example  "&lt;on [difficulty] difficulty or higher&gt;"
	Example: "&lt;on [Prince] difficulty or higher&gt;"

	Applicable to: Conditional

??? example  "&lt;on [difficulty] difficulty or lower&gt;"
	Example: "&lt;on [Prince] difficulty or lower&gt;"

	Applicable to: Conditional

??? example  "&lt;when [victoryType] Victory is enabled&gt;"
	Example: "&lt;when [Domination] Victory is enabled&gt;"

	Applicable to: Conditional

??? example  "&lt;when [victoryType] Victory is disabled&gt;"
	Example: "&lt;when [Domination] Victory is disabled&gt;"

	Applicable to: Conditional

??? example  "&lt;when religion is enabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when religion is disabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when espionage is enabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when espionage is disabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when nuclear weapons are enabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when nuclear weapons are disabled&gt;"
	Applicable to: Conditional

??? example  "&lt;when the [resolution] resolution is in effect&gt;"
	True while the World Congress has the resolution enacted

	Example: "&lt;when the [Standing Army Tax] resolution is in effect&gt;"

	Applicable to: Conditional

??? example  "&lt;with [nonNegativeAmount]% chance&gt;"
	Example: "&lt;with [3]% chance&gt;"

	Applicable to: Conditional

??? example  "&lt;if tutorials are enabled&gt;"
	This unique is automatically hidden from users.

	Applicable to: Conditional

??? example  "&lt;if tutorial [comment] is completed&gt;"
	Example: "&lt;if tutorial [comment] is completed&gt;"

	This unique is automatically hidden from users.

	Applicable to: Conditional

??? example  "&lt;for [civFilter] Civilizations&gt;"
	Example: "&lt;for [City-States] Civilizations&gt;"

	Applicable to: Conditional

??? example  "&lt;when at war&gt;"
	Applicable to: Conditional

??? example  "&lt;when not at war&gt;"
	Applicable to: Conditional

??? example  "&lt;during a Golden Age&gt;"
	Applicable to: Conditional

??? example  "&lt;when not in a Golden Age&gt;"
	Applicable to: Conditional

??? example  "&lt;during We Love The King Day&gt;"
	Applicable to: Conditional

??? example  "&lt;while the empire is happy&gt;"
	Applicable to: Conditional

??? example  "&lt;during the [era]&gt;"
	Example: "&lt;during the [Ancient era]&gt;"

	Applicable to: Conditional

??? example  "&lt;before the [era]&gt;"
	Example: "&lt;before the [Ancient era]&gt;"

	Applicable to: Conditional

??? example  "&lt;starting from the [era]&gt;"
	Example: "&lt;starting from the [Ancient era]&gt;"

	Applicable to: Conditional

??? example  "&lt;if starting in the [era]&gt;"
	Example: "&lt;if starting in the [Ancient era]&gt;"

	Applicable to: Conditional

??? example  "&lt;if no other Civilization has researched this&gt;"
	Applicable to: Conditional

??? example  "&lt;after discovering [techFilter]&gt;"
	Example: "&lt;after discovering [Agriculture]&gt;"

	Applicable to: Conditional

??? example  "&lt;before discovering [techFilter]&gt;"
	Example: "&lt;before discovering [Agriculture]&gt;"

	Applicable to: Conditional

??? example  "&lt;while researching [techFilter]&gt;"
	This condition is fulfilled while the technology is actively being researched (it is the one research points are added to)

	Example: "&lt;while researching [Agriculture]&gt;"

	Applicable to: Conditional

??? example  "&lt;if no other Civilization has adopted this&gt;"
	Applicable to: Conditional

??? example  "&lt;if no Civilization has adopted [policy/belief]&gt;"
	Example: "&lt;if no Civilization has adopted [Oligarchy]&gt;"

	Applicable to: Conditional

??? example  "&lt;after adopting [policy/belief]&gt;"
	Example: "&lt;after adopting [Oligarchy]&gt;"

	Applicable to: Conditional

??? example  "&lt;before adopting [policy/belief]&gt;"
	Example: "&lt;before adopting [Oligarchy]&gt;"

	Applicable to: Conditional

??? example  "&lt;before founding a Pantheon&gt;"
	Applicable to: Conditional

??? example  "&lt;after founding a Pantheon&gt;"
	Applicable to: Conditional

??? example  "&lt;before founding a religion&gt;"
	Applicable to: Conditional

??? example  "&lt;after founding a religion&gt;"
	Applicable to: Conditional

??? example  "&lt;before enhancing a religion&gt;"
	Applicable to: Conditional

??? example  "&lt;after enhancing a religion&gt;"
	Applicable to: Conditional

??? example  "&lt;after generating a Great Prophet&gt;"
	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is constructed&gt;"
	Example: "&lt;if [Culture] is constructed&gt;"

	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is not constructed&gt;"
	Example: "&lt;if [Culture] is not constructed&gt;"

	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is constructed in all [cityFilter] cities&gt;"
	Example: "&lt;if [Culture] is constructed in all [in all cities] cities&gt;"

	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is constructed in at least [positiveAmount] of [cityFilter] cities&gt;"
	Example: "&lt;if [Culture] is constructed in at least [3] of [in all cities] cities&gt;"

	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is constructed by anybody&gt;"
	Example: "&lt;if [Culture] is constructed by anybody&gt;"

	Applicable to: Conditional

??? example  "&lt;if [buildingFilter] is not constructed by anybody&gt;"
	Example: "&lt;if [Culture] is not constructed by anybody&gt;"

	Applicable to: Conditional

??? example  "&lt;with [resource]&gt;"
	Example: "&lt;with [Iron]&gt;"

	Applicable to: Conditional

??? example  "&lt;without [resource]&gt;"
	Example: "&lt;without [Iron]&gt;"

	Applicable to: Conditional

??? example  "&lt;with a global monopoly of [resource]&gt;"
	True while the civilization has a global monopoly of the resource (more than half of the copies on the map for a luxury, V:CvPlayer.cpp:39139-39232)

	Example: "&lt;with a global monopoly of [Iron]&gt;"

	Applicable to: Conditional

??? example  "&lt;with a strategic monopoly of [resource]&gt;"
	True while the civilization has a strategic monopoly of the resource (more than a quarter of the copies on the map)

	Example: "&lt;with a strategic monopoly of [Iron]&gt;"

	Applicable to: Conditional

??? example  "&lt;on tiles of trade routes and their cities&gt;"
	True for a tile or city that the path of a trade route of any civilization passes over (Vox Populi's Inca, `TRAIT_GREAT_ANDEAN_ROAD`, V:CvPlot.cpp:10955-10975: IsTradeUnitRoute; the original also counts tiles of a road that connects a city to the capital, which is not read here)

	Applicable to: Conditional

??? example  "&lt;when above [amount] [stat/resource]&gt;"
	Example: "&lt;when above [3] [Culture]&gt;"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Conditional

??? example  "&lt;when below [amount] [stat/resource]&gt;"
	Example: "&lt;when below [3] [Culture]&gt;"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Conditional

??? example  "&lt;when between [amount] and [amount] [stat/resource]&gt;"
	'Between' is inclusive - so 'between 1 and 5' includes 1 and 5.

	Example: "&lt;when between [3] and [3] [Culture]&gt;"

	This unique's effect can be modified with &lt;(modified by game speed)&gt;

	Applicable to: Conditional

??? example  "&lt;in this city&gt;"
	Applicable to: Conditional

??? example  "&lt;in [cityFilter] cities&gt;"
	Example: "&lt;in [in all cities] cities&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities connected to the capital&gt;"
	Applicable to: Conditional

??? example  "&lt;in cities with a [religionFilter] religion&gt;"
	Example: "&lt;in cities with a [major] religion&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities not following a [religionFilter] religion&gt;"
	Example: "&lt;in cities not following a [major] religion&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities with a major religion&gt;"
	Applicable to: Conditional

??? example  "&lt;in cities with an enhanced religion&gt;"
	Applicable to: Conditional

??? example  "&lt;in cities following our religion&gt;"
	Applicable to: Conditional

??? example  "&lt;in cities with a [buildingFilter]&gt;"
	Example: "&lt;in cities with a [Culture]&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities without a [buildingFilter]&gt;"
	Example: "&lt;in cities without a [Culture]&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities with at least [positiveAmount] [populationFilter]&gt;"
	Example: "&lt;in cities with at least [3] [Followers of this Religion]&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities with [nonNegativeAmount] [populationFilter]&gt;"
	Example: "&lt;in cities with [3] [Followers of this Religion]&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities with between [amount] and [amount] [populationFilter]&gt;"
	'Between' is inclusive - so 'between 1 and 5' includes 1 and 5.

	Example: "&lt;in cities with between [3] and [3] [Followers of this Religion]&gt;"

	Applicable to: Conditional

??? example  "&lt;in cities with less than [amount] [populationFilter]&gt;"
	Example: "&lt;in cities with less than [3] [Followers of this Religion]&gt;"

	Applicable to: Conditional

??? example  "&lt;with a garrison&gt;"
	Applicable to: Conditional

??? example  "&lt;for [mapUnitFilter] units&gt;"
	Example: "&lt;for [Wounded] units&gt;"

	Applicable to: Conditional

??? example  "&lt;when [mapUnitFilter]&gt;"
	Example: "&lt;when [Wounded]&gt;"

	Applicable to: Conditional

??? example  "&lt;for units with [promotion]&gt;"
	Also applies to units with temporary status

	Example: "&lt;for units with [Shock I]&gt;"

	Applicable to: Conditional

??? example  "&lt;for units without [promotion]&gt;"
	Also applies to units with temporary status

	Example: "&lt;for units without [Shock I]&gt;"

	Applicable to: Conditional

??? example  "&lt;vs cities&gt;"
	Applicable to: Conditional

??? example  "&lt;vs [mapUnitFilter] units&gt;"
	Example: "&lt;vs [Wounded] units&gt;"

	Applicable to: Conditional

??? example  "&lt;vs [combatantFilter]&gt;"
	Example: "&lt;vs [City]&gt;"

	Applicable to: Conditional

??? example  "&lt;when fighting units from a Civilization with more Cities than you&gt;"
	Applicable to: Conditional

??? example  "&lt;when attacking&gt;"
	Applicable to: Conditional

??? example  "&lt;when defending&gt;"
	Applicable to: Conditional

??? example  "&lt;when making a ranged attack&gt;"
	True while a unit that has a ranged strength attacks (a unit, a city, or an interception by an air unit). Not true for a city's own strike or while defending.

	Applicable to: Conditional

??? example  "&lt;vs fully healed units&gt;"
	True while a unit attacks a unit that has all its hit points. Never true against a city or while defending.

	Applicable to: Conditional

??? example  "&lt;vs units above half health&gt;"
	True while a unit attacks a unit whose lost hit points are below half of its maximum (rounded down for a melee attacker, up for a ranged one). Never true against a city or while defending.

	Applicable to: Conditional

??? example  "&lt;vs units at or below half health&gt;"
	True while a unit attacks a unit that is not above half health (see `vs units above half health`). Never true against a city or while defending.

	Applicable to: Conditional

??? example  "&lt;when fighting in [tileFilter] tiles&gt;"
	Example: "&lt;when fighting in [Farm] tiles&gt;"

	Applicable to: Conditional

??? example  "&lt;on foreign continents&gt;"
	Applicable to: Conditional

??? example  "&lt;when adjacent to a [mapUnitFilter] unit&gt;"
	Example: "&lt;when adjacent to a [Wounded] unit&gt;"

	Applicable to: Conditional

??? example  "&lt;when above [positiveAmount] HP&gt;"
	Example: "&lt;when above [3] HP&gt;"

	Applicable to: Conditional

??? example  "&lt;when below [positiveAmount] HP&gt;"
	Example: "&lt;when below [3] HP&gt;"

	Applicable to: Conditional

??? example  "&lt;when below [positiveAmount] movement&gt;"
	Example: "&lt;when below [3] movement&gt;"

	Applicable to: Conditional

??? example  "&lt;when above [nonNegativeAmount] movement&gt;"
	Example: "&lt;when above [3] movement&gt;"

	Applicable to: Conditional

??? example  "&lt;if it hasn't used other actions yet&gt;"
	Applicable to: Conditional

??? example  "&lt;when stacked with a [mapUnitFilter] unit&gt;"
	Example: "&lt;when stacked with a [Wounded] unit&gt;"

	Applicable to: Conditional

??? example  "&lt;when not stacked with a [mapUnitFilter] unit&gt;"
	Example: "&lt;when not stacked with a [Wounded] unit&gt;"

	Applicable to: Conditional

??? example  "&lt;with [nonNegativeAmount] to [nonNegativeAmount] neighboring [tileFilter] tiles&gt;"
	Example: "&lt;with [3] to [3] neighboring [Farm] tiles&gt;"

	Applicable to: Conditional

??? example  "&lt;in [tileFilter] tiles&gt;"
	Example: "&lt;in [Farm] tiles&gt;"

	Applicable to: Conditional

??? example  "&lt;in tiles without [tileFilter]&gt;"
	Example: "&lt;in tiles without [Farm]&gt;"

	Applicable to: Conditional

??? example  "&lt;within [positiveAmount] tiles of a [tileFilter]&gt;"
	Example: "&lt;within [3] tiles of a [Farm]&gt;"

	Applicable to: Conditional

??? example  "&lt;in tiles adjacent to [tileFilter] tiles&gt;"
	Example: "&lt;in tiles adjacent to [Farm] tiles&gt;"

	Applicable to: Conditional

??? example  "&lt;in tiles not adjacent to [tileFilter] tiles&gt;"
	Example: "&lt;in tiles not adjacent to [Farm] tiles&gt;"

	Applicable to: Conditional

??? example  "&lt;on water maps&gt;"
	Applicable to: Conditional

??? example  "&lt;in [regionType] Regions&gt;"
	Example: "&lt;in [Hybrid] Regions&gt;"

	Applicable to: Conditional

??? example  "&lt;in all except [regionType] Regions&gt;"
	Example: "&lt;in all except [Hybrid] Regions&gt;"

	Applicable to: Conditional

??? example  "&lt;when number of [countable] is equal to [countable]&gt;"
	Example: "&lt;when number of [1000] is equal to [1000]&gt;"

	Applicable to: Conditional

??? example  "&lt;when number of [countable] is different than [countable]&gt;"
	Example: "&lt;when number of [1000] is different than [1000]&gt;"

	Applicable to: Conditional

??? example  "&lt;when number of [countable] is more than [countable]&gt;"
	Example: "&lt;when number of [1000] is more than [1000]&gt;"

	Applicable to: Conditional

??? example  "&lt;when number of [countable] is less than [countable]&gt;"
	Example: "&lt;when number of [1000] is less than [1000]&gt;"

	Applicable to: Conditional

??? example  "&lt;when number of [countable] is between [countable] and [countable]&gt;"
	'Between' is inclusive - so 'between 1 and 5' includes 1 and 5.

	Example: "&lt;when number of [1000] is between [1000] and [1000]&gt;"

	Applicable to: Conditional

??? example  "&lt;when carried by [mapUnitFilter] units&gt;"
	Example: "&lt;when carried by [Wounded] units&gt;"

	Applicable to: Conditional

??? example  "&lt;if [modFilter] is enabled&gt;"
	Example: "&lt;if [DeCiv Redux] is enabled&gt;"

	Applicable to: Conditional

??? example  "&lt;if [modFilter] is not enabled&gt;"
	Example: "&lt;if [DeCiv Redux] is not enabled&gt;"

	Applicable to: Conditional

## TriggerCondition uniques
!!! note ""

    Special conditionals that can be added to Triggerable uniques, to make them activate upon specific actions.

??? example  "&lt;upon discovering [techFilter] technology&gt;"
	Example: "&lt;upon discovering [Agriculture] technology&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon entering the [era]&gt;"
	Example: "&lt;upon entering the [Ancient era]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon entering a new era&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon adopting [policy/belief]&gt;"
	Example: "&lt;upon adopting [Oligarchy]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon declaring war on [civFilter] Civilizations&gt;"
	Example: "&lt;upon declaring war on [City-States] Civilizations&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon being declared war on by [civFilter] Civilizations&gt;"
	Example: "&lt;upon being declared war on by [City-States] Civilizations&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon entering a war with [civFilter] Civilizations&gt;"
	Example: "&lt;upon entering a war with [City-States] Civilizations&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon signing a peace treaty with [civFilter] Civilizations&gt;"
	Example: "&lt;upon signing a peace treaty with [City-States] Civilizations&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon declaring friendship&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon declaring a defensive pact&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon entering a Golden Age&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon ending a Golden Age&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon conquering a city&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon acquiring a city&gt;"
	Triggers once for each city that changes hands to this civilization in any way: conquest, liberation, marriage with a City-State, or a trade. Not for founded cities. The relevant city is the acquired one, so `in this city` filters work (CvPlayer::acquireCity)

	Applicable to: TriggerCondition

??? example  "&lt;upon losing a city&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon founding a city&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon building a [improvementFilter] improvement&gt;"
	Example: "&lt;upon building a [All Road] improvement&gt;"

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon discovering a Natural Wonder&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon constructing [buildingFilter]&gt;"
	Example: "&lt;upon constructing [Culture]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon constructing [buildingFilter] [cityFilter]&gt;"
	Example: "&lt;upon constructing [Culture] [in all cities]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon gaining a [baseUnitFilter] unit&gt;"
	Example: "&lt;upon gaining a [Melee] unit&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon losing a [mapUnitFilter] unit&gt;"
	Example: "&lt;upon losing a [Wounded] unit&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon turn end&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon turn start&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon founding a Pantheon&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon founding a Religion&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon enhancing a Religion&gt;"
	Applicable to: TriggerCondition

??? example  "&lt;upon reaching contribution tier [positiveAmount] of a World Congress project&gt;"
	Only used on the resolutions that are projects (`projectCostPerPlayer` in Resolutions.json): the effect is triggered for every civilization that completes the project with this contribution tier or a higher one

	Example: "&lt;upon reaching contribution tier [3] of a World Congress project&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon population growth&gt;"
	When a city's population grows above the highest it ever had (a city that starves and regrows gets nothing, nor does a city in resistance or on the turn it was founded), whether by food, by a unique or by a building. The triggering amount is the citizens gained (`Gain [relativeAmount]% of the triggering amount as [stat]`, `<for every [Triggering amount]>`) (`CvCity::setPopulation`, `INSTANT_YIELD_TYPE_BIRTH`)

	Applicable to: TriggerCondition

??? example  "&lt;upon acquiring a [tileFilter] tile through [tileAcquisition]&gt;"
	Once for each tile that a city of this civilization takes in that way (`INSTANT_YIELD_TYPE_BORDERS`, `..._CULTURE_BOMB`, the `Trait_YieldFromTile...` tables). The filter is checked on the tile, so `[Hill]` or `[Desert]` pick the terrain. When the culture of a city is enough for a new tile but it has none left to take, it still gets the yield once for each tile it would have taken, for `border growth` and the tile filter `[All]` only

	Example: "&lt;upon acquiring a [Farm] tile through [border growth]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon buying something with [stat]&gt;"
	When a city buys a building or a unit with that stat, after the price is paid. The triggering amount is the price (`Gain [relativeAmount]% of the triggering amount as [stat]`). With `in this city` the unique of a building fires for the buying city only (`INSTANT_YIELD_TYPE_PURCHASE`, `..._FAITH_PURCHASE`)

	Example: "&lt;upon buying something with [Culture]&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon completing a [baseUnitFilter] unit&gt;"
	When a city completes the unit with its production, not when it buys it. The triggering amount is the unit's production cost (`INSTANT_YIELD_TYPE_U_PROD`)

	Example: "&lt;upon completing a [Melee] unit&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon pillaging a tile&gt;"
	When a unit pillages an improvement in the territory of another civilization. The city of the event is the city that made the unit, else the capital (`CvUnit::pillage`, `INSTANT_YIELD_TYPE_PILLAGE`, `..._PILLAGE_GLOBAL`)

	Applicable to: TriggerCondition

??? example  "&lt;upon We Love The King Day starting&gt;"
	In the city that starts celebrating (`CvCity::ChangeWeLoveTheKingDayCounter`, `INSTANT_YIELD_TYPE_WLTKD_START`)

	Applicable to: TriggerCondition

??? example  "&lt;upon completing a [tradeRouteKind] trade route&gt;"
	When a trade route of the civilization ends, in the city it started in (and in the destination city for a domestic route): `international`, `domestic` or `any` (`INSTANT_YIELD_TYPE_TR_END`, V:CvTradeClasses.cpp:2606-2650: once for each end of a domestic route, so twice for a unique that does not name a city). Not fired for a route that was plundered or cancelled

	Example: "&lt;upon completing a [international] trade route&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon a trade route unit moving&gt;"
	Once each turn that a trade unit of the civilization moves along its route, and when it starts, in the city the route starts in, however many steps it makes (`INSTANT_YIELD_TYPE_TR_MOVEMENT`, V:CvTradeClasses.cpp:2069-2146, V:CvPlayer.cpp:26826-26838)

	Applicable to: TriggerCondition

??? example  "&lt;upon plundering a trade route&gt;"
	When a unit of the civilization plunders a trade route, in the city the unit was made in (else the capital); as a unit's own unique it is the unit that gains (`INSTANT_YIELD_TYPE_PLUNDER_TRADE_ROUTE`, V:CvTradeClasses.cpp:5034, V:CvPlayer.cpp:26863-26870)

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon demanding tribute from a City-State&gt;"
	When the civilization takes gold from a City-State with the ModConstants `cityStateBullyLikeVp`, in the capital. The triggering amount is the gold taken (`Gain [relativeAmount]% of the triggering amount as [stat]`: the original's `Policy_YieldFromMinorDemand` and `Trait_YieldFromMinorDemand`, INSTANT_YIELD_TYPE_BULLY)

	Applicable to: TriggerCondition

??? example  "&lt;upon a World Congress session starting&gt;"
	Only with the ModConstants `leagueSessionRulesLikeVp`. When a REGULAR session starts (not a special one), for every living major civilization with delegates, in the capital. The triggering amount is the civilization's delegates in the session (`CvLeague::StartSession`, V:CvVotingClasses.cpp:7292-7303, `INSTANT_YIELD_TYPE_DELEGATES`: the original's `Policy_YieldFromDelegateCount`, Statecraft's finisher)

	Applicable to: TriggerCondition

??? example  "&lt;upon a proposal of ours passing&gt;"
	Only with the ModConstants `leagueSessionRulesLikeVp`. When a proposal of a civilization passes in the World Congress, an enactment or a repeal, for the civilization that proposed it (not for the proposals of the World Congress itself), in every city, before the resolution takes effect (`CvLeague::FinishSession`, V:CvVotingClasses.cpp:7624 and 8028, `INSTANT_YIELD_TYPE_PROPOSAL`: the original's `Belief_YieldFromProposal`, Global Commandments)

	Applicable to: TriggerCondition

??? example  "&lt;upon gaining a luxury resource&gt;"
	When the civilization has more of a luxury resource than it ever had (without what it imports), in the capital. The triggering amount is how many it has more (`CvPlayer::CheckForLuxuryResourceGainInstantYields`, `INSTANT_YIELD_TYPE_LUXURY_RESOURCE_GAIN`)

	Applicable to: TriggerCondition

??? example  "&lt;upon performing a spy mission&gt;"
	Only with the ModConstants `espionageLikeVp`. When a spy of the civilization finishes a mission, killed or not, in every city of the civilization (CvPlayerEspionage::ProcessSpyMissionResult, V:CvEspionageClasses.cpp:1637, `INSTANT_YIELD_TYPE_SPY_ATTACK`: each city pays its own `Building_YieldFromSpyAttack`)

	Applicable to: TriggerCondition

??? example  "&lt;upon [spyOutcome] a foreign spy in one of our cities&gt;"
	Only with the ModConstants `espionageLikeVp`. When a spy of another civilization did a mission in a city of this civilization and was found out, in that city only (V:CvEspionageClasses.cpp:1586-1627 and 1663-1669; `INSTANT_YIELD_TYPE_SPY_DEFENSE`, `..._IDENTIFY`, `..._DEFENSE_OR_ID`). `killing` when the spy was killed; `identifying without killing` when it was identified and lived (a spy that was killed and identified is only killed: the original's `else if`); `killing or identifying` for either; `identifying` when it was identified, killed or not (the counterspy focus's yields and Double Agents)

	Example: "&lt;upon [killing] a foreign spy in one of our cities&gt;"

	Applicable to: TriggerCondition

??? example  "&lt;upon winning a City-State election with our spy&gt;"
	Only with the ModConstants `espionageLikeVp`. When the civilization wins a rigged City-State election, in every city of the civilization (CvMinorCivAI::DoElection, V:CvMinorCivAI.cpp:15296-15300, `INSTANT_YIELD_TYPE_SPY_RIG_ELECTION`: each city pays its own `Building_YieldFromSpyRigElection`)

	Applicable to: TriggerCondition

??? example  "&lt;upon defeating a [mapUnitFilter] unit&gt;"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats. Also usable on the uniques of nations, buildings, policies and beliefs: they fire when a unit or a city of the civilization defeats the unit, in the city the unit was made in (else the capital), and the triggering amount is the strength of the defeated unit (the higher of melee and ranged), as Vox Populi's `CvPlayer::DoYieldsFromKill` takes the yields from kills

	Example: "&lt;upon defeating a [Wounded] unit&gt;"

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon expending a [mapUnitFilter] unit&gt;"
	Example: "&lt;upon expending a [Wounded] unit&gt;"

	Applicable to: TriggerCondition

## UnitTriggerCondition uniques
!!! note ""

    Special conditionals that can be added to UnitTriggerable uniques, to make them activate upon specific actions.

??? example  "&lt;upon conquering a city&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon building a [improvementFilter] improvement&gt;"
	Example: "&lt;upon building a [All Road] improvement&gt;"

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon turn end&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon turn start&gt;"
	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon plundering a trade route&gt;"
	When a unit of the civilization plunders a trade route, in the city the unit was made in (else the capital); as a unit's own unique it is the unit that gains (`INSTANT_YIELD_TYPE_PLUNDER_TRADE_ROUTE`, V:CvTradeClasses.cpp:5034, V:CvPlayer.cpp:26863-26870)

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon entering combat&gt;"
	Applicable to: UnitTriggerCondition

??? example  "&lt;upon damaging a [mapUnitFilter] unit&gt;"
	Can apply triggers to to damaged unit by setting the first parameter to 'Target Unit'

	Example: "&lt;upon damaging a [Wounded] unit&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon defeating a [mapUnitFilter] unit&gt;"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats. Also usable on the uniques of nations, buildings, policies and beliefs: they fire when a unit or a city of the civilization defeats the unit, in the city the unit was made in (else the capital), and the triggering amount is the strength of the defeated unit (the higher of melee and ranged), as Vox Populi's `CvPlayer::DoYieldsFromKill` takes the yields from kills

	Example: "&lt;upon defeating a [Wounded] unit&gt;"

	Applicable to: TriggerCondition, UnitTriggerCondition

??? example  "&lt;upon being defeated&gt;"
	Does not apply to units destroyed by nuclear explosions, which do not count as combat defeats.

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon being promoted&gt;"
	Applicable to: UnitTriggerCondition

??? example  "&lt;upon gaining the [promotion] promotion&gt;"
	Example: "&lt;upon gaining the [Shock I] promotion&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon losing the [promotion] promotion&gt;"
	Example: "&lt;upon losing the [Shock I] promotion&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon gaining the [promotion] status&gt;"
	Example: "&lt;upon gaining the [Shock I] status&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon losing the [promotion] status&gt;"
	Example: "&lt;upon losing the [Shock I] status&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon losing at least [positiveAmount] HP in a single attack&gt;"
	Example: "&lt;upon losing at least [3] HP in a single attack&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon ending a turn in a [tileFilter] tile&gt;"
	Example: "&lt;upon ending a turn in a [Farm] tile&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon discovering a [tileFilter] tile&gt;"
	Example: "&lt;upon discovering a [Farm] tile&gt;"

	Applicable to: UnitTriggerCondition

??? example  "&lt;upon entering a [tileFilter] tile&gt;"
	Example: "&lt;upon entering a [Farm] tile&gt;"

	Applicable to: UnitTriggerCondition

## UnitActionModifier uniques
!!! note ""

    Modifiers that can be added to UnitAction uniques as conditionals

??? example  "&lt;by consuming this unit&gt;"
	Applicable to: UnitActionModifier

??? example  "&lt;for [amount] movement&gt;"
	Will consume up to [amount] of Movement to execute

	Example: "&lt;for [3] movement&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;for all movement&gt;"
	Will consume all Movement to execute

	Applicable to: UnitActionModifier

??? example  "&lt;requires [nonNegativeAmount] movement&gt;"
	Requires [nonNegativeAmount] of Movement to execute. Unit's Movement is rounded up

	Example: "&lt;requires [3] movement&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;costs [stats] stats&gt;"
	A positive Integer value will be subtracted from your stock. Food and Production will be removed from Closest City's current stock

	Example: "&lt;costs [+1 Gold, +2 Production] stats&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;costs [amount] [stockpiledResource]&gt;"
	A positive Integer value will be subtracted from your stock. Do not confuse with "Costs [amount] [stockpiledResource]" (uppercase 'C') for Improvements, Buildings, and Units.

	Example: "&lt;costs [3] [Mana]&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;removing the [promotion] promotion/status&gt;"
	Removes the promotion/status from the unit - this is not a cost, units will be able to activate the action even without the promotion/status. To limit, use &lt;with the [promotion] promotion&gt; conditional

	Example: "&lt;removing the [Shock I] promotion/status&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;once&gt;"
	Applicable to: UnitActionModifier

??? example  "&lt;[positiveAmount] times&gt;"
	Example: "&lt;[3] times&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;[nonNegativeAmount] additional time(s)&gt;"
	Example: "&lt;[3] additional time(s)&gt;"

	Applicable to: UnitActionModifier

??? example  "&lt;after which this unit is consumed&gt;"
	Applicable to: UnitActionModifier

??? example  "&lt;with [amount] priority&gt;"
	How often this action is used, a higher value means more often and that it should be on an earlier page. 100 is very frequent, 50 is somewhat frequent, less than 25 is press one time for multi-turn movement. A Rare case is &gt; 100 if a button is something like add in capital, promote or something, we need to inform the player that taking the action is an option.

	Example: "&lt;with [3] priority&gt;"

	This unique is automatically hidden from users.

	Applicable to: UnitActionModifier, MetaModifier

## MetaModifier uniques
!!! note ""

    Modifiers that can be added to other uniques changing user experience, not their behavior

??? example  "&lt;for [nonNegativeAmount] turns&gt;"
	Turns this unique into a trigger, activating this unique as a *global* unique for a number of turns

	Example: "&lt;for [3] turns&gt;"

	Applicable to: MetaModifier

??? example  "&lt;with [amount] priority&gt;"
	How often this action is used, a higher value means more often and that it should be on an earlier page. 100 is very frequent, 50 is somewhat frequent, less than 25 is press one time for multi-turn movement. A Rare case is &gt; 100 if a button is something like add in capital, promote or something, we need to inform the player that taking the action is an option.

	Example: "&lt;with [3] priority&gt;"

	This unique is automatically hidden from users.

	Applicable to: UnitActionModifier, MetaModifier

??? example  "&lt;hidden from users&gt;"
	Applicable to: MetaModifier

??? example  "&lt;for every [countable]&gt;"
	Works for positive numbers only

	Example: "&lt;for every [1000]&gt;"

	Applicable to: MetaModifier

??? example  "&lt;for every adjacent [tileFilter]&gt;"
	Works for positive numbers only

	Example: "&lt;for every adjacent [Farm]&gt;"

	Applicable to: MetaModifier

??? example  "&lt;for every [positiveAmount] [countable]&gt;"
	Works for positive numbers only

	Example: "&lt;for every [3] [1000]&gt;"

	Applicable to: MetaModifier

??? example  "&lt;for each [mapUnitFilter] unit within [positiveAmount] tiles&gt;"
	Triggers this unique once for each of your units that matches the filter, instead of once for the triggering unit. Distance 0 is the tile of the triggering unit, 1 adds its neighbors. Only applies to unit-triggerable uniques triggered by a unit, and only to units of the same civilization. `[This Unit]` then refers to each matching unit in turn - add `other` to the filter to leave out the triggering unit. Not performable (and so a unit action is disabled) if no unit would be affected.

	Example: "&lt;for each [Wounded] unit within [3] tiles&gt;"

	Applicable to: MetaModifier

??? example  "&lt;(modified by game speed)&gt;"
	Can only be applied to certain uniques, see details of each unique for specifics

	Applicable to: MetaModifier

??? example  "&lt;(modified by game progress up to [relativeAmount]%)&gt;"
	Can only be applied to certain uniques, see details of each unique for specifics

	Example: "&lt;(modified by game progress up to [+20]%)&gt;"

	Applicable to: MetaModifier

??? example  "&lt;Civilopedia link [pediaLink]&gt;"
	Allows linking a unique to any Civilopedia page when it is listed in Civilopedia normally. This overrides automatic links to objects in the unique's parameters.

	Example: "&lt;Civilopedia link [Units/Settler]&gt;"

	This unique is automatically hidden from users.

	Applicable to: MetaModifier

??? example  "&lt;Suppress warning [validationWarning]&gt;"
	Allows suppressing specific validation warnings. Errors, deprecation warnings, or warnings about untyped and non-filtering uniques should be heeded, not suppressed, and are therefore not accepted. Note that this can be used in ModOptions, in the uniques a warning is about, or as modifier on the unique triggering a warning - but you still need to be specific. Even in the modifier case you will need to specify a sufficiently selective portion of the warning text as parameter.

	Example: "&lt;Suppress warning [Tinman is supposed to automatically upgrade at tech Clockwork, and therefore Servos for its upgrade Mecha may not yet be researched! -or- *is supposed to automatically upgrade*]&gt;"

	This unique does not support conditionals.

	This unique is automatically hidden from users.

	Applicable to: Triggerable, Terrain, Speed, ModOptions, MetaModifier


*[amount]: This indicates a whole number, possibly with a + or - sign, such as `2`, `+13`, or `-3`.
*[baseTerrain]: The name of any terrain that is a base terrain according to the json file.
*[belief]: The name of any belief.
*[beliefType]: 'Pantheon', 'Follower', 'Founder' or 'Enhancer'
*[buildingName]: The name of any building.
*[civWideStat]: All the following stats have civ-wide fields: `Gold`, `Science`, `Culture`, `Faith`.
*[combatantFilter]: This indicates a combatant, which can either be a unit or a city (when bombarding). Must either be `City` or a `mapUnitFilter`.
*[costOrStrength]: `Cost` or `Strength`.
*[countable]: This indicates a number or a numeric variable.They can be tested in the developer console with `civ checkcountable` - for example, `civ checkcountable "[Iron]+2"`.
*[difficulty]: The name of any difficulty.
*[era]: The name of any era.
*[eraFilter]: The name of an era, `any era`, `Starting Era`, `pre-[era]`, `post-[era]`.
*[event]: The name of any event.
*[foundingOrEnhancing]: `founding` or `enhancing`.
*[fraction]: Indicates a fractional number, which can be negative.
*[greatWork]: The name of any Great Work.
*[greatWorkSlotType]: One of `Art/Artifact`, `Literature` or `Music`.
*[greatWorkType]: One of `Art`, `Artifact`, `Literature` or `Music`.
*[improvementName]: The name of any improvement excluding 'Cancel improvement order'
*[leaderTitle]: Provides a leader title that includes the leader's name in parameters.
*[modFilter]: A Mod name, case-sensitive _or_ a simple wildcard filter beginning and ending in an Asterisk, case-insensitive.
Note that this must use the Mod name as Unciv displays it, not the Repository name.
There is a conversion affecting dashes and leading/trailing blanks. Please make sure not to get confused.
*[need]: One of `Distress`, `Poverty`, `Illiteracy`, `Boredom` or `Religious Unrest`.
*[nonNegativeAmount]: This indicates a non-negative whole number, larger than or equal to zero, a '+' sign is optional.
*[pediaLink]: A Civilopedia link in the form category/entry.
*[policy]: The name of any policy.
*[policyFilter]: The name of any policy, a filtering Unique, any branch (matching only the branch itself), a branch name with " Completed" appended (matches if the branch is completed), or a policy branch as `[branchName] branch` (matching all policies in that branch).
*[positiveAmount]: This indicates a positive whole number, larger than zero, a '+' sign is optional.
*[promotion]: The name of any promotion.
*[promotionList]: Comma-separated names of promotions.
*[relativeAmount]: This indicates a number, usually with a + or - sign, such as `+25` (this kind of parameter is often followed by '%' which is nevertheless not part of the value).
*[resolution]: The name of any resolution of the World Congress.
*[resource]: The name of any resource.
*[resourceFilter]: A resource name, type, 'all', or a Stat listed in the resource's improvementStats.
*[specialist]: The name of any specialist.
*[speed]: The name of any speed.
*[spyAction]: A spy action display name, e.g. `Counter-intelligence`, `Stealing Tech`.
*[spyMissionRequirement]: One of `the city`, `no fresh water`, `a trade connection`, `an unhappy owner` or `no puppet`.
*[spyOutcome]: One of `killing`, `identifying`, `identifying without killing` or `killing or identifying`.
*[stat]: This is one of the 7 major stats in the game - `Gold`, `Science`, `Production`, `Food`, `Happiness`, `Culture` and `Faith`. Note that the stat names need to be capitalized!
*[stats]: For example: `+2 Production, +3 Food`. Note that the stat names need to be capitalized!
*[stockpile]: The name of any stockpiled resource.
*[stockpiledResource]: The name of any stockpiled resource.
*[tech]: The name of any tech.
*[terrainFeature]: The name of any terrain that is a terrain feature according to the json file.
*[tileAcquisition]: One of `border growth`, `purchase`, `settling`, `conquest`, `culture bomb` or `stolen by culture bomb`.
*[tileFilter]: Anything that can be used either in an improvementFilter or in a terrainFilter can be used here, plus 'unimproved'
*[tourismRelation]: One of `Shared Religion`, `Open Borders`, `Trade Route`, `Lower Happiness`, `Common Enemies`, `Shared Ideology` or `Different Ideologies`.
*[tradeRouteFilter]: A trade route matches if its unit is of that domain (`Land` or `Water`), if it is of that kind (`International`, `Domestic`, `Food` or `Production`), or if the civilization on the other end matches a civ filter. `All` matches every route.
*[tradeRouteKind]: One of `international`, `domestic` or `any`.
*[unitNameGroup]: The name of a unit name group found in UnitNameGroups.json, or one of their unique tags.
*[unitTriggerTarget]: `This Unit` or `Target Unit`.
*[unitType]: Can be 'Land', 'Water', 'Air', any unit type, a filtering Unique on a unit type.
*[validationWarning]: Suppresses one specific Ruleset validation warning. This can specify the full text verbatim including correct upper/lower case, or it can be a wildcard case-insensitive simple pattern starting and ending in an asterisk ('*'). If the suppression unique is used within an object or as modifier (not ModOptions), the wildcard symbols can be omitted, as selectivity is better due to the limited scope.
*[victoryType]: The name of any victory type: 'Cultural', 'Diplomatic', 'Domination', 'Scientific', 'Time' or one of your mod's VictoryTypes.json names.
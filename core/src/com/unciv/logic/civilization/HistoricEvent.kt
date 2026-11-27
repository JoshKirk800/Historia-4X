package com.unciv.logic.civilization

import com.unciv.models.ruleset.unique.UniqueType

/**
 *  Vox Populi's historic events (HistoricEventTypes) that give tourism, see [com.unciv.logic.civilization.managers.CultureManager.onHistoricEvent]: the turns of
 *  yields that [turns] say are divided by [divisor] (CvPlayer::GetHistoricEventTourism, V:CvPlayer.cpp:31252-31420). [counted] says when the event counts,
 *  as the callers of CvPlayer::ChangeNumHistoricEvents do. [label] names it in the notification.
 */
enum class HistoricEvent(val label: String, val divisor: Int, val turns: UniqueType, val counted: Counted) {
    /** A Great Person born (CvUnit::initWithNameOffset, V:CvUnit.cpp:1166-1193) */
    GreatPerson("Great Person", 15, UniqueType.HistoricEventTourismTurns, Counted.Always),
    /** A new era (CvTeam::setHasTech, V:CvTeam.cpp:6742-6768) */
    EraChange("new era", 10, UniqueType.HistoricEventTourismTurns, Counted.Always),
    /** A world wonder built (CvCity::processBuilding, V:CvCity.cpp:13300-13319) */
    WorldWonder("wonder", 5, UniqueType.HistoricEventTourismTurns, Counted.Always),
    /** A war won (CvPlayer::DoWarVictoryBonuses, V:CvPlayer.cpp:18734-18760), see [com.unciv.logic.civilization.diplomacy.WarVictory] */
    WonWar("victory", 5, UniqueType.HistoricEventTourismTurns, Counted.Always),
    /** A golden age started or made longer (CvPlayer::changeGoldenAgeTurns, V:CvPlayer.cpp:25125-25156) */
    GoldenAge("golden age", 5, UniqueType.GoldenAgeTourismTurns, Counted.WithTurns),
    /** A land trade route to a major civilization ended (CvPlayerTrade::MoveUnits, V:CvTradeClasses.cpp:2617-2654), with the turns of its city of origin */
    TradeLand("land trade route", 15, UniqueType.LandTradeRouteTourismTurns, Counted.WithTourism),
    /** A sea trade route to a major civilization ended, as [TradeLand] */
    TradeSea("sea trade route", 15, UniqueType.SeaTradeRouteTourismTurns, Counted.WithTourism),
    /** A trade route to a City-State ended (CvPlayerTrade::MoveUnits, V:CvTradeClasses.cpp:2688-2696) */
    TradeCityState("trade route to a City-State", 5, UniqueType.CityStateTradeRouteTourismTurns, Counted.WithTurns),
    /** A city adopted the religion for the first time (CvCityReligions::CityConvertsReligion, V:CvReligionClasses.cpp:5653-5659) */
    ReligionSpread("religion spread", 15, UniqueType.ReligionSpreadTourismTurns, Counted.WithTourism),
    /** What to do with a dig chosen (CvPlayerCulture::DoArchaeologyChoice, V:CvCultureClasses.cpp:3105-3109), see [com.unciv.logic.map.tile.ArchaeologyFunctions.execute] */
    Dig("archaeological dig", 10, UniqueType.ArchaeologicalDigTourismTurns, Counted.WithTurns),
    ;

    /** When an event counts: always, when its [turns] are positive, or when the tourism it makes is */
    enum class Counted { Always, WithTurns, WithTourism }
}

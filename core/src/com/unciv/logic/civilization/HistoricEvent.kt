package com.unciv.logic.civilization

/**
 *  Vox Populi's historic events (HistoricEventTypes) that give tourism, see [com.unciv.logic.civilization.managers.CultureManager.onHistoricEvent]: the event's tourism
 *  is divided by [divisor] (CvPlayer::GetHistoricEventTourism, V:CvPlayer.cpp:31252-31420). [label] names it in the notification.
 */
enum class HistoricEvent(val label: String, val divisor: Int) {
    /** A Great Person born (CvUnit::initWithNameOffset, V:CvUnit.cpp:1166-1193) */
    GreatPerson("Great Person", 15),
    /** A new era (CvTeam::setHasTech, V:CvTeam.cpp:6742-6768) */
    EraChange("new era", 10),
    /** A world wonder built (CvCity::processBuilding, V:CvCity.cpp:13300-13319) */
    WorldWonder("wonder", 5),
    /** A war won (CvPlayer::DoWarVictoryBonuses, V:CvPlayer.cpp:18734-18760), see [com.unciv.logic.civilization.diplomacy.WarVictory] */
    WonWar("victory", 5),
}

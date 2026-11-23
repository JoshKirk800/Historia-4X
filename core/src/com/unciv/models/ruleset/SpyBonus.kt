package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.UniqueTarget

/** Who earns the bonus of a [SpyBonus]: the spy gathering Network Points in a foreign city, or the diplomat in a capital */
enum class SpyBonusKind { Passive, Diplomat }

/**
 *  A passive bonus of a spy (SpyBonuses.json; `SpyPassiveBonuses` and `SpyPassiveBonusesDiplomat` of Vox Populi, CvCityEspionage::AddNetworkPoints and `AddNetworkPointsDiplomat`,
 *  V:CvEspionageClasses.cpp:7043-7237). It is earned once the spy has stored [networkPoints] in its city (the highest amount ever stored there, which the spy loses when it leaves),
 *  and lasts while the spy stays. What it gives are its [uniques], read from the spy ([com.unciv.models.Spy.getBonuses]).
 */
class SpyBonus : RulesetObject() {
    var kind = SpyBonusKind.Passive
    /** The Network Points the spy must have stored in the city, at standard speed */
    var networkPoints = 0
    /** Whether [networkPoints] scales with the game speed (`NetworkPointsScaling`): 0 in all rows of Vox Populi 5.4.8 */
    var scaledBySpeed = false

    override fun getUniqueTarget() = UniqueTarget.SpyBonus

    /** There is no Civilopedia category for spy bonuses */
    override fun makeLink() = ""
}

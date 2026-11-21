package com.unciv.models.metadata

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType

class GameSetupInfo(
    val gameParameters: GameParameters = GameParameters(),
    val mapParameters: MapParameters = MapParameters()
) {
    @Transient
    var mapFile: FileHandle? = null

    // This constructor is used for starting a new game from a running one, cloning the setup, including map seed
    constructor(gameInfo: GameInfo) : this(gameInfo.gameParameters.clone(), gameInfo.tileMap.mapParameters.clone())
    // Cloning constructor used for [fromSettings], reseeds map
    constructor(setup: GameSetupInfo): this(setup.gameParameters.clone(), setup.mapParameters.clone())

    companion object {
        /**
         * Get a cloned and reseeded [GameSetupInfo] from saved settings if present, otherwise a default instance.
         * @param defaultDifficulty Overrides difficulty only when no saved settings found, so a virgin
         *          Unciv installation can QuickStart with a different difficulty than New Game defaults to.
         */
        fun fromSettings(defaultDifficulty: String? = null) = UncivGame.Current.settings.run {
            if (lastGameSetup == null) firstSetup(defaultDifficulty)
            else GameSetupInfo(lastGameSetup!!).apply {
                mapParameters.reseed()
            }
        }

        /**
         * The setup of an installation without saved settings. The map is the [MapType.continentsLikeCivV] land step on a rectangular, wrapping [MapSize.Small] map.
         * The defaults of [MapParameters] stay as they are: they are the values of every saved game that does not write them.
         */
        fun firstSetup(defaultDifficulty: String? = null) = GameSetupInfo().apply {
            if (defaultDifficulty != null) gameParameters.difficulty = defaultDifficulty
            mapParameters.type = MapType.continentsLikeCivV
            mapParameters.shape = MapShape.rectangular
            mapParameters.mapSize = MapSize.Small
            mapParameters.worldWrap = true
            gameParameters.espionageEnabled = true
        }
    }
}

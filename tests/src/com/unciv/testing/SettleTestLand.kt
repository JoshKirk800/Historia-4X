package com.unciv.testing

/**
 *  Land the settle plot search of Vox Populi accepts: plains everywhere (food and hammers for the cutoffs of PlotFoundValue) with a Wheat on every
 *  fourth plot, so that every site has resources of a kind within reach.
 */
object SettleTestLand {
    fun plainsWithResources(game: TestGame) {
        for (tile in game.tileMap.values) {
            game.setTileTerrain(tile.position, "Plains")
            tile.setTileResource(if ((tile.position.x + 2 * tile.position.y).mod(4) == 0) "Wheat" else null as String?)
        }
    }
}

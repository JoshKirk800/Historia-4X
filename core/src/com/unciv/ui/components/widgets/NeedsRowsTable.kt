package com.unciv.ui.components.widgets

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.NeedsRow

/** Draws the [rows][NeedsRow] of one of the needs breakdowns: a label on the left, its number on the right */
class NeedsRowsTable(rows: List<NeedsRow>, private val indentWidth: Float = 15f) : Table() {
    init {
        defaults().pad(2f)
        for (row in rows) addRow(row)
        pack()
    }

    private fun addRow(row: NeedsRow) {
        val color = when {
            row.isAlert -> Color.SCARLET
            row.style == NeedsRow.Style.Note -> Color.LIGHT_GRAY
            else -> Color.WHITE
        }
        when (row.style) {
            NeedsRow.Style.Heading -> {
                if (!cells.isEmpty) addSeparator(colSpan = 2)
                add(row.label.toLabel(color, Constants.headingFontSize, hideIcons = true)).left().padLeft(0f)
                add(row.value.toLabel(color, Constants.headingFontSize)).right().row()
            }
            else -> {
                add(row.label.toLabel(color, hideIcons = true)).left().padLeft(row.indent * indentWidth)
                add(row.value.toLabel(color)).right().row()
            }
        }
    }
}

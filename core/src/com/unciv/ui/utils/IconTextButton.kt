package com.unciv.ui.utils

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label

/**
 * Translate a [String] and make a [Button] widget from it, with control over font size, font colour, an optional icon, and custom formatting.
 *
 * @param text Text of the button.
 * @property icon If non-null, [Actor] instance for icon left of the label.
 * @param fontSize Text size for [String.toLabel].
 * @param fontColor Text colour for [String.toLabel].
 */
class IconTextButton(text: String, val icon: Actor? = null, fontSize: Int = 18, fontColor: Color = Color.WHITE): Button(BaseScreen.skin) {
    val button = Button(BaseScreen.skin)
    /** [Label] instance produced by and with content and formatting as specified to [String.toLabel]. */
    val label = text.toLabel(fontColor, fontSize)
    /** Table cell containing the [icon] if any, or `null`. */
    val iconCell: Cell<Actor>? = if (icon != null) {
            val size = fontSize.toFloat()
            add(icon).size(size).padRight(size / 3)
        } else {
            null
        }
    /** Table cell instance containing the [label]. */
    val labelCell: Cell<Label> = add(label)
}

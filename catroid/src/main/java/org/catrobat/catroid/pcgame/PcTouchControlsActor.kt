/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2026 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.pcgame

import android.util.Log
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the touch layout of a PC game on top of the stage and converts touches
 * into X11 key/pointer events through the VNC bridge of the stage.
 *
 * All positions are normalised to the virtual screen, so the overlay matches the
 * game surface which is drawn over the whole virtual screen.
 */
class PcTouchControlsActor(
    private val profile: PcTouchProfile,
    private val keyAction: (keyName: String, isDown: Boolean) -> Unit,
    private val pointerAction: (stageX: Float, stageY: Float, buttonMask: Int) -> Unit,
    private val pointerDeltaAction: (dx: Float, dy: Float, buttonMask: Int) -> Unit,
    private val onLayoutChanged: Runnable? = null
) : Actor() {

    private data class PointerState(
        val controlId: String,
        val startX: Float,
        val startY: Float,
        var lastX: Float,
        var lastY: Float,
        var startTime: Long
    )

    private val font = BitmapFont()
    private var circleTexture: Texture? = null
    private var squareTexture: Texture? = null
    private val layouts = HashMap<String, GlyphLayout>()
    private val pointers = HashMap<Int, PointerState>()
    private val pressedControls = HashSet<String>()
    private val activeKeys = HashSet<String>()
    private var joystickOffsets = HashMap<String, FloatArray>()

    /** When enabled a drag moves a control instead of sending game input. */
    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                activeKeys.forEach { key -> keyAction(key, false) }
                activeKeys.clear()
            }
        }

    init {
        touchable = Touchable.enabled
        font.color = Color(1f, 1f, 1f, 0.95f)
        addListener(object : InputListener() {
            override fun touchDown(
                event: InputEvent,
                x: Float,
                y: Float,
                pointer: Int,
                button: Int
            ): Boolean {
                event.stop()
                handleTouchDown(x, y, pointer)
                return true
            }

            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                event.stop()
                handleTouchDragged(x, y, pointer)
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                event.stop()
                handleTouchUp(x, y, pointer)
            }
        })
    }

    // ------------------------------------------------------------- rendering

    override fun draw(batch: Batch, parentAlpha: Float) {
        val areaWidth = width
        val areaHeight = height
        if (areaWidth <= 0f || areaHeight <= 0f) return
        val base = min(areaWidth, areaHeight)
        val circle = obtainCircleTexture()
        val square = obtainSquareTexture()
        val restoreR = batch.color.r
        val restoreG = batch.color.g
        val restoreB = batch.color.b
        val restoreA = batch.color.a

        for (control in profile.controls) {
            val centreX = control.x * areaWidth
            val centreY = areaHeight - control.y * areaHeight
            val radius = max(8f, control.size * base / 2f)
            val isPressed = pressedControls.contains(control.id)
            val alpha = (if (isPressed) 0.85f else 0.55f) * control.opacity * profile.opacity * parentAlpha
            val iconAlpha = 0.85f * control.opacity * profile.opacity * parentAlpha

            when (control.kind.lowercase()) {
                "joystick" -> {
                    batch.color = Color(1f, 1f, 1f, alpha * 0.6f)
                    batch.draw(
                        circle, centreX - radius, centreY - radius,
                        radius * 2f, radius * 2f
                    )
                    val offset = joystickOffsets[control.id]
                    val knobX = centreX + (offset?.get(0) ?: 0f) * radius * 0.6f
                    val knobY = centreY + (offset?.get(1) ?: 0f) * radius * 0.6f
                    val knobRadius = radius * 0.42f
                    batch.color = Color(1f, 1f, 1f, alpha)
                    batch.draw(
                        circle, knobX - knobRadius, knobY - knobRadius,
                        knobRadius * 2f, knobRadius * 2f
                    )
                }
                "look" -> {
                    batch.color = Color(1f, 1f, 1f, alpha * 0.35f)
                    batch.draw(
                        square, centreX - radius, centreY - radius,
                        radius * 2f, radius * 2f
                    )
                }
                else -> {
                    batch.color = Color(1f, 1f, 1f, alpha)
                    batch.draw(
                        circle, centreX - radius, centreY - radius,
                        radius * 2f, radius * 2f
                    )
                }
            }

            if (control.label.isNotEmpty()) {
                font.color = Color(1f, 1f, 1f, iconAlpha)
                val layout = layouts.getOrPut(control.id + "|" + control.label) {
                    GlyphLayout(font, control.label)
                }
                font.draw(
                    batch,
                    layout,
                    centreX - layout.width / 2f,
                    centreY + layout.height / 2f
                )
            }

            if (editMode) {
                batch.color = Color(1f, 0.85f, 0.2f, 0.9f * parentAlpha)
                val border = max(2f, radius * 0.05f)
                batch.draw(square, centreX - radius, centreY + radius - border, radius * 2f, border)
                batch.draw(square, centreX - radius, centreY - radius, radius * 2f, border)
                batch.draw(square, centreX - radius, centreY - radius, border, radius * 2f)
                batch.draw(square, centreX + radius - border, centreY - radius, border, radius * 2f)
            }
        }

        batch.color = Color(restoreR, restoreG, restoreB, restoreA)
    }

    private fun obtainCircleTexture(): Texture {
        val existing = circleTexture
        if (existing != null) return existing
        val size = 128
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixmap.setColor(1f, 1f, 1f, 1f)
        val centre = (size - 1) / 2f
        val radius = size / 2f - 1f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - centre
                val dy = y - centre
                val distance = sqrt(dx * dx + dy * dy)
                if (distance <= radius) {
                    val edge = (radius - distance).coerceIn(0f, 2f) / 2f
                    pixmap.setColor(1f, 1f, 1f, 0.35f + 0.65f * edge)
                    pixmap.drawPixel(x, y)
                }
            }
        }
        val texture = Texture(pixmap)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        circleTexture = texture
        return texture
    }

    private fun obtainSquareTexture(): Texture {
        val existing = squareTexture
        if (existing != null) return existing
        val size = 64
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val corner = 14f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = when {
                    x < corner -> corner - x
                    x > size - corner -> x - (size - corner)
                    else -> 0f
                }
                val dy = when {
                    y < corner -> corner - y
                    y > size - corner -> y - (size - corner)
                    else -> 0f
                }
                val distance = sqrt(dx * dx + dy * dy)
                if (dx == 0f || dy == 0f || distance <= corner) {
                    pixmap.setColor(1f, 1f, 1f, 1f)
                    pixmap.drawPixel(x, y)
                }
            }
        }
        val texture = Texture(pixmap)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        squareTexture = texture
        return texture
    }

    // ---------------------------------------------------------------- input

    private fun controlAt(x: Float, y: Float): PcTouchControl? {
        val width = width
        val height = height
        if (width <= 0f || height <= 0f) return null
        val base = min(width, height)
        for (control in profile.controls.asReversed()) {
            val centreX = control.x * width
            val centreY = height - control.y * height
            val radius = control.size * base / 2f
            val dx = x - centreX
            val dy = y - centreY
            if (dx * dx + dy * dy <= radius * radius) return control
        }
        return null
    }

    private fun handleTouchDown(x: Float, y: Float, pointer: Int) {
        val control = controlAt(x, y) ?: return
        pointers[pointer] = PointerState(
            controlId = control.id,
            startX = x,
            startY = y,
            lastX = x,
            lastY = y,
            startTime = System.currentTimeMillis()
        )
        pressedControls.add(control.id)

        when (control.kind.lowercase()) {
            "joystick" -> updateJoystick(control, x, y, force = true)
            "look" -> {
                if (control.type.equals("mouse", true) && control.button >= 0) {
                    // a tap with the configured mouse button, dragging moves the
                    // pointer instead (button mask is only sent on release)
                }
            }
            else -> pressControl(control)
        }
    }

    private fun handleTouchDragged(x: Float, y: Float, pointer: Int) {
        val state = pointers[pointer] ?: return
        val control = profile.findControl(state.controlId) ?: return

        if (editMode) {
            moveControl(control, x, y)
            state.lastX = x
            state.lastY = y
            return
        }

        when (control.kind.lowercase()) {
            "joystick" -> updateJoystick(control, x, y, force = false)
            "look" -> {
                val dx = x - state.lastX
                val dy = y - state.lastY
                if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                    pointerDeltaAction(dx, dy, 0)
                }
            }
            else -> {
                // a finger that slides off the button releases it
                val centreX = control.x * width
                val centreY = height - control.y * height
                val radius = control.size * min(width, height) / 2f
                val dx = x - centreX
                val dy = y - centreY
                val inside = dx * dx + dy * dy <= radius * radius * 1.6f
                if (!inside && pressedControls.contains(control.id)) {
                    releaseControl(control)
                    pressedControls.remove(control.id)
                } else if (inside && !pressedControls.contains(control.id)) {
                    pressedControls.add(control.id)
                    pressControl(control)
                }
            }
        }
        state.lastX = x
        state.lastY = y
    }

    private fun handleTouchUp(x: Float, y: Float, pointer: Int) {
        val state = pointers.remove(pointer) ?: return
        val control = profile.findControl(state.controlId) ?: return
        pressedControls.remove(control.id)

        when (control.kind.lowercase()) {
            "joystick" -> {
                val keys = control.keyList()
                for (key in keys) {
                    if (activeKeys.remove(key)) keyAction(key, false)
                }
                joystickOffsets.remove(control.id)
            }
            "look" -> {
                val duration = System.currentTimeMillis() - state.startTime
                val distance = abs(x - state.startX) + abs(y - state.startY)
                if (duration < 400 && distance < 24f && control.type.equals("mouse", true)) {
                    val stageX = this.x + x
                    val stageY = this.y + y
                    pointerAction(stageX, stageY, buttonMask(control.button))
                    pointerAction(stageX, stageY, 0)
                }
            }
            else -> releaseControl(control)
        }

        if (editMode) {
            onLayoutChanged?.run()
        }
    }

    /** RFB pointer mask: bit 0 left, bit 1 middle, bit 2 right, 3/4 extra. */
    private fun buttonMask(button: Int): Int = 1 shl button.coerceIn(0, 4)

    private fun pressControl(control: PcTouchControl) {
        if (control.type.equals("mouse", true)) {
            val centreX = control.x * width
            val centreY = height - control.y * height
            val stageX = this.x + centreX
            val stageY = this.y + centreY
            pointerAction(stageX, stageY, buttonMask(control.button))
            return
        }
        for (key in control.keyList()) {
            if (activeKeys.add(key)) keyAction(key, true)
        }
    }

    private fun releaseControl(control: PcTouchControl) {
        if (control.type.equals("mouse", true)) {
            val centreX = control.x * width
            val centreY = height - control.y * height
            val stageX = this.x + centreX
            val stageY = this.y + centreY
            pointerAction(stageX, stageY, 0)
            return
        }
        for (key in control.keyList()) {
            if (activeKeys.remove(key)) keyAction(key, false)
        }
    }

    private fun updateJoystick(control: PcTouchControl, x: Float, y: Float, force: Boolean) {
        val centreX = control.x * width
        val centreY = height - control.y * height
        val radius = max(1f, control.size * min(width, height) / 2f)
        var dx = (x - centreX) / radius
        var dy = (y - centreY) / radius
        val length = sqrt(dx * dx + dy * dy)
        if (length > 1f) {
            dx /= length
            dy /= length
        }
        joystickOffsets[control.id] = floatArrayOf(dx, dy)

        val keys = control.keyList()
        if (keys.isEmpty()) return
        val threshold = 0.35f
        val desired = HashSet<String>()
        if (keys.size >= 4) {
            if (dy > threshold) desired.add(keys[0])
            if (dx < -threshold) desired.add(keys[1])
            if (dy < -threshold) desired.add(keys[2])
            if (dx > threshold) desired.add(keys[3])
        } else if (keys.size == 1 && (abs(dx) > threshold || abs(dy) > threshold)) {
            desired.add(keys[0])
        }
        for (key in keys) {
            if (desired.contains(key)) {
                if (activeKeys.add(key)) keyAction(key, true)
            } else if (activeKeys.remove(key)) {
                keyAction(key, false)
            }
        }
        if (force) {
            Log.d(TAG, "joystick '${control.id}' engaged")
        }
    }

    private fun moveControl(control: PcTouchControl, x: Float, y: Float) {
        if (width <= 0f || height <= 0f) return
        control.x = (x / width).coerceIn(0f, 1f)
        control.y = (1f - y / height).coerceIn(0f, 1f)
    }

    fun dispose() {
        circleTexture?.dispose()
        circleTexture = null
        squareTexture?.dispose()
        squareTexture = null
        font.dispose()
        pointers.clear()
        activeKeys.clear()
        pressedControls.clear()
    }

    companion object {
        private const val TAG = "PcTouchControls"
    }
}

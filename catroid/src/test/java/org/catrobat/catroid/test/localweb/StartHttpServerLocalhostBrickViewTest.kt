/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://www.gnu.org/licenses/.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.localweb

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.Sprite
import org.catrobat.catroid.content.WhenScript
import org.catrobat.catroid.content.actions.ScriptSequenceAction
import org.catrobat.catroid.content.actions.StartHttpServerAction
import org.catrobat.catroid.content.bricks.StartHttpServerBrick
import org.catrobat.catroid.content.bricks.StartHttpServerLocalhostBrick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression tests for the reported device problem: the new "Start local HTTP
 * server on port (localhost)" block showed no usable port field in the visual
 * editor. Root cause: BrickLayout only wraps a child onto a new line when the
 * child carries app:layout_newLine="true" AND the line content overflows the
 * brick width; the localhost brick's (long, also localized) label plus the
 * port field overflow on device widths, so the field was laid out beyond the
 * right edge and clipped. The original block is unaffected because its second
 * label carries the wrap hint.
 *
 * These tests verify the actual visual block: the inflated view exposes an
 * editable, clickable port field; the field stays inside the brick bounds even
 * at a narrow width (the clipping regression); the entered port reaches the
 * server-start action; and the original block is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class StartHttpServerLocalhostBrickViewTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    // Brick layouts use theme attributes (?attr/large via the BrickEditText
    // style) that only resolve with the app theme - the same setup the
    // existing brick-view tests get from a themed activity.
    private val themedContext: Context =
        ContextThemeWrapper(context, R.style.Catroid)
    private val scope: Scope = Scope(null, Sprite(), null)

    private fun portFieldOf(brickView: View, id: Int): TextView =
        brickView.findViewById(id) as TextView

    @Test
    fun `localhost brick view exposes a visible editable port field`() {
        val brick = StartHttpServerLocalhostBrick()
        val view = brick.getView(themedContext)

        val field = portFieldOf(view, R.id.brick_start_http_server_localhost_edit_port)
        assertNotNull("port field view must exist in the inflated layout", field)
        assertEquals("8080", field.text.toString())

        // The field becomes editable (clickable) exactly like in the original
        // block: the adapter calls setClickListeners() when the brick is shown.
        brick.setClickListeners()
        assertTrue("port field must be clickable to open the formula editor", field.hasOnClickListeners())
    }

    @Test
    fun `cloned localhost brick keeps its port field`() {
        // Dragging a brick from the palette into a script clones it.
        val brick = StartHttpServerLocalhostBrick("9090")
        val clone = brick.clone() as StartHttpServerLocalhostBrick
        val view = clone.getView(themedContext)
        val field = portFieldOf(view, R.id.brick_start_http_server_localhost_edit_port)
        assertNotNull(field)
        assertEquals("9090", field.text.toString())
    }

    @Test
    fun `port field stays inside the brick bounds at a narrow width`() {
        // Regression for the clipping: at a narrow width the long label plus
        // the field overflow a single line; the field must wrap (via its
        // layout_newLine hint) and remain fully inside the brick.
        val brick = StartHttpServerLocalhostBrick()
        val view = brick.getView(themedContext)
        val narrowWidth = 220
        view.measure(
            View.MeasureSpec.makeMeasureSpec(narrowWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, narrowWidth, view.measuredHeight)

        val field = portFieldOf(view, R.id.brick_start_http_server_localhost_edit_port)
        assertTrue("port field must have a positive width", field.width > 0)
        assertTrue(
            "port field must stay inside the brick bounds (right=${field.right}, width=$narrowWidth)",
            field.right <= narrowWidth
        )
    }

    @Test
    fun `original start server brick view is unchanged`() {
        val brick = StartHttpServerBrick()
        val view = brick.getView(themedContext)
        val port = portFieldOf(view, R.id.brick_start_http_server_edit_port)
        val bind = portFieldOf(view, R.id.brick_start_http_server_edit_bind)
        assertEquals("8080", port.text.toString())
        assertEquals("127.0.0.1", bind.text.toString())
        brick.setClickListeners()
        assertTrue(port.hasOnClickListeners())
        assertTrue(bind.hasOnClickListeners())
    }

    @Test
    fun `entered port and localhost bind address reach the server start action`() {
        ProjectManager(context)
        val sprite = Sprite("s")
        sprite.addScript(WhenScript())
        val sequence = ScriptSequenceAction(sprite.getScript(0))

        val brick = StartHttpServerLocalhostBrick("9090")
        brick.addActionToSequence(sprite, sequence)

        val action = sequence.actions.first() as StartHttpServerAction
        assertEquals(9090.0, action.port?.interpretObject(scope))
        assertEquals("localhost", action.bindAddress?.interpretString(scope))
    }

    @Test
    fun `original brick still passes port and 127 bind address to its action`() {
        ProjectManager(context)
        val sprite = Sprite("s")
        sprite.addScript(WhenScript())
        val sequence = ScriptSequenceAction(sprite.getScript(0))

        val brick = StartHttpServerBrick("9091")
        brick.addActionToSequence(sprite, sequence)

        val action = sequence.actions.first() as StartHttpServerAction
        assertEquals(9091.0, action.port?.interpretObject(scope))
        assertEquals("127.0.0.1", action.bindAddress?.interpretString(scope))
    }
}

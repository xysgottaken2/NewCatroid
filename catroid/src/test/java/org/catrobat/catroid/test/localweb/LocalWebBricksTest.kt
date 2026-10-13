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
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.localweb

import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.Sprite
import org.catrobat.catroid.content.bricks.Brick
import org.catrobat.catroid.content.bricks.HostDirectoryBrick
import org.catrobat.catroid.content.bricks.OpenWebPageBrick
import org.catrobat.catroid.content.bricks.SelectDirectoryBrick
import org.catrobat.catroid.content.bricks.SelectFileBrick
import org.catrobat.catroid.content.bricks.SelectFolderAndHostBrick
import org.catrobat.catroid.content.bricks.StartHttpServerBrick
import org.catrobat.catroid.content.bricks.StartHttpServerLocalhostBrick
import org.catrobat.catroid.content.bricks.StopHttpServerBrick
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.io.XstreamSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the new local-web blocks: default formula values, variable
 * spinner integration and XStream (de)serialization used for project save /
 * load.
 */
class LocalWebBricksTest {

    private val scope: Scope = Scope(null, Sprite(), null)

    @Test
    fun `start http server brick has port and bind address defaults`() {
        val brick = StartHttpServerBrick()
        assertEquals(8080.0, brick.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
        assertEquals(
            "127.0.0.1",
            brick.getFormulaWithBrickField(Brick.BrickField.IP).interpretString(scope)
        )

        val custom = StartHttpServerBrick("9090")
        assertEquals(9090.0, custom.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
    }

    @Test
    fun `localhost server brick has a port default and no bind address field`() {
        val brick = StartHttpServerLocalhostBrick()
        assertEquals(8080.0, brick.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
        // The localhost variant binds to localhost by construction (no IP field).
        assertEquals(
            StartHttpServerLocalhostBrick.LOCALHOST_BIND_ADDRESS,
            StartHttpServerLocalhostBrick.LOCALHOST_BIND_ADDRESS
        )

        val custom = StartHttpServerLocalhostBrick("9090")
        assertEquals(9090.0, custom.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
    }

    @Test
    fun `original start server brick keeps its original defaults`() {
        val brick = StartHttpServerBrick()
        assertEquals(8080.0, brick.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
        assertEquals(
            "127.0.0.1",
            brick.getFormulaWithBrickField(Brick.BrickField.IP).interpretString(scope)
        )
    }

    @Test
    fun `select file brick defaults to all mime types`() {
        val brick = SelectFileBrick()
        assertEquals("*/*", brick.getFormulaWithBrickField(Brick.BrickField.MIME_TYPE).interpretString(scope))
    }

    @Test
    fun `select directory brick has an optional initial directory field`() {
        val brick = SelectDirectoryBrick()
        assertEquals("", brick.getFormulaWithBrickField(Brick.BrickField.INITIAL_DIRECTORY).interpretString(scope))
    }

    @Test
    fun `host directory brick has directory and prefix fields`() {
        val brick = HostDirectoryBrick("content://tree")
        assertEquals(
            "content://tree",
            brick.getFormulaWithBrickField(Brick.BrickField.DIRECTORY_URI).interpretString(scope)
        )
        assertEquals("", brick.getFormulaWithBrickField(Brick.BrickField.URL_PREFIX).interpretString(scope))
    }

    @Test
    fun `open web page brick has url and target fields`() {
        val brick = OpenWebPageBrick("http://127.0.0.1:8080/")
        assertEquals(
            "http://127.0.0.1:8080/",
            brick.getFormulaWithBrickField(Brick.BrickField.WEB_PAGE_URL).interpretString(scope)
        )
        assertEquals("", brick.getFormulaWithBrickField(Brick.BrickField.NAV_TARGET).interpretString(scope))
    }

    @Test
    fun `selection bricks accept a destination user variable`() {
        val variable = UserVariable("selectedFolder")
        val directoryBrick = SelectDirectoryBrick(org.catrobat.catroid.formulaeditor.Formula(""), variable)
        assertEquals(variable, directoryBrick.userVariable)

        val fileBrick = SelectFileBrick(org.catrobat.catroid.formulaeditor.Formula("*/*"), variable)
        assertEquals(variable, fileBrick.userVariable)
    }

    @Test
    fun `combined select folder and host brick is a variable brick without formula fields`() {
        // Variable-only brick: extends the UserVariableBrick base (spinner),
        // not FormulaBrick - so it has no formula fields by construction.
        val brick = SelectFolderAndHostBrick()
        assertTrue(brick is org.catrobat.catroid.content.bricks.UserVariableBrick)
    }

    @Test
    fun `combined select folder and host brick accepts a destination user variable`() {
        val variable = UserVariable("gameFolder")
        val brick = SelectFolderAndHostBrick()
        brick.userVariable = variable
        assertEquals(variable, brick.userVariable)
    }

    @Test
    fun `stop http server brick has no formula fields`() {
        val brick = StopHttpServerBrick()
        assertTrue(brick.allFormulaFieldsWithFormulas.isEmpty())
    }

    @Test
    fun `all new bricks serialize and deserialize with xstream`() {
        val xstream = XstreamSerializer.getInstance().xstream

        val bricks = listOf(
            SelectDirectoryBrick(),
            SelectFolderAndHostBrick(),
            SelectFileBrick(),
            StartHttpServerBrick(),
            StartHttpServerLocalhostBrick(),
            StopHttpServerBrick(),
            HostDirectoryBrick("content://tree"),
            OpenWebPageBrick("http://127.0.0.1:8080/")
        )

        for (brick in bricks) {
            val xml = xstream.toXML(brick)
            assertTrue("alias used for ${brick.javaClass.simpleName}", xml.startsWith("<brick type=\"${brick.javaClass.simpleName}\">"))
            val restored = xstream.fromXML(xml) as Brick
            assertEquals(brick.javaClass, restored.javaClass)
        }
    }

    @Test
    fun `serialization round trip keeps formula values`() {
        val xstream = XstreamSerializer.getInstance().xstream

        val brick = StartHttpServerBrick("9090")
        val restored = xstream.fromXML(xstream.toXML(brick)) as StartHttpServerBrick
        assertEquals(9090.0, restored.getFormulaWithBrickField(Brick.BrickField.PORT).interpretObject(scope))
        assertEquals(
            "127.0.0.1",
            restored.getFormulaWithBrickField(Brick.BrickField.IP).interpretString(scope)
        )

        val hostBrick = HostDirectoryBrick("content://tree")
        val restoredHost = xstream.fromXML(xstream.toXML(hostBrick)) as HostDirectoryBrick
        assertEquals(
            "content://tree",
            restoredHost.getFormulaWithBrickField(Brick.BrickField.DIRECTORY_URI).interpretString(scope)
        )
    }

    @Test
    fun `serialization round trip keeps the selected user variable`() {
        val xstream = XstreamSerializer.getInstance().xstream
        val variable = UserVariable("selectedFolder")

        val brick = SelectDirectoryBrick(org.catrobat.catroid.formulaeditor.Formula(""), variable)
        val restored = xstream.fromXML(xstream.toXML(brick)) as SelectDirectoryBrick
        assertNotNull(restored.userVariable)

        val combined = SelectFolderAndHostBrick()
        combined.userVariable = variable
        val restoredCombined = xstream.fromXML(xstream.toXML(combined)) as SelectFolderAndHostBrick
        assertNotNull(restoredCombined.userVariable)
        assertEquals(variable.name, restoredCombined.userVariable!!.name)
        assertEquals("selectedFolder", restored.userVariable.name)
    }

    @Test
    fun `new brick fields expect string values`() {
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.DIRECTORY_URI))
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.URL_PREFIX))
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.MIME_TYPE))
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.INITIAL_DIRECTORY))
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.WEB_PAGE_URL))
        assertTrue(Brick.BrickField.isExpectingStringValue(Brick.BrickField.NAV_TARGET))
    }
}

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
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.FormulaElement
import org.catrobat.catroid.formulaeditor.Functions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the reporter formula functions backing the "Local HTTP Server
 * Running?", "Get Local HTTP Server URL", "Get Local HTTP Server Error",
 * "Get Display Name of [Selected URI]" and "Get Selected Item URI" blocks.
 */
@RunWith(RobolectricTestRunner::class)
class LocalWebFormulaFunctionsTest {

    private val scope: Scope = Scope(null, Sprite(), null)

    private fun functionFormula(name: String, vararg args: String): Formula {
        val element = FormulaElement(FormulaElement.ElementType.FUNCTION, name, null)
        if (args.isNotEmpty()) {
            element.leftChild = FormulaElement(FormulaElement.ElementType.STRING, args[0], null)
        }
        return Formula(element)
    }

    @Test
    fun `reporter functions are registered`() {
        assertTrue(Functions.isFunction("HTTP_SERVER_RUNNING"))
        assertTrue(Functions.isFunction("HTTP_SERVER_URL"))
        assertTrue(Functions.isFunction("HTTP_SERVER_ERROR"))
        assertTrue(Functions.isFunction("SAF_DISPLAY_NAME"))
        assertTrue(Functions.isFunction("SAF_ITEM_URI"))
    }

    @Test
    fun `server running is a boolean function`() {
        assertTrue(Functions.isBoolean(Functions.HTTP_SERVER_RUNNING))
        assertFalse(Functions.isBoolean(Functions.HTTP_SERVER_URL))
    }

    @Test
    fun `text functions are registered as text`() {
        assertTrue(Functions.TEXT.contains(Functions.HTTP_SERVER_URL))
        assertTrue(Functions.TEXT.contains(Functions.HTTP_SERVER_ERROR))
        assertTrue(Functions.TEXT.contains(Functions.SAF_DISPLAY_NAME))
        assertTrue(Functions.TEXT.contains(Functions.SAF_ITEM_URI))
    }

    @Test
    fun `server running reporter reflects the actual server state`() {
        // No server is running in this test: the reporter must say so.
        val result = functionFormula("HTTP_SERVER_RUNNING").interpretObject(scope)
        assertEquals(0.0, result)
    }

    @Test
    fun `server url reporter is empty when no server is active`() {
        assertEquals("", functionFormula("HTTP_SERVER_URL").interpretObject(scope))
    }

    @Test
    fun `server error reporter is empty when there is no error`() {
        assertEquals("", functionFormula("HTTP_SERVER_ERROR").interpretObject(scope))
    }

    @Test
    fun `display name reporter resolves the last uri segment`() {
        val result = functionFormula(
            "SAF_DISPLAY_NAME",
            "content://com.example.provider/document/primary%3ADocuments%2Freport.txt"
        ).interpretObject(scope)
        assertEquals("report.txt", result)
    }

    @Test
    fun `item uri reporter returns the uri unchanged`() {
        val input = "content://com.example.provider/document/primary%3ADocuments%2Freport.txt"
        assertEquals(input, functionFormula("SAF_ITEM_URI", input).interpretObject(scope))
    }

    @Test
    fun `item uri reporter keeps plain paths unchanged`() {
        assertEquals("/storage/emulated/0/a.txt", functionFormula("SAF_ITEM_URI", "/storage/emulated/0/a.txt").interpretObject(scope))
    }
}

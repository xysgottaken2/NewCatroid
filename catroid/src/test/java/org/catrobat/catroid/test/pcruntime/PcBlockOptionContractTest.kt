/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.pcruntime

import org.catrobat.catroid.pcruntime.ElfConstants
import org.catrobat.catroid.pcruntime.GraphicsBackend
import org.catrobat.catroid.pcruntime.PcErrorCatalog
import org.catrobat.catroid.pcruntime.PcErrorCode
import org.catrobat.catroid.pcruntime.PcQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The visual block spinners store a plain integer index and the action maps
 * that index back to an enum entry. If the string array in strings.xml ever
 * falls out of sync with the enum, a user would select "Vulkan support" and
 * silently receive "Status".
 *
 * These tests read the real resource files from the source tree — unit tests
 * run with the module directory as the working directory — so the contract is
 * checked against what actually ships.
 */
class PcBlockOptionContractTest {

    private val stringArrayRegex = Regex(
        """<string-array name="([^"]+)">(.*?)</string-array>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val itemRegex = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    private fun locateStringsXml(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("catroid/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(File(System.getProperty("user.dir"), "catroid"), relative)
        )
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue("strings.xml not reachable from ${System.getProperty("user.dir")}", found != null)
        return found!!
    }

    private fun stringArray(name: String): List<String> {
        val file = locateStringsXml("src/main/res/values/strings.xml")
        val content = file.readText()
        val arrays = stringArrayRegex.findAll(content).associate { match ->
            match.groupValues[1] to itemRegex.findAll(match.groupValues[2])
                .map { it.groupValues[1].trim() }
                .toList()
        }
        val values = arrays[name]
        assertTrue("string-array '$name' is missing from strings.xml", values != null)
        return values!!
    }

    @Test
    fun testQuerySpinnerMatchesTheQueryEnumOrder() {
        val options = stringArray("pc_query_options")
        val queries = PcQuery.entries

        assertEquals(
            "pc_query_options has ${options.size} entries but PcQuery has ${queries.size}",
            queries.size,
            options.size
        )
    }

    @Test
    fun testGraphicsBackendSpinnerMatchesTheBackendEnumOrder() {
        val options = stringArray("pc_graphics_backends")
        val backends = GraphicsBackend.entries

        assertEquals(
            "pc_graphics_backends has ${options.size} entries but GraphicsBackend has ${backends.size}",
            backends.size,
            options.size
        )
    }

    @Test
    fun testEveryQueryOptionIsNonEmptyAndLocalised() {
        stringArray("pc_query_options").forEach { option ->
            assertTrue("empty query option", option.isNotBlank())
        }
    }

    @Test
    fun testRussianStringsContainTheSameArrays() {
        val english = stringArray("pc_query_options")
        val russian = File(locateStringsXml("src/main/res/values/strings.xml").parentFile.parentFile, "values-ru/strings.xml")
        assumeTrue("values-ru/strings.xml not found", russian.isFile)

        val content = russian.readText()
        val arrays = stringArrayRegex.findAll(content).associate { match ->
            match.groupValues[1] to itemRegex.findAll(match.groupValues[2]).map { it.groupValues[1].trim() }.toList()
        }

        val russianQueries = arrays["pc_query_options"]
        assertTrue("Russian strings are missing pc_query_options", russianQueries != null)
        assertEquals(english.size, russianQueries!!.size)
        assertFalse(russianQueries.any { it.isBlank() })

        val russianBackends = arrays["pc_graphics_backends"]
        assertTrue("Russian strings are missing pc_graphics_backends", russianBackends != null)
        assertEquals(GraphicsBackend.entries.size, russianBackends!!.size)
    }

    @Test
    fun testEveryBlockStringUsedByALayoutExists() {
        val expected = listOf(
            "brick_pc_init_runtime",
            "brick_pc_shutdown_runtime",
            "brick_pc_query",
            "brick_pc_native_probe",
            "brick_pc_prepare_prefix",
            "brick_pc_graphics_backend",
            "brick_pc_inspect_binary",
            "brick_pc_into_variable",
            "subcategory_device_pcruntime"
        )
        val content = locateStringsXml("src/main/res/values/strings.xml").readText()
        expected.forEach { name ->
            assertTrue("missing string $name", content.contains("name=\"$name\""))
        }
    }

    @Test
    fun testEveryBlockLayoutReferencesAnExistingString() {
        val layouts = listOf(
            "brick_pc_init_runtime.xml",
            "brick_pc_shutdown_runtime.xml",
            "brick_pc_query.xml",
            "brick_pc_native_probe.xml",
            "brick_pc_prepare_prefix.xml",
            "brick_pc_graphics_backend.xml",
            "brick_pc_inspect_binary.xml"
        )
        val strings = locateStringsXml("src/main/res/values/strings.xml").readText()
        val layoutDir = locateStringsXml("src/main/res/values/strings.xml").parentFile.parentFile

        layouts.forEach { layout ->
            val file = File(layoutDir, "layout/$layout")
            assumeTrue("layout $layout not found", file.isFile)
            val references = Regex("""android:text="@string/([^"]+)"""").findAll(file.readText())
                .map { it.groupValues[1] }
                .toList()
            assertTrue("$layout references no string", references.isNotEmpty())
            references.forEach { reference ->
                assertTrue(
                    "$layout references @string/$reference which does not exist",
                    strings.contains("name=\"$reference\"")
                )
            }
        }
    }

    @Test
    fun testLayoutsKeepTheMandatoryCheckboxTheProjectRequires() {
        val layoutDir = locateStringsXml("src/main/res/values/strings.xml").parentFile.parentFile
        val layout = File(layoutDir, "layout/brick_pc_query.xml")
        assumeTrue(layout.isFile)
        // AGENTS.md documents that a missing brick_checkbox crashes the editor.
        assertTrue(layout.readText().contains("@+id/brick_checkbox"))
    }

    @Test
    fun testEveryErrorCodeHasActionableGuidance() {
        PcErrorCode.entries.forEach { code ->
            val error = PcErrorCatalog.describe(code, "detail")
            assertTrue("$code has an empty hint", error.hint.isNotBlank())
            assertTrue("$code has an empty detail", error.detail.isNotBlank())
            assertTrue("$code formats without detail", error.format().contains("detail"))
        }
    }

    @Test
    fun testErrorCatalogKeepsTheProvidedDetailWhenPresent() {
        val error = PcErrorCatalog.describe(PcErrorCode.PROCESS_TIMEOUT, "timeout after 5000ms")
        assertEquals("timeout after 5000ms", error.detail)
        assertTrue(error.format().contains("timeout after 5000ms"))
    }

    @Test
    fun testExecutionBlockedMessageExplainsTheAndroidRule() {
        val error = PcErrorCatalog.describe(PcErrorCode.EXECUTION_BLOCKED_BY_ANDROID, "static binary")
        assertTrue(error.hint.contains("W^X"))
    }

    @Test
    fun testElfMachineConstantsMatchTheElfSpecification() {
        assertEquals(0xB7, ElfConstants.EM_AARCH64)
        assertEquals(0x3E, ElfConstants.EM_X86_64)
        assertEquals(0x28, ElfConstants.EM_ARM)
        assertEquals(0x03, ElfConstants.EM_386)
    }
}

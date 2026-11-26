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
 * An additional term exception under section 7 of the GNU Affero General
 * Public License, version 3, is available at
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

package org.catrobat.catroid.test.apkbuild

import org.catrobat.catroid.apkbuild.BrickCoverage
import org.catrobat.catroid.apkbuild.ProjectScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for the "exported APK is black and then closes" report.
 *
 * An exported APK is a finished template APK with a replaced `assets/project.zip`;
 * it is never compiled. A brick that the template was not built with therefore
 * cannot be loaded by XStream, the project fails to load, and the exported app
 * stops right after its splash screen. The build dialog now refuses to produce
 * such an APK, which requires two things to be reliable:
 *
 *  - the list of classes a project uses ([ProjectScanner.referencedClasses]),
 *  - the answer whether an APK contains them ([BrickCoverage.check]).
 */
class BrickCoverageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun projectBricksAndScriptsAreListedOnceAndInOrder() {
        val projectDir = temporaryFolder.newFolder("project")
        File(projectDir, "code.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <program>
              <scenes>
                <scene>
                  <objectList>
                    <object type="Sprite" name="Background">
                      <scriptList>
                        <script type="StartScript" posX="0.0" posY="0.0">
                          <brickList>
                            <brick type="PcLaunchGameBrick">
                              <brickId>1</brickId>
                            </brick>
                            <brick type="PcLaunchGameBrick">
                              <brickId>2</brickId>
                            </brick>
                            <brick type="SetVariableBrick"/>
                          </brickList>
                        </script>
                      </scriptList>
                    </object>
                  </objectList>
                </scene>
              </scenes>
            </program>
            """.trimIndent()
        )

        assertEquals(
            listOf("StartScript", "PcLaunchGameBrick", "SetVariableBrick"),
            ProjectScanner.referencedClasses(projectDir)
        )
    }

    @Test
    fun aProjectWithoutCodeXmlHasNoReferencedClasses() {
        val projectDir = temporaryFolder.newFolder("empty-project")
        assertTrue(ProjectScanner.referencedClasses(projectDir).isEmpty())
    }

    @Test
    fun aTemplateThatContainsEveryClassIsAccepted() {
        val apk = apkWithDex(
            "dex-content before PcLaunchGameBrick and SetVariableBrick after"
        )

        val result = BrickCoverage.check(apk, listOf("PcLaunchGameBrick", "SetVariableBrick"))

        assertTrue(result.isComplete)
        assertTrue(result.missing.isEmpty())
        assertEquals(2, result.checked.size)
    }

    @Test
    fun aTemplateBuiltWithoutTheBricksIsReportedAsIncomplete() {
        // What an old template looks like: the base bricks exist, the fork-only
        // ones do not. This is the exact case that produced the black screen.
        val apk = apkWithDex("dex with SetVariableBrick only")

        val result = BrickCoverage.check(apk, listOf("SetVariableBrick", "PcLaunchGameBrick"))

        assertFalse(result.isComplete)
        assertEquals(listOf("PcLaunchGameBrick"), result.missing)
        assertEquals("PcLaunchGameBrick", result.missingSummary())
    }

    @Test
    fun aClassSplitBetweenTwoReadsIsStillFound() {
        // `search` keeps the tail of the previous window, so a name that straddles
        // the chunk boundary must not be lost - a 300 MB template is read in
        // chunks this way.
        val marker = "PcDownloadPackBrick"
        val padding = "x".repeat(300_000)
        val stream = ByteArrayInputStream(("$padding$marker$padding").toByteArray())
        val found = BrickCoverage.search(stream, listOf(marker))
        assertEquals(setOf(marker), found)
    }

    @Test
    fun everyDexFileIsSearched() {
        val file = temporaryFolder.newFile("classes-multi.dex.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("nothing useful here".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes2.dex"))
            zip.write("PcRamCheckBrick".toByteArray())
            zip.closeEntry()
        }

        assertTrue(BrickCoverage.check(file, listOf("PcRamCheckBrick")).isComplete)
    }

    @Test
    fun anUnreadableApkReportsEverythingAsMissing() {
        val notAnApk = temporaryFolder.newFile("broken.apk")
        notAnApk.writeText("this is not a zip file")

        val result = BrickCoverage.check(notAnApk, listOf("PcLaunchGameBrick"))

        assertFalse(result.isComplete)
        assertEquals(listOf("PcLaunchGameBrick"), result.missing)
    }

    @Test
    fun duplicatesAndBlankNamesAreIgnored() {
        val apk = apkWithDex("PcShowLauncherBrick")
        val result = BrickCoverage.check(apk, listOf("PcShowLauncherBrick", " PcShowLauncherBrick ", ""))
        assertEquals(listOf("PcShowLauncherBrick"), result.checked)
        assertTrue(result.isComplete)
    }

    private fun apkWithDex(content: String): File {
        val file = temporaryFolder.newFile("template-${System.nanoTime()}.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return file
    }
}

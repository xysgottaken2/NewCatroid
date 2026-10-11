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

import android.Manifest
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * Shared Robolectric environment for the SAF hosting tests: registers
 * [FakeGameDocumentsProvider] (with a properly attached `ProviderInfo`, as
 * `DocumentsProvider` builds its URI matcher in `attachInfo`) and registers
 * input-stream suppliers for every fake document URI (Robolectric's
 * `openInputStream` shadow serves registered suppliers instead of providers).
 */
object SafTestEnvironment {

    val treeUri: Uri
        get() = Uri.parse(FakeGameDocumentsProvider.TREE_URI)

    fun registerFakeProvider() {
        val provider = FakeGameDocumentsProvider()
        val info = ProviderInfo()
        info.authority = FakeGameDocumentsProvider.AUTHORITY
        info.name = FakeGameDocumentsProvider::class.java.name
        // DocumentsProvider.attachInfo enforces the same manifest rules a
        // real documents provider must satisfy.
        info.exported = true
        info.grantUriPermissions = true
        info.readPermission = Manifest.permission.MANAGE_DOCUMENTS
        info.writePermission = Manifest.permission.MANAGE_DOCUMENTS
        provider.attachInfo(RuntimeEnvironment.getApplication(), info)
        ShadowContentResolver.registerProviderInternal(FakeGameDocumentsProvider.AUTHORITY, provider)
    }

    fun registerDocumentStreams() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for ((documentId, content) in FakeGameDocumentsProvider.allFiles) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(content) })
        }
    }

    fun register() {
        registerFakeProvider()
        registerDocumentStreams()
    }
}

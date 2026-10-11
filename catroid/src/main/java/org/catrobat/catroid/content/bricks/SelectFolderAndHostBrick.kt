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

package org.catrobat.catroid.content.bricks

import org.catrobat.catroid.R
import org.catrobat.catroid.content.Sprite
import org.catrobat.catroid.content.actions.ScriptSequenceAction

/**
 * "Select Folder, Save to Variable and Host".
 *
 * Combined block for the local game-hosting workflow: opens the system
 * directory picker, stores the selected SAF tree URI in the chosen user
 * variable AND configures the embedded HTTP server to serve that same
 * folder at the "/" URL prefix - atomically, without depending on a separate
 * "Host Directory" block or on formula evaluation passing the value along.
 *
 * Cancelling the picker keeps the previous variable value and the previous
 * server configuration. Starting the server and opening the game remain
 * separate blocks.
 */
class SelectFolderAndHostBrick : UserVariableBrick() {

    constructor() : super()

    override fun getViewResource(): Int = R.layout.brick_select_folder_and_host

    override fun getSpinnerId(): Int = R.id.brick_select_folder_and_host_var_spinner

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        // The action is always added, even without a chosen variable: hosting
        // still works and a missing variable is reported as an error.
        sequence.addAction(
            sprite.actionFactory.createSelectFolderAndHostAction(sprite, sequence, userVariable)
        )
    }
}

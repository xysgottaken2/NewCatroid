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

package org.catrobat.catroid.content.bricks

import org.catrobat.catroid.R
import org.catrobat.catroid.content.Sprite
import org.catrobat.catroid.content.actions.ScriptSequenceAction
import org.catrobat.catroid.content.bricks.Brick.BrickField
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable

/**
 * "Select Directory and Store in [Variable]".
 *
 * Opens the system directory picker (Storage Access Framework,
 * `ACTION_OPEN_DOCUMENT_TREE`) and stores the selected directory URI in the
 * chosen user variable. The block waits until the picker is closed; when the
 * picker is cancelled the variable keeps its previous value.
 */
class SelectDirectoryBrick : UserVariableBrickWithFormula {

    constructor() : super() {
        addAllowedBrickField(BrickField.INITIAL_DIRECTORY, R.id.brick_select_directory_edit_initial)
        setFormulaWithBrickField(BrickField.INITIAL_DIRECTORY, Formula(""))
    }

    constructor(initialDirectory: String) : this(Formula(initialDirectory))

    constructor(initialDirectory: Formula) : this() {
        setFormulaWithBrickField(BrickField.INITIAL_DIRECTORY, initialDirectory)
    }

    constructor(initialDirectory: Formula, userVariable: UserVariable) : this(initialDirectory) {
        this.userVariable = userVariable
    }

    override fun getViewResource(): Int = R.layout.brick_select_directory

    override fun getSpinnerId(): Int = R.id.brick_select_directory_var_spinner

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        userVariable?.let { variable ->
            sequence.addAction(
                sprite.actionFactory.createSelectDirectoryAction(
                    sprite, sequence,
                    getFormulaWithBrickField(BrickField.INITIAL_DIRECTORY), variable
                )
            )
        }
    }
}

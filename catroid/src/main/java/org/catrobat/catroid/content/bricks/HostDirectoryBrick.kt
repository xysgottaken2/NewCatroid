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

/**
 * "Host Directory at [Selected Directory]".
 *
 * Configures the directory served by the embedded HTTP server. Typically
 * fed with the variable filled by the "Select Directory" block (a
 * `content://` document tree URI). An optional URL prefix can be given.
 * This block only configures the server; use "Start Local HTTP Server" to
 * start it.
 */
class HostDirectoryBrick : FormulaBrick {

    constructor() : super() {
        addAllowedBrickField(BrickField.DIRECTORY_URI, R.id.brick_host_directory_edit_dir)
        addAllowedBrickField(BrickField.URL_PREFIX, R.id.brick_host_directory_edit_prefix)
        setFormulaWithBrickField(BrickField.DIRECTORY_URI, Formula(""))
        setFormulaWithBrickField(BrickField.URL_PREFIX, Formula(""))
    }

    constructor(directory: String) : this(Formula(directory))

    constructor(directory: Formula) : this() {
        setFormulaWithBrickField(BrickField.DIRECTORY_URI, directory)
    }

    constructor(directory: Formula, urlPrefix: Formula) : this() {
        setFormulaWithBrickField(BrickField.DIRECTORY_URI, directory)
        setFormulaWithBrickField(BrickField.URL_PREFIX, urlPrefix)
    }

    override fun getViewResource(): Int = R.layout.brick_host_directory

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        sequence.addAction(
            sprite.actionFactory.createHostDirectoryAction(
                sprite, sequence,
                getFormulaWithBrickField(BrickField.DIRECTORY_URI),
                getFormulaWithBrickField(BrickField.URL_PREFIX)
            )
        )
    }
}

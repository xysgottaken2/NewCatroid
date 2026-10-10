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
 * "Start Local HTTP Server on Port [8080]".
 *
 * Starts the embedded HTTP server on the given port. The optional bind
 * address defaults to `127.0.0.1` (loopback only); set it to `0.0.0.0`
 * explicitly to expose the server on the LAN. The block finishes once the
 * server has started or failed; the outcome is observable through the
 * "local HTTP server running?", "local HTTP server URL" and
 * "local HTTP server error" reporters.
 */
class StartHttpServerBrick : FormulaBrick {

    constructor() : super() {
        addAllowedBrickField(BrickField.PORT, R.id.brick_start_http_server_edit_port)
        addAllowedBrickField(BrickField.IP, R.id.brick_start_http_server_edit_bind)
        setFormulaWithBrickField(BrickField.PORT, Formula(8080))
        setFormulaWithBrickField(BrickField.IP, Formula("127.0.0.1"))
    }

    constructor(port: String) : this(Formula(port))

    constructor(port: Formula) : this() {
        setFormulaWithBrickField(BrickField.PORT, port)
    }

    constructor(port: Formula, bindAddress: Formula) : this() {
        setFormulaWithBrickField(BrickField.PORT, port)
        setFormulaWithBrickField(BrickField.IP, bindAddress)
    }

    override fun getViewResource(): Int = R.layout.brick_start_http_server

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        sequence.addAction(
            sprite.actionFactory.createStartHttpServerAction(
                sprite, sequence,
                getFormulaWithBrickField(BrickField.PORT),
                getFormulaWithBrickField(BrickField.IP)
            )
        )
    }
}

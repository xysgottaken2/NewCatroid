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
import org.catrobat.catroid.content.bricks.Brick.BrickField
import org.catrobat.catroid.formulaeditor.Formula

/**
 * "Start Local HTTP Server on Port (localhost) [8080]".
 *
 * Variant of [StartHttpServerBrick] that always binds to the loopback
 * interface under the name `localhost`, so the server URL is generated as
 * `http://localhost:PORT/` (e.g. `http://localhost:8080/`). Browsers treat
 * `localhost` as a secure, potentially-trustworthy origin, which web
 * applications that need cross-origin isolation (SharedArrayBuffer, WASM
 * threads, WebGPU) require - combined with the COOP/COEP response headers the
 * server sends. The original block is unchanged and keeps its configurable
 * bind address.
 */
class StartHttpServerLocalhostBrick : FormulaBrick {

    constructor() : super() {
        addAllowedBrickField(BrickField.PORT, R.id.brick_start_http_server_localhost_edit_port)
        setFormulaWithBrickField(BrickField.PORT, Formula(DEFAULT_PORT.toDouble()))
    }

    constructor(port: String) : this(Formula(port.toDoubleOrNull() ?: DEFAULT_PORT.toDouble()))

    constructor(port: Formula) : this() {
        setFormulaWithBrickField(BrickField.PORT, port)
    }

    override fun getViewResource(): Int = R.layout.brick_start_http_server_localhost

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        sequence.addAction(
            sprite.actionFactory.createStartHttpServerAction(
                sprite, sequence,
                getFormulaWithBrickField(BrickField.PORT),
                Formula(LOCALHOST_BIND_ADDRESS)
            )
        )
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val LOCALHOST_BIND_ADDRESS = "localhost"
    }
}

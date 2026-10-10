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
import org.catrobat.catroid.content.bricks.Brick.ResourcesSet
import org.catrobat.catroid.formulaeditor.Formula

/**
 * "Open Web Page at [URL]".
 *
 * Opens a URL in the in-app WebView so pages served by the embedded local
 * HTTP server can be displayed. Navigation is restricted to local (loopback)
 * origins by default. When a target name is given, the URL is loaded into the
 * named in-stage WebView from the "Create WebView" blocks instead.
 */
class OpenWebPageBrick : FormulaBrick {

    constructor() : super() {
        addAllowedBrickField(BrickField.WEB_PAGE_URL, R.id.brick_open_web_page_edit_url)
        addAllowedBrickField(BrickField.NAV_TARGET, R.id.brick_open_web_page_edit_target)
        setFormulaWithBrickField(BrickField.WEB_PAGE_URL, Formula("http://127.0.0.1:8080/"))
        setFormulaWithBrickField(BrickField.NAV_TARGET, Formula(""))
    }

    constructor(url: String) : this(Formula(url))

    constructor(url: Formula) : this() {
        setFormulaWithBrickField(BrickField.WEB_PAGE_URL, url)
    }

    constructor(url: Formula, target: Formula) : this() {
        setFormulaWithBrickField(BrickField.WEB_PAGE_URL, url)
        setFormulaWithBrickField(BrickField.NAV_TARGET, target)
    }

    override fun getViewResource(): Int = R.layout.brick_open_web_page

    override fun addActionToSequence(sprite: Sprite, sequence: ScriptSequenceAction) {
        sequence.addAction(
            sprite.actionFactory.createOpenWebPageAction(
                sprite, sequence,
                getFormulaWithBrickField(BrickField.WEB_PAGE_URL),
                getFormulaWithBrickField(BrickField.NAV_TARGET)
            )
        )
    }

    override fun addRequiredResources(requiredResourcesSet: ResourcesSet) {
        requiredResourcesSet.add(Brick.NETWORK_CONNECTION)
        super.addRequiredResources(requiredResourcesSet)
    }
}

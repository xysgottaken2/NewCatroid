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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;
import org.catrobat.catroid.formulaeditor.Formula;

public class PcSetResolutionBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcSetResolutionBrick() {
        addAllowedBrickField(BrickField.PC_SCREEN_WIDTH, R.id.brick_pc_resolution_width);
        addAllowedBrickField(BrickField.PC_SCREEN_HEIGHT, R.id.brick_pc_resolution_height);
    }

    public PcSetResolutionBrick(String width, String height) {
        this(new Formula(width), new Formula(height));
    }

    public PcSetResolutionBrick(Formula width, Formula height) {
        this();
        setFormulaWithBrickField(BrickField.PC_SCREEN_WIDTH, width);
        setFormulaWithBrickField(BrickField.PC_SCREEN_HEIGHT, height);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_set_resolution;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcSetResolutionAction(sprite, sequence, getFormulaWithBrickField(BrickField.PC_SCREEN_WIDTH), getFormulaWithBrickField(BrickField.PC_SCREEN_HEIGHT)));
    }
}

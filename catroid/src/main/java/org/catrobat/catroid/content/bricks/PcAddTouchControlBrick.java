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

public class PcAddTouchControlBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcAddTouchControlBrick() {
        addAllowedBrickField(BrickField.PC_CONTROL_LABEL, R.id.brick_pc_touch_label);
        addAllowedBrickField(BrickField.PC_CONTROL_KEYS, R.id.brick_pc_touch_keys);
        addAllowedBrickField(BrickField.PC_CONTROL_X, R.id.brick_pc_touch_x);
        addAllowedBrickField(BrickField.PC_CONTROL_Y, R.id.brick_pc_touch_y);
        addAllowedBrickField(BrickField.PC_CONTROL_SIZE, R.id.brick_pc_touch_size);
    }

    public PcAddTouchControlBrick(String label, String keys, String x, String y, String size) {
        this(new Formula(label), new Formula(keys), new Formula(x), new Formula(y), new Formula(size));
    }

    public PcAddTouchControlBrick(Formula label, Formula keys, Formula x, Formula y, Formula size) {
        this();
        setFormulaWithBrickField(BrickField.PC_CONTROL_LABEL, label);
        setFormulaWithBrickField(BrickField.PC_CONTROL_KEYS, keys);
        setFormulaWithBrickField(BrickField.PC_CONTROL_X, x);
        setFormulaWithBrickField(BrickField.PC_CONTROL_Y, y);
        setFormulaWithBrickField(BrickField.PC_CONTROL_SIZE, size);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_add_touch_control;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcAddTouchControlAction(sprite, sequence, getFormulaWithBrickField(BrickField.PC_CONTROL_LABEL), getFormulaWithBrickField(BrickField.PC_CONTROL_KEYS), getFormulaWithBrickField(BrickField.PC_CONTROL_X), getFormulaWithBrickField(BrickField.PC_CONTROL_Y), getFormulaWithBrickField(BrickField.PC_CONTROL_SIZE)));
    }
}

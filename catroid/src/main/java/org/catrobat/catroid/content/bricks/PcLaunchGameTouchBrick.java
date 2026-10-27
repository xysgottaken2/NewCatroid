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

public class PcLaunchGameTouchBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcLaunchGameTouchBrick() {
        addAllowedBrickField(BrickField.PC_EXE_TOUCH, R.id.brick_pc_launch_touch_exe);
        addAllowedBrickField(BrickField.PC_ARGS_TOUCH, R.id.brick_pc_launch_touch_args);
        addAllowedBrickField(BrickField.PC_TOUCH_PROFILE, R.id.brick_pc_launch_touch_profile);
    }

    public PcLaunchGameTouchBrick(String executable, String arguments, String touchProfile) {
        this(new Formula(executable), new Formula(arguments), new Formula(touchProfile));
    }

    public PcLaunchGameTouchBrick(Formula executable, Formula arguments, Formula touchProfile) {
        this();
        setFormulaWithBrickField(BrickField.PC_EXE_TOUCH, executable);
        setFormulaWithBrickField(BrickField.PC_ARGS_TOUCH, arguments);
        setFormulaWithBrickField(BrickField.PC_TOUCH_PROFILE, touchProfile);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_launch_game_touch;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcLaunchGameTouchAction(sprite, sequence, getFormulaWithBrickField(BrickField.PC_EXE_TOUCH), getFormulaWithBrickField(BrickField.PC_ARGS_TOUCH), getFormulaWithBrickField(BrickField.PC_TOUCH_PROFILE)));
    }
}

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
 * http://www.catrobat.org/license_additional_term
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
import org.catrobat.catroid.formulaeditor.UserVariable;

/**
 * Writes `YES` into the variable when the device can provide the required amount
 * of RAM, otherwise `NO` - the "can this phone run it at all" question a launcher
 * asks before it starts Wine.
 *
 * The last field decides what is compared: `total` (only the physical RAM),
 * `free` (only what is free right now) or `both` (the default).
 */
public class PcRamCheckBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    public PcRamCheckBrick() {
        this(null, new Formula("6000"), new Formula("both"));
    }

    public PcRamCheckBrick(UserVariable variable, String requiredMb, String mode) {
        this(variable, new Formula(requiredMb), new Formula(mode));
    }

    public PcRamCheckBrick(UserVariable variable, Formula requiredMb, Formula mode) {
        this.userVariable = variable;
        addAllowedBrickField(BrickField.PC_RAM_REQUIRED, R.id.brick_pc_ram_check_mb);
        addAllowedBrickField(BrickField.PC_RAM_MODE, R.id.brick_pc_ram_check_mode);
        setFormulaWithBrickField(BrickField.PC_RAM_REQUIRED, requiredMb);
        setFormulaWithBrickField(BrickField.PC_RAM_MODE, mode);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_ram_check;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_ram_check_variable_spinner;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory().createPcRamCheckAction(
                sprite,
                sequence,
                userVariable,
                getFormulaWithBrickField(BrickField.PC_RAM_REQUIRED),
                getFormulaWithBrickField(BrickField.PC_RAM_MODE)));
    }
}

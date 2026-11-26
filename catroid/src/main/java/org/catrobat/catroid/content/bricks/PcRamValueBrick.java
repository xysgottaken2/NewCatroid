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
 * Stores one memory value of the device in the chosen variable.
 *
 * The value field accepts `total`, `available` (free right now), `used`,
 * `percent`, `app` (the heap limit of NewCatroid) and `low` (1 when Android
 * reports that the device is running out of memory). Everything is in MB, the
 * percentage is 0-100.
 *
 * The same numbers are available as formula sensors; this brick is the shortcut
 * for "put it into a variable".
 */
public class PcRamValueBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    public PcRamValueBrick() {
        this(null, new Formula("total"));
    }

    public PcRamValueBrick(UserVariable variable, String kind) {
        this(variable, new Formula(kind));
    }

    public PcRamValueBrick(UserVariable variable, Formula kind) {
        this.userVariable = variable;
        addAllowedBrickField(BrickField.PC_MEMORY_KIND, R.id.brick_pc_ram_value_kind);
        setFormulaWithBrickField(BrickField.PC_MEMORY_KIND, kind);
    }

    @Override
    public int getViewResource() {
        return R.layout.pc_ram_value;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_ram_value_variable_spinner;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory().createPcDeviceMemoryAction(
                sprite,
                sequence,
                userVariable,
                getFormulaWithBrickField(BrickField.PC_MEMORY_KIND)));
    }
}

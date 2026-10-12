package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;
import org.catrobat.catroid.formulaeditor.Formula;
import org.catrobat.catroid.formulaeditor.UserVariable;

/**
 * Inspects a native file without running it and reports what it is and whether
 * this device could execute it.
 *
 * This is the safe counterpart of the launch machinery: it exists so users can
 * verify a downloaded Box64 or DXVK build before anything tries to run it. No
 * file chosen by the user is ever executed automatically.
 */
public class PcInspectBinaryBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    public PcInspectBinaryBrick() {
        addAllowedBrickField(BrickField.FILENAME, R.id.brick_pc_inspect_path_edit);
    }

    public PcInspectBinaryBrick(String path) {
        this(new Formula(path));
    }

    public PcInspectBinaryBrick(Formula path) {
        this();
        setFormulaWithBrickField(BrickField.FILENAME, path);
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_inspect_binary;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_inspect_variable_spinner;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcInspectBinaryAction(sprite, sequence,
                        getFormulaWithBrickField(BrickField.FILENAME), userVariable));
    }
}

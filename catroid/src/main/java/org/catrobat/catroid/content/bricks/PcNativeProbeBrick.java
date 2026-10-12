package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;

import org.catrobat.catroid.formulaeditor.UserVariable;

/**
 * Runs the bundled AArch64 helper binary as a separate process and stores its
 * output and exit status in a user variable.
 *
 * This is the runtime's own end-to-end proof that a native executable can be
 * started from the app on this device. If the helper is not packaged, or the
 * device forbids the launch, the variable contains the concrete reason instead
 * of a success message.
 */
public class PcNativeProbeBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    public PcNativeProbeBrick() {
        this(null);
    }

    public PcNativeProbeBrick(UserVariable variable) {
        this.userVariable = variable;
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_native_probe;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_native_probe_variable_spinner;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcNativeProbeAction(sprite, sequence, userVariable));
    }
}

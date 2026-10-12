package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;

/**
 * Releases the PC runtime. Measurements are dropped; the component files and
 * prepared prefixes on disk are deliberately left alone.
 */
public class PcShutdownRuntimeBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcShutdownRuntimeBrick() {
        // No configurable fields.
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_shutdown_runtime;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory().createPcShutdownRuntimeAction(sprite, sequence));
    }
}

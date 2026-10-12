package org.catrobat.catroid.content.bricks;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;

/**
 * Starts the PC runtime: detects the device architecture, probes the Vulkan
 * driver and verifies which runtime components are installed.
 */
public class PcInitRuntimeBrick extends FormulaBrick {
    private static final long serialVersionUID = 1L;

    public PcInitRuntimeBrick() {
        // No configurable fields: the runtime detects everything it can.
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_init_runtime;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory().createPcInitRuntimeAction(sprite, sequence));
    }
}

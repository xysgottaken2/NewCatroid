package org.catrobat.catroid.content.bricks;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import org.catrobat.catroid.R;
import org.catrobat.catroid.content.Sprite;
import org.catrobat.catroid.content.actions.ScriptSequenceAction;

/**
 * Selects a Direct3D translation backend and reports whether the device's
 * Vulkan driver actually meets the requirements that the upstream project
 * documents. Selecting a backend never enables it: the report is a verdict from
 * real driver data, and a backend whose requirements fail stays unusable.
 */
public class PcGraphicsBackendBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    private int backendSelection = 0;

    public PcGraphicsBackendBrick() {
        // Selection comes from the preset spinner.
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_graphics_backend;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_graphics_variable_spinner;
    }

    @Override
    public View getView(Context context) {
        super.getView(context);

        Spinner backendSpinner = view.findViewById(R.id.brick_pc_graphics_spinner);
        if (backendSpinner != null) {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    context, R.array.pc_graphics_backends, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            backendSpinner.setAdapter(adapter);
            backendSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                    backendSelection = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // Keep the previous selection.
                }
            });
            backendSpinner.setSelection(backendSelection);
        }
        return view;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcGraphicsBackendAction(sprite, sequence, backendSelection, userVariable));
    }
}

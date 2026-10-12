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
 * Reads one piece of runtime information into a user variable.
 *
 * The spinner index maps directly onto {@code PcQuery.entries}, and the string
 * array in {@code strings.xml} is written in the same order, so the three
 * cannot disagree without the unit test in {@code PcQueryOptionsTest} noticing.
 */
public class PcQueryBrick extends UserVariableBrickWithFormula {
    private static final long serialVersionUID = 1L;

    private int querySelection = 0;

    public PcQueryBrick() {
        // Variable is chosen through the spinner; query through the preset spinner.
    }

    @Override
    public int getViewResource() {
        return R.layout.brick_pc_query;
    }

    @Override
    protected int getSpinnerId() {
        return R.id.brick_pc_query_variable_spinner;
    }

    @Override
    public View getView(Context context) {
        super.getView(context);

        Spinner querySpinner = view.findViewById(R.id.brick_pc_query_spinner);
        if (querySpinner != null) {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    context, R.array.pc_query_options, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            querySpinner.setAdapter(adapter);
            querySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                    querySelection = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // Keep the previous selection.
                }
            });
            querySpinner.setSelection(querySelection);
        }
        return view;
    }

    @Override
    public void addActionToSequence(Sprite sprite, ScriptSequenceAction sequence) {
        sequence.addAction(sprite.getActionFactory()
                .createPcQueryAction(sprite, sequence, querySelection, userVariable));
    }
}

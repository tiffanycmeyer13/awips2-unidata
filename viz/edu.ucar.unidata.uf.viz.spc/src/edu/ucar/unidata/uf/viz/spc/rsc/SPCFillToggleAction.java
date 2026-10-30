package edu.ucar.unidata.uf.viz.spc.rsc;

import com.raytheon.viz.ui.cmenu.AbstractRightClickAction;

/**
 * Toggle rendering of SPC risk fill polygons from the resource context menu.
 */
public class SPCFillToggleAction extends AbstractRightClickAction {

    public SPCFillToggleAction() {
        super("Show Risk Fill", AS_CHECK_BOX);
    }

    @Override
    public boolean isHidden() {
        if (!(getSelectedRsc() instanceof SPCResource)) {
            return true;
        }
        setChecked(((SPCResource) getSelectedRsc()).isFillEnabled());
        return false;
    }

    @Override
    public void run() {
        if (getSelectedRsc() instanceof SPCResource) {
            ((SPCResource) getSelectedRsc()).setFillEnabled(isChecked());
        }
    }
}

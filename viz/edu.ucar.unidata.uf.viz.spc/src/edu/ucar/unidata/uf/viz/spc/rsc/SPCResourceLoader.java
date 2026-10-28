package edu.ucar.unidata.uf.viz.spc.rsc;

import java.util.HashMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.jface.window.Window;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.common.dataquery.requests.RequestConstraint;
import com.raytheon.uf.common.time.BinOffset;
import com.raytheon.uf.viz.core.IDisplayPane;
import com.raytheon.uf.viz.core.IDisplayPaneContainer;
import com.raytheon.uf.viz.core.drawables.ResourcePair;
import com.raytheon.uf.viz.core.rsc.LoadProperties;
import com.raytheon.uf.viz.core.rsc.ResourceProperties;

import edu.ucar.unidata.common.dataplugin.spc.SPCRecord;

/**
 * Loads a selected SPC Convective Outlook product.
 */
public class SPCResourceLoader extends AbstractHandler {

    private static final String[] PRODUCTS = {
            "Convective Outlook (overview)", "Tornado Outlook",
            "Hail Outlook", "Wind Outlook" };

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null || window.getShell() == null) {
            return null;
        }

        ElementListSelectionDialog dialog = new ElementListSelectionDialog(
                window.getShell(), new LabelProvider());
        dialog.setTitle("SPC Convective Outlook");
        dialog.setMessage("Select the outlook product to display:");
        dialog.setElements(PRODUCTS);
        if (dialog.open() != Window.OK) {
            return null;
        }

        String selected = (String) dialog.getFirstResult();
        if (selected == null) {
            return null;
        }
        String reportType = selected.startsWith("Convective Outlook")
                ? "Convective Outlook" : selected;

        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (!(editor instanceof IDisplayPaneContainer)) {
            return null;
        }

        IDisplayPaneContainer container = (IDisplayPaneContainer) editor;
        for (IDisplayPane pane : container.getDisplayPanes()) {
            HashMap<String, RequestConstraint> metadataMap = new HashMap<>();
            metadataMap.put(PluginDataObject.PLUGIN_NAME_ID,
                    new RequestConstraint(SPCRecord.PLUGIN_NAME));
            metadataMap.put("reportType", new RequestConstraint(reportType));

            SPCResourceData data = new SPCResourceData();
            data.setMetadataMap(metadataMap);
            data.setBinOffset(new BinOffset(1800, 1800));

            LoadProperties loadProperties = new LoadProperties();
            ResourceProperties resourceProperties = new ResourceProperties();
            ResourcePair pair = new ResourcePair();
            pair.setResourceData(data);
            pair.setLoadProperties(loadProperties);
            pair.setProperties(resourceProperties);

            pane.getDescriptor().getResourceList().add(pair);
            pane.getDescriptor().getResourceList()
                    .instantiateResources(pane.getDescriptor(), true);
        }
        return null;
    }
}

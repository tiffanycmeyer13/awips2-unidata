package edu.ucar.unidata.uf.viz.spc.rsc;

import java.util.ArrayList;
import java.util.List;

import com.raytheon.uf.viz.core.rsc.LoadProperties;
import com.raytheon.uf.viz.productbrowser.AbstractRequestableProductBrowserDataDefinition;
import com.raytheon.uf.viz.productbrowser.ProductBrowserLabel;

/**
 * Product Browser definition for SPC Convective Outlooks.
 */
public class SPCProductBrowserDataDefinition
        extends AbstractRequestableProductBrowserDataDefinition<SPCResourceData> {

    public SPCProductBrowserDataDefinition() {
        productName = "spc";
        displayName = "SPC Outlooks";
        order = new String[] { "outlookDay", "reportType" };
        order = getOrder();

        loadProperties = new LoadProperties();
        loadProperties.setResourceType(getResourceType());
    }

    @Override
    public List<ProductBrowserLabel> formatData(String param,
            String[] parameters) {
        List<ProductBrowserLabel> labels = new ArrayList<>();
        if ("outlookDay".equals(param)) {
            for (String day : parameters) {
                labels.add(new ProductBrowserLabel("Day " + day, day));
            }
        } else if ("reportType".equals(param)) {
            for (String reportType : parameters) {
                labels.add(new ProductBrowserLabel(reportType, reportType));
            }
        }
        return labels;
    }

    @Override
    public SPCResourceData getResourceData() {
        return new SPCResourceData();
    }
}

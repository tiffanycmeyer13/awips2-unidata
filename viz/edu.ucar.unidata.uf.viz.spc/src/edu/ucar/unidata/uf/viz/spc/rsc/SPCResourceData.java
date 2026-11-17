/**
 * This software was developed and / or modified by NSF Unidata
 */

package edu.ucar.unidata.uf.viz.spc.rsc;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.viz.core.exception.VizException;
import com.raytheon.uf.viz.core.rsc.AbstractRequestableResourceData;
import com.raytheon.uf.viz.core.rsc.AbstractVizResource;
import com.raytheon.uf.viz.core.rsc.LoadProperties;

/**
 * Defines the requestable resource data used to load SPC outlooks in CAVE.
 *
 * SOFTWARE HISTORY
 *
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Mar 28, 2018             mjames     Initial creation
 * Nov 13, 2026            Tiffany Meyer    Added SPC outlook metadata and query configuration.
 *
 * @author mjames
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "spcResourceData")
@XmlType(name = "spcResourceData")
public class SPCResourceData extends AbstractRequestableResourceData {

    @Override
    protected AbstractVizResource<?, ?> constructResource(
            LoadProperties loadProperties, PluginDataObject[] objects)
            throws VizException {
        return new SPCResource(this, loadProperties, objects);
    }

}

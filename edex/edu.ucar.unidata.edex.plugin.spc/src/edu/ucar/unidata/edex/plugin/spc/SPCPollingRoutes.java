/**
 * This software was developed and / or modified by NSF Unidata
 */

package edu.ucar.unidata.edex.plugin.spc;

import com.raytheon.uf.edex.routes.EDEXRouteBuilder;

/**
 * Camel routes for automatically polling SPC outlook sources.
 *
 * SOFTWARE HISTORY
 *
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Nov 13, 2026            Tiffany Meyer    Initial Creation
 * Nov 13, 2026            Tiffany Meyer    Added configurable automatic polling routes.
 */
public class SPCPollingRoutes extends EDEXRouteBuilder {

    @Override
    public void configure() throws Exception {
        // Check the local setting at each timer event; setting false avoids
        // calling the remote source without stopping SPC file ingestion.
        from("timer:spcArchivePoller?delay=15000&period=300000")
            .filter(method("spcPollingConfiguration", "isEnabled"))
                .bean("spcArchivePoller", "poll")
                .split(body())
                    .to("jms-durable:queue:Ingest.Spc")
                .end()
            .end()
            .setId("spcArchivePollRoute");

        from("timer:spcFeatureServerPoller?delay=30000&period=900000")
            .filter(method("spcPollingConfiguration", "isEnabled"))
                .bean("spcFeatureServerPoller", "poll")
                .split(body())
                    .to("jms-durable:queue:Ingest.Spc")
                .end()
            .end()
            .setId("spcFeatureServerPollRoute");
    }
}

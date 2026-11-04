package edu.ucar.unidata.edex.plugin.spc;

import com.raytheon.uf.edex.routes.EDEXRouteBuilder;

/**
 * Camel ingest route for SPC Convective Outlook products.
 */
public class SPCCamelRoutes extends EDEXRouteBuilder {

    @Override
    public void configure() throws Exception {
        // Poll SPC's archive on a single EDEX host. The archive poller checks
        // recent scheduled cycles and sends newly published ZIPs through the
        // same durable queue used by the existing ingest path.
        from("timer:spcArchivePoller?delay=15000&period=300000")
            .bean("spcArchivePoller", "poll")
            .split(body())
                .to("jms-durable:queue:Ingest.Spc")
            .end()
            .setId("spcArchivePollRoute");

        // Poll the NOAA FeatureServer for the probabilistic Day 4-8
        // outlooks and send changed GeoJSON through the standard ingest queue.
        from("timer:spcFeatureServerPoller?delay=30000&period=900000")
            .bean("spcFeatureServerPoller", "poll")
            .split(body())
                .to("jms-durable:queue:Ingest.Spc")
            .end()
            .setId("spcFeatureServerPollRoute");

        from("jms-durable:queue:Ingest.Spc")
            .setHeader("pluginName", constant("spc"))
            .doTry()
                .pipeline()
                    .bean("stringToFile")
                    .bean("spcDecoder", "decode")
                    .to("direct:persistIndexAlert")
            .endDoTry()
            .doCatch(Throwable.class)
                .to("log:spc?level=ERROR")
            .endDoTry()
            .end()
            .setId("spcIngestRoute");
    }
}

package edu.ucar.unidata.edex.plugin.spc;

import com.raytheon.uf.edex.routes.EDEXRouteBuilder;

/**
 * Camel ingest route for SPC Convective Outlook products.
 */
public class SPCCamelRoutes extends EDEXRouteBuilder {

    @Override
    public void configure() throws Exception {
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

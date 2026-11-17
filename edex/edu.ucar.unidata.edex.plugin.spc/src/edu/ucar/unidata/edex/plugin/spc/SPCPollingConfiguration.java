/**
 * This software was developed and / or modified by NSF Unidata
 */

package edu.ucar.unidata.edex.plugin.spc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Reads the switch for SPC's automatic data polling.
 *
 * SOFTWARE HISTORY
 *
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Nov 13, 2026            Tiffany Meyer    Initial Creation
 * Nov 13, 2026            Tiffany Meyer    Added runtime SPC polling configuration.
 */
public final class SPCPollingConfiguration {

    private static final String PROPERTY_NAME = "spc.polling.enabled";

    private static final Path CONFIG_FILE = Paths
            .get("/awips2/edex/conf/resources/spc.properties");

    public SPCPollingConfiguration() {
    }

    /**
     * Check the setting each time a poll timer fires so it can be changed
     * without restarting EDEX.
     *
     * @return true when automatic SPC polling is enabled
     * @throws IOException if an existing configuration file cannot be read
     */
    public boolean isEnabled() throws IOException {
        String systemOverride = System.getProperty(PROPERTY_NAME);
        if (systemOverride != null) {
            return parseEnabled(systemOverride);
        }

        Properties properties = new Properties();
        try (InputStream defaults = SPCPollingConfiguration.class
                .getClassLoader()
                .getResourceAsStream("resources/spc.properties")) {
            if (defaults != null) {
                properties.load(defaults);
            }
        }

        if (Files.isRegularFile(CONFIG_FILE)) {
            try (InputStream overrides = Files.newInputStream(CONFIG_FILE)) {
                properties.load(overrides);
            }
        }

        String configuredValue = properties.getProperty(PROPERTY_NAME);
        return configuredValue == null || parseEnabled(configuredValue);
    }

    private static boolean parseEnabled(String value) {
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException("Invalid " + PROPERTY_NAME
                + " value: " + value + " (expected true or false)");
    }
}

package edu.ucar.unidata.edex.plugin.spc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;

/**
 * Polls the NOAA SPC Outlooks FeatureServer for Day 4-8 GeoJSON.
 *
 * The service exposes the current probabilistic outlook as GeoJSON layers.
 * Changed layer responses are forwarded to the standard SPC ingest queue.
 */
public class SPCFeatureServerPoller {

    private static final String FEATURE_SERVER_URL =
            "https://mapservices.weather.noaa.gov/vector/rest/services/"
                    + "outlooks/SPC_wx_outlks/FeatureServer/";

    private static final int[] LAYER_IDS = { 21, 22, 23, 24, 25 };

    private static final int CONNECT_TIMEOUT_MILLIS = 10000;

    private static final int READ_TIMEOUT_MILLIS = 30000;

    private static final int MAX_RESPONSE_BYTES = 50 * 1024 * 1024;

    private final IUFStatusHandler logger = UFStatus
            .getHandler(SPCFeatureServerPoller.class);

    private final Map<Integer, String> lastHashes = new HashMap<>();

    /**
     * Return changed Day 4-8 FeatureCollections.
     */
    public synchronized List<byte[]> poll() {
        List<byte[]> changedLayers = new ArrayList<>();
        for (int layerId : LAYER_IDS) {
            String url = FEATURE_SERVER_URL + layerId
                    + "/query?where=1%3D1&outFields=*&returnGeometry=true"
                    + "&outSR=4326&f=geojson";
            try {
                byte[] response = download(url);
                String hash = sha256(response);
                if (!hash.equals(lastHashes.get(layerId))) {
                    lastHashes.put(layerId, hash);
                    changedLayers.add(response);
                    logger.info("Found changed SPC Day "
                            + (layerId - 17) + " FeatureServer layer");
                }
            } catch (Exception e) {
                logger.warn("Unable to retrieve SPC FeatureServer layer "
                        + layerId + ": " + e.getMessage());
            }
        }
        return changedLayers;
    }

    private byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url)
                .openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent",
                "Unidata-AWIPS-SPC-Outlook-Poller");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                        "NOAA FeatureServer returned HTTP " + status);
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException(
                                "SPC GeoJSON response exceeds 50 MB limit");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] response = output.toByteArray();
                String prefix = new String(response, 0,
                        Math.min(response.length, 128),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!prefix.contains("FeatureCollection")) {
                    throw new IllegalStateException(
                            "NOAA response was not a GeoJSON FeatureCollection");
                }
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}

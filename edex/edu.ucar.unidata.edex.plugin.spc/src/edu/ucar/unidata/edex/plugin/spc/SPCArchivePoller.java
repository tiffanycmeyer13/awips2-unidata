package edu.ucar.unidata.edex.plugin.spc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;

/**
 * Polls the SPC GeoJSON archive for recently issued Day 1-3 outlook ZIPs.
 *
 * The archive publishes files under:
 * https://www.spc.noaa.gov/products/outlook/archive/<year>/
 *
 * Newly seen or revised ZIP contents are returned to the Camel route, which
 * sends them through the normal SPC ingest queue and decoder.
 */
public class SPCArchivePoller {

    private static final String ARCHIVE_URL =
            "https://www.spc.noaa.gov/products/outlook/archive/";

    private static final long MAX_AGE_HOURS = 36;

    private static final long MISSING_RETRY_MILLIS = Duration
            .ofMinutes(10).toMillis();

    private static final long RECHECK_MILLIS = Duration.ofHours(1).toMillis();

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private static final int READ_TIMEOUT_MILLIS = 20000;

    private static final int MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd");

    private static final DateTimeFormatter CYCLE_FORMAT = DateTimeFormatter
            .ofPattern("HHmm");

    private static final Map<Integer, List<LocalTime>> ISSUE_CYCLES =
            createIssueCycles();

    private final IUFStatusHandler logger = UFStatus
            .getHandler(SPCArchivePoller.class);

    private final Map<String, ArchiveState> archiveStates = new HashMap<>();

    /**
     * Check recent scheduled archive URLs and return new or revised ZIPs.
     */
    public synchronized List<byte[]> poll() {
        List<byte[]> archives = new ArrayList<>();
        Instant now = Instant.now();

        for (int dayOffset = 0; dayOffset <= 1; dayOffset++) {
            LocalDate issueDate = LocalDate.now(ZoneOffset.UTC)
                    .minusDays(dayOffset);

            for (Map.Entry<Integer, List<LocalTime>> dayEntry : ISSUE_CYCLES
                    .entrySet()) {
                int outlookDay = dayEntry.getKey();
                for (LocalTime cycle : dayEntry.getValue()) {
                    Instant issueInstant = issueDate.atTime(cycle)
                            .toInstant(ZoneOffset.UTC);
                    if (issueInstant.isAfter(now)
                            || Duration.between(issueInstant, now).toHours()
                                    > MAX_AGE_HOURS) {
                        continue;
                    }

                    String url = createUrl(issueDate, outlookDay, cycle);
                    ArchiveState state = archiveStates.computeIfAbsent(url,
                            key -> new ArchiveState());
                    if (shouldWaitBeforeRetry(state, now)) {
                        continue;
                    }

                    try {
                        if (downloadIfChanged(url, state, now, archives)) {
                            logger.info("Queued new SPC outlook archive: "
                                    + url);
                        }
                    } catch (Exception e) {
                        state.lastChecked = now;
                        logger.warn("Unable to check SPC outlook archive "
                                + url + ": " + e.getMessage());
                    }
                }
            }
        }
        return archives;
    }

    private boolean shouldWaitBeforeRetry(ArchiveState state, Instant now) {
        if (state.lastChecked == null) {
            return false;
        }
        long delay = state.sha256 == null ? MISSING_RETRY_MILLIS
                : RECHECK_MILLIS;
        return Duration.between(state.lastChecked, now).toMillis() < delay;
    }

    private boolean downloadIfChanged(String url, ArchiveState state,
            Instant now, List<byte[]> archives) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url)
                .openConnection();
        connection.setRequestMethod("HEAD");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent",
                "Unidata-AWIPS-SPC-Outlook-Poller");
        int status;
        String metadata;
        try {
            status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                state.lastChecked = now;
                return false;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                        "SPC HEAD request returned HTTP " + status);
            }
            metadata = getMetadataToken(connection);
        } finally {
            connection.disconnect();
        }

        state.lastChecked = now;
        if (state.sha256 != null && metadata != null
                && metadata.equals(state.metadataToken)) {
            return false;
        }

        byte[] content = download(url);
        String sha256 = sha256(content);
        state.metadataToken = metadata;
        if (sha256.equals(state.sha256)) {
            return false;
        }

        state.sha256 = sha256;
        archives.add(content);
        return true;
    }

    private byte[] download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url)
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent",
                "Unidata-AWIPS-SPC-Outlook-Poller");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                        "SPC download returned HTTP " + status);
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_ARCHIVE_BYTES) {
                        throw new IllegalStateException(
                                "SPC archive exceeds 50 MB limit");
                    }
                    output.write(buffer, 0, count);
                }
                byte[] content = output.toByteArray();
                if (content.length < 4 || content[0] != 'P'
                        || content[1] != 'K') {
                    throw new IllegalStateException(
                            "SPC response is not a ZIP archive");
                }
                return content;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String getMetadataToken(HttpURLConnection connection) {
        String etag = connection.getHeaderField("ETag");
        long lastModified = connection.getLastModified();
        long contentLength = connection.getContentLengthLong();
        if ((etag == null || etag.isEmpty()) && lastModified == 0
                && contentLength < 0) {
            return null;
        }
        return String.valueOf(etag) + ":" + lastModified + ":"
                + contentLength;
    }

    private String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }

    private String createUrl(LocalDate issueDate, int outlookDay,
            LocalTime cycle) {
        String date = issueDate.format(DATE_FORMAT);
        String cycleText = cycle.format(CYCLE_FORMAT);
        return ARCHIVE_URL + issueDate.getYear() + "/day" + outlookDay
                + "otlk_" + date + "_" + cycleText + "-geojson.zip";
    }

    private static Map<Integer, List<LocalTime>> createIssueCycles() {
        Map<Integer, List<LocalTime>> cycles = new HashMap<>();
        cycles.put(1, Arrays.asList(LocalTime.of(1, 0), LocalTime.of(6, 0),
                LocalTime.of(13, 0), LocalTime.of(16, 30),
                LocalTime.of(20, 0)));
        cycles.put(2, Arrays.asList(LocalTime.of(6, 0), LocalTime.of(17, 0)));
        cycles.put(3, Arrays.asList(LocalTime.of(7, 30), LocalTime.of(19, 30)));
        return cycles;
    }

    private static class ArchiveState {
        private Instant lastChecked;
        private String metadataToken;
        private String sha256;
    }
}

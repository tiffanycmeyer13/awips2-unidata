package edu.ucar.unidata.edex.plugin.spc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.MultiPolygon;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.common.json.geo.SimpleGeoJsonService;
import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;
import com.raytheon.uf.common.time.DataTime;

import de.micromata.opengis.kml.v_2_2_0.Coordinate;
import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Folder;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.LinearRing;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.TimeSpan;
import edu.ucar.unidata.common.dataplugin.spc.SPCRecord;

/**
 * Decoder for SPC convective outlook KML and GeoJSON files.
 */
public class SPCDecoder {

    private static final DateTimeFormatter SPC_TIME_FORMAT = DateTimeFormat
            .forPattern("yyyyMMddHHmm").withZoneUTC();

    private final GeometryFactory geomFact = new GeometryFactory();

    private final IUFStatusHandler logger = UFStatus
            .getHandler(SPCDecoder.class);

    private static final String[] REPORT_NAMES = { "Convective Outlook",
            "Tornado Outlook", "Wind Outlook", "Hail Outlook" };

    private static final String[] REPORT_SUFFIXES = { "cat", "torn", "wind",
            "hail" };

    private static final Pattern OUTLOOK_LAYER_PATTERN = Pattern.compile(
            "^day([123])otlk(?:_([0-9]{8}_[0-9]{4}))?_(cat|torn|hail|wind)$");

    private static class ZipGeoJsonFile {
        private final String day;
        private final String key;
        private final boolean timestamped;
        private final byte[] content;

        private ZipGeoJsonFile(String day, String key, boolean timestamped,
                byte[] content) {
            this.day = day;
            this.key = key;
            this.timestamped = timestamped;
            this.content = content;
        }
    }

    public PluginDataObject[] decode(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            return new PluginDataObject[0];
        }

        if (data.length > 3 && data[0] == 'P' && data[1] == 'K') {
            return decodeZip(data);
        }

        String input = new String(data, StandardCharsets.UTF_8).trim();
        if (input.startsWith("{")) {
            return decodeGeoJson(input);
        }
        return decodeKml(input);
    }

    private PluginDataObject[] decodeZip(byte[] data) throws Exception {
        Map<String, ZipGeoJsonFile> timestampedFiles = new LinkedHashMap<>();
        Map<String, ZipGeoJsonFile> currentFiles = new LinkedHashMap<>();
        byte[] buffer = new byte[8192];

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ZipGeoJsonFile fileInfo = parseOutlookLayerFile(
                        entry.getName());
                if (fileInfo == null) {
                    continue;
                }

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                ZipGeoJsonFile file = new ZipGeoJsonFile(fileInfo.day,
                        fileInfo.key, fileInfo.timestamped,
                        output.toByteArray());
                if (file.timestamped) {
                    timestampedFiles.put(file.key, file);
                } else {
                    currentFiles.putIfAbsent(file.key, file);
                }
            }
        }

        // For each day and outlook type, prefer the timestamped archive file
        // over its current-name alias. Keep aliases where an archive file is
        // not present so a partial ZIP can still be ingested.
        List<ZipGeoJsonFile> selectedFiles = new ArrayList<>(
                timestampedFiles.values());
        for (Map.Entry<String, ZipGeoJsonFile> entry : currentFiles
                .entrySet()) {
            if (!timestampedFiles.containsKey(entry.getKey())) {
                selectedFiles.add(entry.getValue());
            }
        }
        List<PluginDataObject> records = new ArrayList<>();
        for (ZipGeoJsonFile file : selectedFiles) {
            PluginDataObject[] decoded = decodeGeoJson(
                    new String(file.content, StandardCharsets.UTF_8).trim(),
                    file.day, getReportTypeForLayer(file.key));
            for (PluginDataObject record : decoded) {
                records.add(record);
            }
        }

        logger.info(records.size() + " SPC outlook features decoded from ZIP");
        return records.toArray(new PluginDataObject[records.size()]);
    }

    private ZipGeoJsonFile parseOutlookLayerFile(String entryName) {
        int slash = entryName.lastIndexOf('/');
        String baseName = slash < 0 ? entryName
                : entryName.substring(slash + 1);
        if (!baseName.endsWith(".lyr.geojson")) {
            return null;
        }

        String stem = baseName.substring(0,
                baseName.length() - ".lyr.geojson".length());
        Matcher matcher = OUTLOOK_LAYER_PATTERN.matcher(stem);
        if (!matcher.matches()) {
            return null;
        }

        String day = matcher.group(1);
        String suffix = matcher.group(3);
        String key = day + "_" + suffix;
        return new ZipGeoJsonFile(day, key, matcher.group(2) != null, null);
    }

    private PluginDataObject[] decodeGeoJson(String input) throws Exception {
        return decodeGeoJson(input, "1", null);
    }

    private PluginDataObject[] decodeGeoJson(String input, String outlookDay)
            throws Exception {
        return decodeGeoJson(input, outlookDay, null);
    }

    private PluginDataObject[] decodeGeoJson(String input, String outlookDay,
            String layerReportType) throws Exception {
        List<SPCRecord> records = new ArrayList<>();

        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = new SimpleGeoJsonService()
                .deserializeFeatureCollection(new ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)));

        int reportPart = 0;
        try (FeatureIterator<SimpleFeature> iterator = collection.features()) {
            while (iterator.hasNext()) {
                SimpleFeature feature = iterator.next();
                String label = getProperty(feature, "LABEL");
                String displayLabel = getProperty(feature, "LABEL2");
                String source = getProperty(feature, "idp_source");
                String featureDay = getOutlookDay(source, outlookDay);
                Integer riskLevel = parseInteger(
                        getAttribute(feature, "DN"));
                boolean featureServiceOutlook = isFeatureServiceOutlook(
                        source, featureDay);

                // Day 5-8 service layers omit LABEL and use DN=0 as the
                // no-risk placeholder. Convert it to a normal SPC no-risk
                // record so the display and product browser still have a time.
                if (featureServiceOutlook
                        && (label == null || label.isEmpty())) {
                    if (riskLevel == null) {
                        continue;
                    }
                    if (riskLevel.intValue() <= 0) {
                        label = "Less than 15% All Areas";
                        displayLabel = label;
                    } else {
                        label = String.valueOf(riskLevel);
                        displayLabel = riskLevel + "% Any Severe Risk";
                    }
                }

                // Conditional-intensity layers are intentionally omitted.
                if (label == null || label.isEmpty()
                        || label.toUpperCase(Locale.US).startsWith("CIG")) {
                    continue;
                }

                boolean noRisk = isNoRiskLabel(label);
                String type = featureServiceOutlook
                        ? "Day " + featureDay + " Probabilistic Outlook"
                        : getReportType(label, displayLabel, featureDay);
                if (type == null && noRisk) {
                    type = layerReportType;
                }
                if (type == null) {
                    logger.info("Skipping SPC feature with unsupported label: "
                            + label + " (" + displayLabel + ")");
                    continue;
                }

                Geometry geometry = null;
                if (!noRisk) {
                    Object geometryValue = feature.getDefaultGeometry();
                    if (!(geometryValue instanceof Geometry)) {
                        continue;
                    }
                    geometry = (Geometry) geometryValue;
                    if (geometry.isEmpty()
                            || (!(geometry instanceof Polygon)
                                    && !(geometry instanceof MultiPolygon))) {
                        continue;
                    }
                }

                Date issueTime = parseTime(
                        getProperty(feature, "ISSUE_ISO"),
                        getProperty(feature, "ISSUE"));
                if (issueTime == null && featureServiceOutlook) {
                    issueTime = parseArcGisDate(
                            getAttribute(feature, "idp_filedate"));
                    // Layers 5-8 omit ISSUE; SPC publishes these daily at 09Z.
                    if (issueTime != null) {
                        issueTime = new DateTime(issueTime, DateTimeZone.UTC)
                                .withTime(9, 0, 0, 0).toDate();
                    }
                }
                Date validTime = parseTime(
                        getProperty(feature, "VALID_ISO"),
                        getProperty(feature, "VALID"));
                Date expireTime = parseTime(
                        getProperty(feature, "EXPIRE_ISO"),
                        getProperty(feature, "EXPIRE"));
                if (issueTime == null) {
                    issueTime = validTime;
                }
                if (issueTime == null) {
                    logger.info("Skipping SPC feature without ISSUE time");
                    continue;
                }
                if (featureServiceOutlook && validTime == null) {
                    int dayNumber = Integer.parseInt(featureDay);
                    validTime = new DateTime(issueTime, DateTimeZone.UTC)
                            .plusDays(dayNumber - 1)
                            .withTime(12, 0, 0, 0).toDate();
                }
                if (featureServiceOutlook && expireTime == null
                        && validTime != null) {
                    expireTime = new DateTime(validTime, DateTimeZone.UTC)
                            .plusDays(1).toDate();
                }

                SPCRecord record = new SPCRecord();
                // Keep the precise publication time in issueTime, but use
                // the nominal hourly cycle for the CAVE frame time.
                DateTime frameTime = new DateTime(issueTime, DateTimeZone.UTC)
                        .withMinuteOfHour(0).withSecondOfMinute(0)
                        .withMillisOfSecond(0);
                record.setDataTime(new DataTime(frameTime.toDate()));
                record.setIssueTime(issueTime);
                record.setValidTime(validTime);
                record.setExpireTime(expireTime);
                record.setReportType(type);
                record.setOutlookDay(featureDay);
                record.setTypeCategory(
                        noRisk ? "No Risk" : normalizeCategory(label));
                record.setDisplayLabel(noRisk ? label : displayLabel);
                record.setForecaster(getProperty(feature, "FORECASTER"));
                record.setRiskLevel(riskLevel);
                record.setFillColor(getProperty(feature, "fill"));
                record.setStrokeColor(getProperty(feature, "stroke"));
                record.setReportPart(++reportPart);
                record.setGeometry(geometry);
                records.add(record);
            }
        }

        logger.info(records.size() + " SPC outlook features decoded from GeoJSON");
        return records.toArray(new PluginDataObject[records.size()]);
    }

    private String getReportTypeForLayer(String key) {
        int separator = key.lastIndexOf('_');
        String suffix = separator < 0 ? key : key.substring(separator + 1);
        for (int i = 0; i < REPORT_SUFFIXES.length; i++) {
            if (REPORT_SUFFIXES[i].equals(suffix)) {
                return REPORT_NAMES[i];
            }
        }
        return null;
    }

    private boolean isNoRiskLabel(String label) {
        if (label == null) {
            return false;
        }
        String normalized = label.trim().toLowerCase(Locale.US);
        return normalized.startsWith("less than ")
                && normalized.endsWith(" all areas");
    }

    private String getReportType(String label, String displayLabel,
            String outlookDay) {
        for (Entry<String, String> entry : SPCRecord.CONVECTIVE_OUTLOOK
                .entrySet()) {
            if (entry.getValue().equalsIgnoreCase(label)) {
                return "Convective Outlook";
            }
        }

        String text = displayLabel == null ? "" : displayLabel
                .toLowerCase(Locale.US);
        if (text.contains("any severe") && outlookDay != null) {
            return "Day " + outlookDay + " Probabilistic Outlook";
        }
        if (text.contains("tornado")) {
            return "Tornado Outlook";
        } else if (text.contains("hail")) {
            return "Hail Outlook";
        } else if (text.contains("wind")) {
            return "Wind Outlook";
        }
        return null;
    }

    private String normalizeCategory(String label) {
        for (Entry<String, String> entry : SPCRecord.CONVECTIVE_OUTLOOK
                .entrySet()) {
            if (entry.getValue().equalsIgnoreCase(label)) {
                return entry.getKey();
            }
        }

        if ("SIG".equalsIgnoreCase(label)) {
            return "Sig";
        }

        try {
            double probability = Double.parseDouble(label.replace("%", "")
                    .trim());
            if (probability <= 1.0) {
                probability *= 100.0;
            }
            return String.format(Locale.US, "%.0f %%", probability);
        } catch (NumberFormatException e) {
            return label;
        }
    }

    private Date parseTime(String isoTime, String basicTime) {
        try {
            if (isoTime != null && !isoTime.isEmpty()) {
                return DateTime.parse(isoTime).toDate();
            }
            if (basicTime != null && !basicTime.isEmpty()) {
                return SPC_TIME_FORMAT.parseDateTime(basicTime).toDate();
            }
        } catch (IllegalArgumentException e) {
            logger.info("Unable to parse SPC time: " + isoTime + " / "
                    + basicTime);
        }
        return null;
    }

    private String getProperty(SimpleFeature feature, String name) {
        Object value = getAttribute(feature, name);
        return value == null ? null : value.toString().trim();
    }

    private Object getAttribute(SimpleFeature feature, String name) {
        Object value = feature.getAttribute(name);
        if (value != null) {
            return value;
        }
        for (int i = 0; i < feature.getFeatureType()
                .getAttributeCount(); i++) {
            String attributeName = feature.getFeatureType().getDescriptor(i)
                    .getLocalName();
            if (attributeName.equalsIgnoreCase(name)) {
                return feature.getAttribute(i);
            }
        }
        return null;
    }

    private String getOutlookDay(String source, String defaultDay) {
        if (source != null) {
            Matcher matcher = Pattern.compile("(?i)^day([1-8])otlk_.*")
                    .matcher(source.trim());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return defaultDay == null ? "1" : defaultDay;
    }

    private boolean isFeatureServiceOutlook(String source, String day) {
        if (source == null || day == null || day.trim().isEmpty()) {
            return false;
        }
        try {
            int dayNumber = Integer.parseInt(day);
            return dayNumber >= 4 && dayNumber <= 8
                    && source.toLowerCase(Locale.US).contains("otlk_");
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Date parseArcGisDate(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Number) {
            return new Date(((Number) value).longValue());
        }
        if (value != null) {
            try {
                return DateTime.parse(value.toString()).toDate();
            } catch (IllegalArgumentException e) {
                return parseTime(null, value.toString());
            }
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        if (value != null) {
            try {
                return Integer.valueOf(value.toString().trim());
            } catch (NumberFormatException e) {
                // Ignore malformed optional risk rank.
            }
        }
        return null;
    }

    private PluginDataObject[] decodeKml(String input) throws Exception {
        List<SPCRecord> records = new ArrayList<>();

        String normalizedInput = input.replace(
                "xmlns=\"http://earth.google.com/kml/2.2\"",
                "xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\"");

        Document document = (Document) Kml.unmarshal(new ByteArrayInputStream(
                normalizedInput.getBytes(StandardCharsets.UTF_8))).getFeature();
        List<Feature> folders = document.getFeature();

        for (Feature feature : folders) {
            String folderName = feature.getName();
            String type = getKmlReportType(folderName);

            if (!(feature instanceof Folder)) {
                continue;
            }

            Folder folder = (Folder) feature;
            int reportPart = 0;
            for (Feature mark : folder.getFeature()) {
                if (!(mark instanceof Placemark)) {
                    continue;
                }

                Placemark placemark = (Placemark) mark;
                if (!(placemark.getGeometry() instanceof de.micromata.opengis.kml.v_2_2_0.Polygon)) {
                    continue;
                }

                de.micromata.opengis.kml.v_2_2_0.Polygon polygon =
                        (de.micromata.opengis.kml.v_2_2_0.Polygon) placemark
                                .getGeometry();
                if (!(placemark.getTimePrimitive() instanceof TimeSpan)) {
                    continue;
                }

                TimeSpan timePrimitive = (TimeSpan) placemark
                        .getTimePrimitive();
                Date date = new DateTime(timePrimitive.getBegin()).toDate();

                SPCRecord record = new SPCRecord();
                record.setDataTime(new DataTime(date));
                record.setIssueTime(date);
                record.setTypeCategory(placemark.getName());
                record.setReportType(type);
                record.setOutlookDay("1");
                record.setReportPart(++reportPart);
                record.setGeometry(geomFact.createPolygon(modifyLinearRing(
                        polygon.getOuterBoundaryIs().getLinearRing())));
                records.add(record);
            }
            logger.info(records.size() + " polygons processed for KML feature "
                    + folderName);
        }

        return records.toArray(new PluginDataObject[records.size()]);
    }

    private String getKmlReportType(String folderName) {
        for (int i = 0; i < REPORT_SUFFIXES.length; i++) {
            if (folderName != null && folderName.endsWith(REPORT_SUFFIXES[i])) {
                return REPORT_NAMES[i];
            }
        }
        return null;
    }

    public org.locationtech.jts.geom.LinearRing modifyLinearRing(LinearRing ring) {
        return geomFact.createLinearRing(convertCoords(ring.getCoordinates()));
    }

    /**
     * Convert coordinates from KML to JTS.
     */
    public org.locationtech.jts.geom.Coordinate[] convertCoords(
            List<Coordinate> geomList) {
        org.locationtech.jts.geom.Coordinate[] coordList =
                new org.locationtech.jts.geom.Coordinate[geomList.size()];
        int index = 0;
        for (Coordinate coordinate : geomList) {
            coordList[index++] = new org.locationtech.jts.geom.Coordinate(
                    coordinate.getLongitude(), coordinate.getLatitude());
        }
        return coordList;
    }
}

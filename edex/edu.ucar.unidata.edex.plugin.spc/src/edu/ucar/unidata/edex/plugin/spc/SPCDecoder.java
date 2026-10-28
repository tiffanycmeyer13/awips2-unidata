package edu.ucar.unidata.edex.plugin.spc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;

import org.joda.time.DateTime;
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

    public PluginDataObject[] decode(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            return new PluginDataObject[0];
        }

        String input = new String(data, StandardCharsets.UTF_8).trim();
        if (input.startsWith("{")) {
            return decodeGeoJson(input);
        }
        return decodeKml(input);
    }

    private PluginDataObject[] decodeGeoJson(String input) throws Exception {
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

                // Conditional-intensity layers are separate SPC products and
                // are intentionally not included in the four outlook layers.
                if (label == null || label.isEmpty()
                        || label.toUpperCase(Locale.US).startsWith("CIG")) {
                    continue;
                }

                String type = getReportType(label, displayLabel);
                if (type == null) {
                    logger.info("Skipping SPC feature with unsupported label: "
                            + label + " (" + displayLabel + ")");
                    continue;
                }

                Object geometryValue = feature.getDefaultGeometry();
                if (!(geometryValue instanceof Geometry)) {
                    continue;
                }
                Geometry geometry = (Geometry) geometryValue;
                if (geometry.isEmpty()
                        || (!(geometry instanceof Polygon)
                                && !(geometry instanceof MultiPolygon))) {
                    continue;
                }

                Date issueTime = parseTime(
                        getProperty(feature, "ISSUE_ISO"),
                        getProperty(feature, "ISSUE"));
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

                SPCRecord record = new SPCRecord();
                record.setDataTime(new DataTime(issueTime));
                record.setIssueTime(issueTime);
                record.setValidTime(validTime);
                record.setExpireTime(expireTime);
                record.setReportType(type);
                record.setTypeCategory(normalizeCategory(label));
                record.setDisplayLabel(displayLabel);
                record.setForecaster(getProperty(feature, "FORECASTER"));
                record.setRiskLevel(parseInteger(feature.getAttribute("DN")));
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

    private String getReportType(String label, String displayLabel) {
        for (Entry<String, String> entry : SPCRecord.CONVECTIVE_OUTLOOK
                .entrySet()) {
            if (entry.getValue().equalsIgnoreCase(label)) {
                return "Convective Outlook";
            }
        }

        String text = displayLabel == null ? "" : displayLabel
                .toLowerCase(Locale.US);
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
        Object value = feature.getAttribute(name);
        return value == null ? null : value.toString().trim();
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

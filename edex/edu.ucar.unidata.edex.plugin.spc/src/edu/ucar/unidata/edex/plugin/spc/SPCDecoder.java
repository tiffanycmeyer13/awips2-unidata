package edu.ucar.unidata.edex.plugin.spc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.common.json.geo.IGeoJsonService;
import com.raytheon.uf.common.json.geo.SimpleGeoJsonService;
import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;
import com.raytheon.uf.common.time.DataTime;

import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Folder;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.LinearRing;
import de.micromata.opengis.kml.v_2_2_0.Placemark;
import de.micromata.opengis.kml.v_2_2_0.Polygon;
import de.micromata.opengis.kml.v_2_2_0.TimeSpan;
import edu.ucar.unidata.common.dataplugin.spc.SPCRecord;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

/**
 * Decoder for SPC convective outlook GeoJSON files. Legacy KML products are
 * still supported for backward compatibility.
 */
public class SPCDecoder {

    private static final String CONDITIONAL_INTENSITY = "conditional intensity";

    private static final DateTimeFormatter SPC_TIME_FORMAT = DateTimeFormat
            .forPattern("yyyyMMddHHmm").withZoneUTC();

    private final GeometryFactory geomFact = new GeometryFactory();

    private final IUFStatusHandler logger = UFStatus.getHandler(SPCDecoder.class);

    /**
     * Decode a GeoJSON FeatureCollection or a legacy KML outlook.
     *
     * @param data
     *            product bytes
     * @return decoded SPC records
     * @throws Exception
     *             if the product cannot be decoded
     */
    public PluginDataObject[] decode(byte[] data) throws Exception {
        String input = new String(data, StandardCharsets.UTF_8).trim();
        if (input.startsWith("{")) {
            return decodeGeoJson(data);
        }
        return decodeKml(data);
    }

    private PluginDataObject[] decodeGeoJson(byte[] data) throws Exception {
        List<SPCRecord> records = new ArrayList<>();
        IGeoJsonService json = new SimpleGeoJsonService();
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = json
                .deserializeFeatureCollection(new ByteArrayInputStream(data));

        try (FeatureIterator<SimpleFeature> features = collection.features()) {
            int part = 0;
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                Object geometryObject = feature.getDefaultGeometry();
                if (!(geometryObject instanceof Geometry)) {
                    continue;
                }

                String label = property(feature, "LABEL");
                String displayLabel = property(feature, "LABEL2");
                String reportType = determineReportType(label, displayLabel);
                if (reportType == null) {
                    // Conditional Intensity Guidance is a separate product
                    // family and is not part of these four outlook products.
                    continue;
                }

                String issueTime = property(feature, "ISSUE");
                String validTime = property(feature, "VALID");
                String expireTime = property(feature, "EXPIRE");
                Date referenceTime = parseProductTime(issueTime);
                if (referenceTime == null) {
                    referenceTime = parseProductTime(validTime);
                }
                if (referenceTime == null) {
                    throw new IllegalArgumentException(
                            "SPC GeoJSON feature has no parseable ISSUE or VALID time");
                }

                SPCRecord record = new SPCRecord();
                record.setDataTime(new DataTime(referenceTime));
                record.setReportType(reportType);
                record.setTypeCategory(displayCategory(label, displayLabel));
                record.setDisplayLabel(displayLabel);
                record.setIssueTime(issueTime);
                record.setValidTime(validTime);
                record.setExpireTime(expireTime);
                record.setForecaster(property(feature, "FORECASTER"));
                record.setRiskRank(integerProperty(feature, "DN"));
                record.setFillColor(property(feature, "fill"));
                record.setStrokeColor(property(feature, "stroke"));
                record.setReportPart(++part);
                record.setGeometry((Geometry) geometryObject);
                records.add(record);
            }
        }

        logger.info(records.size() + " SPC GeoJSON features decoded");
        return records.toArray(new PluginDataObject[records.size()]);
    }

    private static String determineReportType(String label, String displayLabel) {
        String text = displayLabel == null ? "" : displayLabel.toLowerCase(Locale.US);
        if (text.contains(CONDITIONAL_INTENSITY)) {
            return null;
        }
        if (text.contains("tornado")) {
            return "Tornado Outlook";
        }
        if (text.contains("hail")) {
            return "Hail Outlook";
        }
        if (text.contains("wind")) {
            return "Wind Outlook";
        }
        if (text.contains("thunderstorm") || isCategoricalLabel(label)) {
            return "Convective Outlook";
        }
        return null;
    }

    private static boolean isCategoricalLabel(String label) {
        if (label == null) {
            return false;
        }
        switch (label.toUpperCase(Locale.US)) {
        case "TSTM":
        case "MRGL":
        case "SLGT":
        case "ENH":
        case "MDT":
        case "HIGH":
            return true;
        default:
            return false;
        }
    }

    /**
     * Translate SPC's compact GeoJSON labels to the categories used by the
     * existing CAVE resource color map.
     */
    private static String displayCategory(String label, String displayLabel) {
        if (label == null) {
            return displayLabel;
        }
        switch (label.toUpperCase(Locale.US)) {
        case "TSTM":
            return "General Thunder";
        case "MRGL":
            return "Marginal Risk";
        case "SLGT":
            return "Slight Risk";
        case "ENH":
            return "Enhanced Risk";
        case "MDT":
            return "Moderate Risk";
        case "HIGH":
            return "High Risk";
        case "SIG":
            return "Sig";
        default:
            try {
                double probability = Double.parseDouble(label);
                if (probability >= 0.0 && probability <= 1.0) {
                    return String.format(Locale.US, "%d %%",
                            Math.round(probability * 100.0));
                }
            } catch (NumberFormatException e) {
                // Non-probability labels are retained as supplied.
            }
            return label;
        }
    }

    private static String property(SimpleFeature feature, String name) {
        Object value = feature.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private static Integer integerProperty(SimpleFeature feature, String name) {
        Object value = feature.getAttribute(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(value.toString());
            } catch (NumberFormatException e) {
                // Ignore absent or malformed rank values.
            }
        }
        return null;
    }

    private static Date parseProductTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            String time = value.trim();
            if (time.matches("\\d{12}")) {
                return SPC_TIME_FORMAT.parseDateTime(time).toDate();
            }
            return DateTime.parse(time).toDate();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Preserve support for the earlier folder-based KML format.
     */
    private PluginDataObject[] decodeKml(byte[] data) throws Exception {
        List<SPCRecord> records = new ArrayList<>();
        String input = new String(data, StandardCharsets.UTF_8).replace(
                "xmlns=\"http://earth.google.com/kml/2.2\"",
                "xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\"");

        Document document = (Document) Kml.unmarshal(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))
                .getFeature();
        for (Feature feature : document.getFeature()) {
            if (!(feature instanceof Folder)) {
                continue;
            }

            Folder folder = (Folder) feature;
            int part = 0;
            for (Feature mark : folder.getFeature()) {
                if (!(mark instanceof Placemark)) {
                    continue;
                }
                Placemark placemark = (Placemark) mark;
                if (!(placemark.getGeometry() instanceof Polygon)
                        || !(placemark.getTimePrimitive() instanceof TimeSpan)) {
                    continue;
                }

                Polygon polygon = (Polygon) placemark.getGeometry();
                TimeSpan timeSpan = (TimeSpan) placemark.getTimePrimitive();
                Date referenceTime = parseProductTime(timeSpan.getBegin());
                if (referenceTime == null) {
                    continue;
                }

                SPCRecord record = new SPCRecord();
                record.setDataTime(new DataTime(referenceTime));
                record.setTypeCategory(placemark.getName());
                record.setReportType(reportTypeFromFolder(folder.getName()));
                record.setReportPart(++part);
                record.setGeometry(geomFact.createPolygon(
                        convertRing(polygon.getOuterBoundaryIs().getLinearRing())));
                records.add(record);
            }
        }
        return records.toArray(new PluginDataObject[records.size()]);
    }

    private static String reportTypeFromFolder(String folderName) {
        if (folderName == null) {
            return null;
        }
        String name = folderName.toLowerCase(Locale.US);
        if (name.endsWith("cat")) {
            return "Convective Outlook";
        }
        if (name.endsWith("torn")) {
            return "Tornado Outlook";
        }
        if (name.endsWith("wind")) {
            return "Wind Outlook";
        }
        if (name.endsWith("hail")) {
            return "Hail Outlook";
        }
        return null;
    }

    private org.locationtech.jts.geom.LinearRing convertRing(LinearRing ring) {
        List<de.micromata.opengis.kml.v_2_2_0.Coordinate> coordinates = ring
                .getCoordinates();
        Coordinate[] result = new Coordinate[coordinates.size()];
        for (int i = 0; i < coordinates.size(); i++) {
            de.micromata.opengis.kml.v_2_2_0.Coordinate coordinate = coordinates
                    .get(i);
            result[i] = new Coordinate(coordinate.getLongitude(),
                    coordinate.getLatitude());
        }
        return geomFact.createLinearRing(result);
    }
}

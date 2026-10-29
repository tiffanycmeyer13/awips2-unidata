package edu.ucar.unidata.uf.viz.spc.rsc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Date;
import java.util.TimeZone;
import java.util.Locale;

import org.eclipse.swt.graphics.RGB;
import org.geotools.coverage.grid.GeneralGridGeometry;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import edu.ucar.unidata.common.dataplugin.spc.SPCRecord;
import com.raytheon.uf.common.geospatial.ReferencedCoordinate;
import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;
import com.raytheon.uf.common.status.UFStatus.Priority;
import com.raytheon.uf.common.time.DataTime;
import com.raytheon.uf.viz.core.DrawableString;
import com.raytheon.uf.viz.core.IGraphicsTarget;
import com.raytheon.uf.viz.core.IGraphicsTarget.TextStyle;
import com.raytheon.uf.viz.core.IGraphicsTarget.VerticalAlignment;
import com.raytheon.uf.viz.core.drawables.IShadedShape;
import com.raytheon.uf.viz.core.drawables.ext.ICanvasRenderingExtension;
import com.raytheon.uf.viz.core.drawables.IWireframeShape;
import com.raytheon.uf.viz.core.drawables.PaintProperties;
import com.raytheon.uf.viz.core.exception.VizException;
import com.raytheon.uf.viz.core.map.IMapDescriptor;
import com.raytheon.uf.viz.core.map.MapDescriptor;
import com.raytheon.uf.viz.core.rsc.AbstractVizResource;
import com.raytheon.uf.viz.core.rsc.IResourceDataChanged;
import com.raytheon.uf.viz.core.rsc.LoadProperties;
import com.raytheon.uf.viz.core.rsc.capabilities.ColorableCapability;
import com.raytheon.uf.viz.core.rsc.capabilities.OutlineCapability;
import com.raytheon.uf.viz.core.rsc.capabilities.ImagingCapability;
import com.raytheon.viz.core.rsc.jts.JTSCompiler;
import com.raytheon.viz.core.rsc.jts.JTSCompiler.JTSGeometryData;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import com.raytheon.uf.common.dataquery.requests.RequestConstraint;

/**
 * Resource for SPC data
 * 
 * SOFTWARE HISTORY
 * 
 * Date         Engineer    Description
 * ------------ ----------- --------------------------
 * Mar 28, 2018 mjames      Initial creation
 * Apr 04, 2018 mjames      Type/category labeling and sampling
 * 
 * 
 * @author mjames@ucar
 */

public class SPCResource extends AbstractVizResource<SPCResourceData, MapDescriptor> {

    /** Fixed transparency for risk fills, independent of brightness controls. */
    private static final float SPC_FILL_ALPHA = 0.5f;

    private volatile boolean fillEnabled = true;

	private static final transient IUFStatusHandler statusHandler = UFStatus
			.getHandler(SPCResource.class);

	GeometryFactory geomFact = new GeometryFactory();

	private Map<String, RGB> CONVECTIVE_MAPPING = 
			getColorMapping(convectiveFillColor,SPCRecord.CONVECTIVE_OUTLOOK);
	
	private Map<String, RGB> TORNADO_MAPPING =
			getColorMapping(tornadoFillColor, SPCRecord.TORNADO_OUTLOOK);

    private static final String[] WIND_PROBABILITY_CATEGORIES = {
            "5 %", "15 %", "30 %", "45 %", "60 %", "75 %", "90 %", "Sig" };

    private static final String[] HAIL_PROBABILITY_CATEGORIES = {
            "5 %", "15 %", "30 %", "45 %", "60 %", "Sig" };

    private Map<String, RGB> WIND_MAPPING =
            getColorMapping(windFillColor, WIND_PROBABILITY_CATEGORIES);

    private Map<String, RGB> HAIL_MAPPING =
            getColorMapping(hailFillColor, HAIL_PROBABILITY_CATEGORIES);

    // SPC GeoJSON/MapServer fill colors for the probabilistic outlook classes.
    private static final RGB[] tornadoFillColor = {
            new RGB(121, 186, 122), // 2%
            new RGB(189, 153, 138), // 5%
            new RGB(255, 228, 129), // 10%
            new RGB(255, 128, 128), // 15%
            new RGB(255, 128, 255), // 30%
            new RGB(200, 150, 247), // 45%
            new RGB(16, 78, 139), // 60%
            new RGB(0, 0, 0) // Significant
    };

    private static final RGB[] windFillColor = {
            new RGB(197, 163, 146), // 5%
            new RGB(255, 235, 127), // 15%
            new RGB(255, 127, 127), // 30%
            new RGB(255, 127, 255), // 45%
            new RGB(200, 149, 246), // 60%
            new RGB(92, 133, 214), // 75%
            new RGB(26, 255, 255), // 90%
            new RGB(0, 0, 0) // Significant
    };

    private static final RGB[] hailFillColor = {
            new RGB(197, 163, 146), // 5%
            new RGB(255, 235, 127), // 15%
            new RGB(255, 127, 127), // 30%
            new RGB(255, 127, 255), // 45%
            new RGB(200, 149, 246), // 60%
            new RGB(0, 0, 0) // Significant
    };

	private static final RGB[] convectiveFillColor = { 
			new RGB(192, 232, 192), 
			new RGB(127, 197, 127), 
			new RGB(246, 246, 127), 
			new RGB(230, 194, 127), 
			new RGB(230, 127, 127), 
			new RGB(255, 127, 255)
	};

	public static RGB[] getConvectiveFillColor() {
		return convectiveFillColor;
	}

	private Map<DataTime, Collection<SPCRecord>> unprocessedRecords = new HashMap<DataTime, Collection<SPCRecord>>();

	Collection<SPCRecord> records = null;
	
	List<IShadedShape> shapeList = new ArrayList<IShadedShape>();

	private DataTime displayedDataTime;

	protected SPCResource(SPCResourceData resourceData,
			LoadProperties loadProperties, PluginDataObject[] pdos) {
		super(resourceData, loadProperties, false);
        getCapabilities().addCapability(new OutlineCapability());
        ImagingCapability imagingCapability = new ImagingCapability();
        // This is a vector product, but expose the standard Imaging Properties
        // controls so users can adjust its fill opacity, brightness, contrast.
        imagingCapability.setSuppressingMenuItems(false);
        getCapabilities().addCapability(imagingCapability);
        if (pdos != null) {
            for (PluginDataObject p : pdos) {
                if (p instanceof SPCRecord) {
                    addRecord((SPCRecord) p);
                }
            }
        }
		resourceData.addChangeListener(new IResourceDataChanged() {
			@Override
			public void resourceChanged(ChangeType type, Object object) {
				if (type == ChangeType.DATA_UPDATE) {
					for (PluginDataObject p : pdos) {
						if (p instanceof SPCRecord) {
							addRecord((SPCRecord) p);
						}
					}
				}
				issueRefresh();
			} 
		});
	}
	
	@Override
	protected void disposeInternal() {
	}

	@Override
	public DataTime[] getDataTimes() {
		if (this.dataTimes == null) {
			return new DataTime[0];
		}
		return this.dataTimes.toArray(new DataTime[this.dataTimes.size()]);
	}

	@Override
	protected void initInternal(IGraphicsTarget target) throws VizException {
	}

	@Override
	public String inspect(ReferencedCoordinate coord) throws VizException {
        StringBuilder res = new StringBuilder();
        for (SPCRecord record : records) {
            try {
                Geometry geom = record.getGeometry();
                Coordinate latLon = coord.asLatLon();
                Point point = geom.getFactory().createPoint(latLon);
                if (geom.contains(point)) {
                    String data = record.getTypeCategory();
                    res.append(data);
                    res.append("\n");
                }
                
            } catch (Exception e) {
                throw new VizException("Error interogating SPC geometry", e);
            }
        }
        return res.toString();
	}

	@Override
	protected void paintInternal(IGraphicsTarget target,
			PaintProperties paintProps) throws VizException {
		this.displayedDataTime = paintProps.getDataTime();

		Collection<SPCRecord> unprocessed = null;
		synchronized (unprocessedRecords) {
			unprocessed = unprocessedRecords.get(this.displayedDataTime);
		}
		if (unprocessed != null && unprocessed.size() > 0) {
			updateRecords(target, paintProps);
		}
	}
	
    /**
	 * Process all records for the displayedDataTime
	 * 
	 * @param target
	 * @param paintProps
	 * @throws VizException
	 */
	@SuppressWarnings("null")
	private void updateRecords(IGraphicsTarget target,
			PaintProperties paintProps) throws VizException {

		Collection<SPCRecord> newRecords = null;
		synchronized (unprocessedRecords) {
			newRecords = unprocessedRecords.get(this.displayedDataTime);
		}
		
		// SPC risk areas are nested. Draw the lower-risk polygons first so
		// higher-risk fills remain visible over them.
        List<SPCRecord> drawOrder = new ArrayList<>(newRecords);
        drawOrder.sort((first, second) -> {
            Integer firstRisk = first.getRiskLevel();
            Integer secondRisk = second.getRiskLevel();
            if (firstRisk == null) {
                return secondRisk == null ? 0 : -1;
            }
            return secondRisk == null ? 1 : firstRisk.compareTo(secondRisk);
        });
        ImagingCapability imaging = getCapability(ImagingCapability.class);
        OutlineCapability outline = getCapability(OutlineCapability.class);

        // Draw every fill first, from broad/low-risk to narrow/high-risk.
        // This preserves the nested risk colors without covering outlines.
        for (SPCRecord record : drawOrder) {
            Geometry geometry = record.getGeometry();
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            // Keep the risk fills translucent so the map remains visible
            // beneath them. Brightness remains controlled independently by
            // the standard Imaging Properties brightness control.
            float brightness = imaging.getBrightness();
            RGB fillColor = applyBrightness(
                    applyContrast(getReportTypeColor(record),
                            imaging.getContrast()),
                    brightness);
            if (fillEnabled && brightness > 0.0f) {
                IShadedShape shadedShape = computeShape(target, descriptor,
                        geometry, fillColor);
                target.drawShadedShape(shadedShape, SPC_FILL_ALPHA);
            }
        }

        // Draw outlines in a second pass so later higher-risk fills cannot
        // hide the outlines of the categories below them.
        if (outline.isOutlineOn()) {
            for (SPCRecord record : drawOrder) {
                Geometry geometry = record.getGeometry();
                if (geometry == null || geometry.isEmpty()) {
                    continue;
                }
                IWireframeShape wireFrame = computeWireframe(target,
                        descriptor, geometry);
                RGB outlineColor = parseGeoJsonColor(record.getStrokeColor());
                if (outlineColor == null) {
                    outlineColor = getReportTypeColor(record);
                }
                target.drawWireframeShape(wireFrame, outlineColor,
                        outline.getOutlineWidth(), outline.getLineStyle());
            }
        }
        paintRiskLegend(target, paintProps, drawOrder, imaging);
        paintProductMetadata(target, paintProps, drawOrder);
	}

    /**
     * Draw a compact, screen-relative legend for the risk categories in the
     * displayed outlook. Legend colors and opacity stay fixed when the map
     * fill's Imaging Properties change.
     */
    private void paintRiskLegend(IGraphicsTarget target,
            PaintProperties paintProps, List<SPCRecord> displayRecords,
            ImagingCapability imaging) throws VizException {
        Map<String, SPCRecord> categories = new LinkedHashMap<>();
        for (SPCRecord record : displayRecords) {
            String label = record.getTypeCategory();
            if (label != null && !label.trim().isEmpty()) {
                categories.putIfAbsent(label, record);
            }
        }
        if (categories.isEmpty()) {
            return;
        }

        List<DrawableString> strings = new ArrayList<>();
        // Reserve enough room for the longest risk label and swatch.
        // Place the legend relative to the canvas so it stays in the upper
        // right corner as users resize the display.
        double legendWidth = 232.0;
        double x = Math.max(12.0,
                paintProps.getCanvasBounds().x
                        + paintProps.getCanvasBounds().width - legendWidth
                        - 12.0);
        double y = 18.0;
        DrawableString title = new DrawableString("SPC Risk Levels",
                new RGB(255, 255, 255));
        title.setCoordinates(x, y);
        title.verticallAlignment = VerticalAlignment.TOP;
        title.addTextStyle(TextStyle.BLANKED, new RGB(0, 0, 0));
        strings.add(title);
        y += 22.0;

        for (Map.Entry<String, SPCRecord> entry : categories.entrySet()) {
            RGB categoryColor = getReportTypeColor(entry.getValue());
            DrawableString swatch = new DrawableString("    ",
                    new RGB(255, 255, 255));
            swatch.setCoordinates(x, y);
            swatch.verticallAlignment = VerticalAlignment.TOP;
            swatch.addTextStyle(TextStyle.BLANKED, categoryColor);
            strings.add(swatch);

            DrawableString label = new DrawableString(entry.getKey(),
                    new RGB(255, 255, 255));
            label.setCoordinates(x + 34.0, y);
            label.verticallAlignment = VerticalAlignment.TOP;
            label.addTextStyle(TextStyle.BLANKED, new RGB(0, 0, 0));
            strings.add(label);
            y += 20.0;
        }

        PaintProperties legendProps = new PaintProperties(paintProps);
        legendProps.setAlpha(1.0f);
        target.getExtension(ICanvasRenderingExtension.class).drawStrings(
                legendProps, strings.toArray(new DrawableString[0]));
    }

    /**
     * Draw product issue/validity details and forecaster in the upper-left
     * corner, using fields retained on each decoded SPC record.
     */
    private void paintProductMetadata(IGraphicsTarget target,
            PaintProperties paintProps, List<SPCRecord> displayRecords)
            throws VizException {
        if (displayRecords.isEmpty()) {
            return;
        }

        SPCRecord record = displayRecords.get(0);
        List<String> lines = new ArrayList<>();
        String reportType = record.getReportType();
        lines.add("SPC " + (reportType == null ? "OUTLOOK"
                : reportType.toUpperCase(Locale.US)));
        if (record.getIssueTime() != null) {
            lines.add("ISSUED " + formatUtc(record.getIssueTime()));
        }
        Date validTime = record.getValidTime();
        Date expireTime = record.getExpireTime();
        if (validTime != null || expireTime != null) {
            String valid = validTime == null ? "?"
                    : formatUtc(validTime);
            String expire = expireTime == null ? "?"
                    : formatUtc(expireTime);
            lines.add("VALID " + valid + " - " + expire);
        }
        if (record.getForecaster() != null
                && !record.getForecaster().trim().isEmpty()) {
            lines.add("FCSTR: " + record.getForecaster());
        }
        lines.add("NOAA/NWS/NCEP");
        lines.add("STORM PREDICTION CENTER");

        List<DrawableString> strings = new ArrayList<>();
        RGB green = new RGB(0, 255, 0);
        double y = 16.0;
        for (String line : lines) {
            DrawableString text = new DrawableString(line, green);
            text.setCoordinates(16.0, y);
            text.verticallAlignment = VerticalAlignment.TOP;
            text.addTextStyle(TextStyle.BLANKED, new RGB(0, 0, 0));
            strings.add(text);
            y += 16.0;
        }

        PaintProperties metadataProps = new PaintProperties(paintProps);
        metadataProps.setAlpha(1.0f);
        target.getExtension(ICanvasRenderingExtension.class).drawStrings(
                metadataProps, strings.toArray(new DrawableString[0]));
    }

    private String formatUtc(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy HHmm'Z'");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    private RGB applyContrast(RGB color, float contrast) {
        return new RGB(adjustContrast(color.red, contrast),
                adjustContrast(color.green, contrast),
                adjustContrast(color.blue, contrast));
    }

    private int adjustContrast(int value, float contrast) {
        return Math.max(0, Math.min(255,
                Math.round((value - 127.5f) * contrast + 127.5f)));
    }

    private RGB applyBrightness(RGB color, float brightness) {
        return new RGB(Math.max(0, Math.min(255,
                        Math.round(color.red * brightness))),
                Math.max(0, Math.min(255,
                        Math.round(color.green * brightness))),
                Math.max(0, Math.min(255,
                        Math.round(color.blue * brightness))));
    }

	/**
	 * Create wireframe object from Polygon
	 * 
	 * @param target
	 * @param descriptor
	 * @param p
	 * @param color
	 * @return
	 */
	private IWireframeShape computeWireframe(IGraphicsTarget target,
            IMapDescriptor descriptor, Geometry geometry) {
        GeneralGridGeometry genGrid = descriptor.getGridGeometry();
        IWireframeShape wireframe = target.createWireframeShape(false,
                new GeneralGridGeometry(genGrid));
        addBoundarySegments(geometry.getBoundary(), wireframe);
        wireframe.compile();
        return wireframe;
    }

    /**
     * Add every boundary line without dropping vertices. The previous
     * zoom-based sampling could reduce long rings to a few points and create
     * straight chords across outlook areas.
     */
    private void addBoundarySegments(Geometry boundary,
            IWireframeShape wireframe) {
        if (boundary instanceof LineString) {
            Coordinate[] coordinates = boundary.getCoordinates();
            if (coordinates.length > 1) {
                wireframe.addLineSegment(coordinates);
            }
            return;
        }

        for (int i = 0; i < boundary.getNumGeometries(); i++) {
            Geometry component = boundary.getGeometryN(i);
            if (component != boundary) {
                addBoundarySegments(component, wireframe);
            }
        }
    }

	/**
	 * Create shaded shape and compile to JTS
	 * 
	 * @param target
	 * @param descriptor
	 * @param p
	 * @param color
	 * @return
	 */
	private IShadedShape computeShape(IGraphicsTarget target,
            IMapDescriptor descriptor, Geometry geometry, RGB color) {
		GeneralGridGeometry genGrid = descriptor.getGridGeometry();
		IShadedShape newShadedShape = target.createShadedShape(false, new GeneralGridGeometry(genGrid));
		JTSCompiler shapeCompiler = new JTSCompiler(newShadedShape, null, descriptor);
		JTSGeometryData geomData = shapeCompiler.createGeometryData();
		geomData.setWorldWrapCorrect(true);
		try {
			geomData.setGeometryColor(color);
			shapeCompiler.handle(geometry, geomData);
		} catch (VizException e) {
			statusHandler.handle(Priority.PROBLEM, "Error computing shaded shape", e);
		}
		newShadedShape.compile();
		return newShadedShape;
	}

	/**
	 * Get color for report type
	 * 
	 * @param record
	 * @return
	 */
	private RGB getReportTypeColor(SPCRecord record) {
        RGB color = parseGeoJsonColor(record.getFillColor());
        if (color != null) {
            return color;
        }
		color = getCapability(ColorableCapability.class).getColor();
		switch(record.getReportType()) {
			case("Convective Outlook"):
				for (Entry<String, RGB> entry : CONVECTIVE_MAPPING.entrySet()) {
					if (entry.getKey().equals(record.getTypeCategory())) {
						color = entry.getValue();
						break;}}
                break;
			case("Tornado Outlook"):
				for (Entry<String, RGB> entry : TORNADO_MAPPING.entrySet()) {
					if (entry.getKey().equals(record.getTypeCategory())) {
						color = entry.getValue();
						break;}}
                break;
			case("Wind Outlook"):
				for (Entry<String, RGB> entry : WIND_MAPPING.entrySet()) {
					if (entry.getKey().equals(record.getTypeCategory())) {
						color = entry.getValue();
						break;}}
                break;
			case("Hail Outlook"):
				for (Entry<String, RGB> entry : HAIL_MAPPING.entrySet()) {
					if (entry.getKey().equals(record.getTypeCategory())) {
						color = entry.getValue();
						break;}}
		}
		return color;
	}

    private RGB parseGeoJsonColor(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.matches("(?i)[0-9a-f]{6}")) {
            try {
                return new RGB(Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16),
                        Integer.parseInt(hex.substring(4, 6), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
	
	/**
	 * Add a new record to this resource
	 * 
	 * @param obj
	 */
	protected void addRecord(SPCRecord obj) {
		DataTime dataTime = obj.getDataTime();
		
		boolean brandNew = false;
		synchronized (unprocessedRecords) {
			records = unprocessedRecords.get(dataTime);
			if (records == null) {
				records = new LinkedHashSet<SPCRecord>();
				unprocessedRecords.put(dataTime, records);
				brandNew = true;
			}
		}
		if (brandNew) {
			this.dataTimes.add(dataTime);
		}
		records.add(obj);
	}

	    public boolean isFillEnabled() {
        return fillEnabled;
    }

    public void setFillEnabled(boolean enabled) {
        if (fillEnabled != enabled) {
            fillEnabled = enabled;
            issueRefresh();
        }
    }

@Override
	public String getName() {
		RequestConstraint constraint = this.resourceData.getMetadataMap()
                .get("reportType");
        String product = constraint == null ? "Convective Outlook"
                : constraint.getConstraintValue().toString().trim();
        RequestConstraint dayConstraint = this.resourceData.getMetadataMap()
                .get("outlookDay");
        if (dayConstraint != null
                && dayConstraint.getConstraintValue() != null) {
            String day = dayConstraint.getConstraintValue().toString().trim();
            if (!day.isEmpty()) {
                return "SPC Day " + day + " " + product;
            }
        }
        return "SPC " + product;
	}

	@Override
	public void project(CoordinateReferenceSystem crs) throws VizException {
	}

	@Override
	public void remove(DataTime time) {
	}
	
	/**
	 * Color mapping by report type
	 * 
	 * @param fillColor
	 * @param records
	 * @return
	 */
	private static Map<String, RGB> getColorMapping(RGB[] fillColor, Map<String, String> records) {
		Map<String, RGB> mapping = new LinkedHashMap<>();
		int i=0;
		for (String name : records.keySet() ) {
			mapping.put(name, fillColor[i]);
			i++;
		}
		return mapping;
	}

	/**
	 * Color mapping by report type
	 * 
	 * @param fillColor
	 * @param records
	 * @return
	 */
	private static Map<String, RGB> getColorMapping(RGB[] fillColor, String[] records) {
		Map<String, RGB> mapping = new LinkedHashMap<>();
		int i=0;
		for (String name : records ) {
			mapping.put(name, fillColor[i]);
			i++;
		}
		return mapping;
	}

}

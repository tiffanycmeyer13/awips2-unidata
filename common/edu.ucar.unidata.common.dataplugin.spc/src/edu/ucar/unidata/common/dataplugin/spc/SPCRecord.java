package edu.ucar.unidata.common.dataplugin.spc;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.hibernate.annotations.Index;
import org.hibernate.annotations.Type;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.common.dataplugin.annotations.DataURI;
import com.raytheon.uf.common.geospatial.adapter.GeometryAdapter;
import com.raytheon.uf.common.serialization.annotations.DynamicSerialize;
import com.raytheon.uf.common.serialization.annotations.DynamicSerializeElement;
import org.locationtech.jts.geom.Geometry;

/**
 * 
 * SPC Convective Outlook Record
 * 
 * SOFTWARE HISTORY
 * Date         Ticket#     Engineer    Description
 * ------------ ----------  ----------- --------------------------
 * Mar 27, 2018             mjames@ucar  Initial creation
 * 
 * @author mjames
 */

@Entity
@SequenceGenerator(initialValue = 1, name = PluginDataObject.ID_GEN, sequenceName = "spc_seq")
@Table(name = SPCRecord.PLUGIN_NAME, uniqueConstraints = { @UniqueConstraint(columnNames = { "dataURI" }) })
/*
 * Both refTime and forecastTime are included in the refTimeIndex since
 * forecastTime is unlikely to be used.
 */
@org.hibernate.annotations.Table(appliesTo = SPCRecord.PLUGIN_NAME, indexes = { 
		@Index(name = "spc_refTimeIndex", columnNames = {
        "refTime", "forecastTime" }) })
@DynamicSerialize
public class SPCRecord extends PluginDataObject {

	private static final long serialVersionUID = 1L;
	
	public static final String[] TORNADO_OUTLOOK = {
			"2 %",
			"5 %",
			"10 %",
			"15 %",
			"30 %",
			"45 %",
			"60 %",
			"Sig"
	};
	
	public static final String[] HAILWIND_OUTLOOK = {
			"5 %",
			"15 %",
			"30 %",
			"45 %",
			"60 %",
			"Sig"
	};
		
	public static final String[] TSTORM_OUTLOOK = {
			"10 %",
			"40 %",
			"70 %"
	};
	
	private static final String[] convectiveCategory= { 
			"TSTM", 
			"MRGL", 
			"SLGT", 
			"ENH", 
			"MDT", 
			"HIGH"
	};

	private static final String[] convectiveCategoryName = { 
			"General Thunder",
			"Marginal Risk",
			"Slight Risk",
			"Enhanced Risk",
			"Moderate Risk",
			"High Risk"
	};
	
	public static Map<String, String> CONVECTIVE_OUTLOOK = 
			mapDefinitions(convectiveCategoryName, convectiveCategory);

	public static final String PLUGIN_NAME = "spc";

	// report type
	@Column(length = 32)
	@DynamicSerializeElement
	@DataURI(position = 1)
	String reportType;

	// report name
	@Column(length = 32)
	@DynamicSerializeElement
	@DataURI(position = 2)
	String typeCategory;
	
	// report part
	@Column
    @DynamicSerializeElement
    @DataURI(position = 3)
    Integer reportPart;

	@Column(length = 32)
	@DynamicSerializeElement
	@DataURI(position = 4)
	private String issueTime;

	@Column(length = 32)
	@DynamicSerializeElement
	private String validTime;

	@Column(length = 32)
	@DynamicSerializeElement
	private String expireTime;

	@Column(length = 128)
	@DynamicSerializeElement
	private String displayLabel;

	@Column(length = 64)
	@DynamicSerializeElement
	private String forecaster;

	@Column
	@DynamicSerializeElement
	private Integer riskRank;

	@Column(length = 16)
	@DynamicSerializeElement
	private String fillColor;

	@Column(length = 16)
	@DynamicSerializeElement
	private String strokeColor;

	@Column(name = "location", columnDefinition = "geometry")
	@XmlJavaTypeAdapter(value = GeometryAdapter.class)
	@DynamicSerializeElement
	private Geometry geometry;

	/**
     * Default Constructor
     */
    public SPCRecord() {
        this.reportType = SPCRecord.PLUGIN_NAME;
        this.typeCategory = null;
        this.geometry = null;
    }

	public Geometry getGeometry() {
		return geometry;
	}

	public void setGeometry(Geometry geometry) {
		this.geometry = geometry;
	}

	public void setReportPart(Integer part) {
		this.reportPart = part;
	}
	
	public Integer getReportPart() {
		return reportPart;
	}

	public String getIssueTime() {
		return issueTime;
	}

	public void setIssueTime(String issueTime) {
		this.issueTime = issueTime;
	}

	public String getValidTime() {
		return validTime;
	}

	public void setValidTime(String validTime) {
		this.validTime = validTime;
	}

	public String getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(String expireTime) {
		this.expireTime = expireTime;
	}

	public String getDisplayLabel() {
		return displayLabel;
	}

	public void setDisplayLabel(String displayLabel) {
		this.displayLabel = displayLabel;
	}

	public String getForecaster() {
		return forecaster;
	}

	public void setForecaster(String forecaster) {
		this.forecaster = forecaster;
	}

	public Integer getRiskRank() {
		return riskRank;
	}

	public void setRiskRank(Integer riskRank) {
		this.riskRank = riskRank;
	}

	public String getFillColor() {
		return fillColor;
	}

	public void setFillColor(String fillColor) {
		this.fillColor = fillColor;
	}

	public String getStrokeColor() {
		return strokeColor;
	}

	public void setStrokeColor(String strokeColor) {
		this.strokeColor = strokeColor;
	}

	public String getTypeCategory() {
		return typeCategory;
	}

	public void setTypeCategory(String typeCategory) {
		this.typeCategory = typeCategory;
	}

	public String getReportType() {
		return reportType;
	}

	public void setReportType(String reportType) {
		this.reportType = reportType;
	}

    @Override
    @Column
    @Access(AccessType.PROPERTY)
    public String getDataURI() {
        return super.getDataURI();
    }
	
	@Override
	public String getPluginName() {
		return PLUGIN_NAME;
	}
	
	public static Map<String, String> mapDefinitions(String[] s1, String[] s2) {
		Map<String, String> mapping = new LinkedHashMap<>();
		int i=0;
		for (String name : s1 ) {
			mapping.put(name, s2[i]);
			i++;
		}
		return mapping;
	}

}

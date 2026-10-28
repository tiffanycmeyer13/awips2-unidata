package edu.ucar.unidata.common.dataplugin.spc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Date;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.UniqueConstraint;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.hibernate.annotations.Index;

import com.raytheon.uf.common.dataplugin.PluginDataObject;
import com.raytheon.uf.common.dataplugin.annotations.DataURI;
import com.raytheon.uf.common.geospatial.adapter.GeometryAdapter;
import com.raytheon.uf.common.serialization.annotations.DynamicSerialize;
import com.raytheon.uf.common.serialization.annotations.DynamicSerializeElement;
import org.locationtech.jts.geom.Geometry;

/**
 * SPC Convective Outlook record.
 */
@Entity
@SequenceGenerator(initialValue = 1, name = PluginDataObject.ID_GEN, sequenceName = "spc_seq")
@Table(name = SPCRecord.PLUGIN_NAME, uniqueConstraints = {
        @UniqueConstraint(columnNames = { "dataURI" }) })
@org.hibernate.annotations.Table(appliesTo = SPCRecord.PLUGIN_NAME, indexes = {
        @Index(name = "spc_refTimeIndex", columnNames = { "refTime",
                "forecastTime" }) })
@DynamicSerialize
public class SPCRecord extends PluginDataObject {

    private static final long serialVersionUID = 1L;

    public static final String[] TORNADO_OUTLOOK = { "2 %", "5 %", "10 %",
            "15 %", "30 %", "45 %", "60 %", "Sig" };

    public static final String[] HAILWIND_OUTLOOK = { "5 %", "15 %", "30 %",
            "45 %", "60 %", "Sig" };

    public static final String[] TSTORM_OUTLOOK = { "10 %", "40 %", "70 %" };

    private static final String[] convectiveCategory = { "TSTM", "MRGL",
            "SLGT", "ENH", "MDT", "HIGH" };

    private static final String[] convectiveCategoryName = { "General Thunder",
            "Marginal Risk", "Slight Risk", "Enhanced Risk", "Moderate Risk",
            "High Risk" };

    /**
     * Mapping from the display category used by the existing SPC CAVE resource
     * to the SPC GeoJSON short label.
     */
    public static final Map<String, String> CONVECTIVE_OUTLOOK = mapDefinitions(
            convectiveCategoryName, convectiveCategory);

    public static final String PLUGIN_NAME = "spc";

    @Column(length = 32)
    @DynamicSerializeElement
    @DataURI(position = 1)
    private String reportType;

    @Column(length = 32)
    @DynamicSerializeElement
    @DataURI(position = 2)
    private String typeCategory;

    @Column
    @DynamicSerializeElement
    @DataURI(position = 3)
    private Integer reportPart;

    @Column(name = "location", columnDefinition = "geometry")
    @XmlJavaTypeAdapter(value = GeometryAdapter.class)
    @DynamicSerializeElement
    private Geometry geometry;

    @Temporal(TemporalType.TIMESTAMP)
    @Column
    @DynamicSerializeElement
    private Date issueTime;

    @Temporal(TemporalType.TIMESTAMP)
    @Column
    @DynamicSerializeElement
    private Date validTime;

    @Temporal(TemporalType.TIMESTAMP)
    @Column
    @DynamicSerializeElement
    private Date expireTime;

    @Column(length = 128)
    @DynamicSerializeElement
    private String displayLabel;

    @Column(length = 64)
    @DynamicSerializeElement
    private String forecaster;

    @Column
    @DynamicSerializeElement
    private Integer riskLevel;

    @Column(length = 16)
    @DynamicSerializeElement
    private String fillColor;

    @Column(length = 16)
    @DynamicSerializeElement
    private String strokeColor;

    public SPCRecord() {
        this.reportType = PLUGIN_NAME;
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

    public Date getIssueTime() {
        return issueTime;
    }

    public void setIssueTime(Date issueTime) {
        this.issueTime = issueTime;
    }

    public Date getValidTime() {
        return validTime;
    }

    public void setValidTime(Date validTime) {
        this.validTime = validTime;
    }

    public Date getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Date expireTime) {
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

    public Integer getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(Integer riskLevel) {
        this.riskLevel = riskLevel;
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

    public static Map<String, String> mapDefinitions(String[] keys,
            String[] values) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(keys.length, values.length); i++) {
            mapping.put(keys[i], values[i]);
        }
        return mapping;
    }
}

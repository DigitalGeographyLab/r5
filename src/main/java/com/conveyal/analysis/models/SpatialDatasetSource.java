package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.spatial.GeometryWrapper;
import com.conveyal.analysis.spatial.SpatialAttribute;
import com.conveyal.analysis.spatial.SpatialDataset;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.util.ShapefileReader;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.locationtech.jts.geom.Envelope;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.spatial.SpatialDataset.SourceFormat.*;
import static com.conveyal.file.FileCategory.RESOURCES;
import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

public class SpatialDatasetSource extends BaseModel {
    public String regionId;
    /** Description editable by end users */
    public String description;
    public SpatialDataset.SourceFormat sourceFormat;
    /** General geometry type, with associated static methods for conversion to spatial datasets */
    public GeometryWrapper geometryWrapper;
    /** Attributes, set only after validation (e.g. appropriate format for each feature's attributes) */
    public List<SpatialAttribute> attributes;

    private SpatialDatasetSource (UserPermissions userPermissions, String sourceName) {
        super(userPermissions, sourceName);
    }

    @JsonIgnore
    public static SpatialDatasetSource create (UserPermissions userPermissions, String sourceName) {
        return new SpatialDatasetSource(userPermissions, sourceName);
    }

    @JsonIgnore
    public SpatialDatasetSource withRegion (String regionId) {
        this.regionId = regionId;
        return this;
    }

    @JsonIgnore
    public void validateAndSetDetails (SpatialDataset.SourceFormat uploadFormat, List<File> files) {
        this.sourceFormat = uploadFormat;
        if (uploadFormat == GRID) {
            // TODO source.fromGrids(fileItems);
        } else if (uploadFormat == SHAPEFILE) {
            this.fromShapefile(files);
        } else if (uploadFormat == CSV) {
            // TODO source.fromCsv(fileItems);
        } else if (uploadFormat == GEOJSON) {
            // TODO source.fromGeojson(fileItems);
        }
    }

    private void fromShapefile (List<File> files) {
        // In the caller, we should have already verified that all files have the same base name and have an extension.
        // Extract the relevant files: .shp, .prj, .dbf, and .shx.
        // We need the SHX even though we're looping over every feature as they might be sparse.
        Map<String, File> filesByExtension = new HashMap<>();
        for (File file : files) {
            filesByExtension.put(FilenameUtils.getExtension(file.getName()).toUpperCase(), file);
        }

        try {
            ShapefileReader reader = new ShapefileReader(filesByExtension.get("SHP"));
            Envelope envelope = reader.wgs84Bounds();
            checkWgsEnvelopeSize(envelope);
            this.attributes = reader.getAttributes();
            this.geometryWrapper = reader.getGeometryType();
        } catch (Exception e) {
            throw AnalysisServerException.fileUpload("Shapefile transform error. Try uploading an unprojected " +
                    "(EPSG:4326) file." + e.getMessage());
        }
    }

    @JsonIgnore
    public SpatialDatasetSource fromFiles (List<FileItem> fileItemList) {
        // TODO this.files from fileItemList;
        return this;
    }

    @JsonIgnore
    public FileStorageKey storageKey() {
        return new FileStorageKey(RESOURCES, this._id.toString(), sourceFormat.toString());
    }

}

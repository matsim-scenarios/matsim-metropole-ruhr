package org.matsim.prepare;

import org.apache.log4j.Logger;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.DirectPosition2D;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.opengis.referencing.operation.TransformException;

import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;

public class ElevationReader {

    private final static Logger log = Logger.getLogger(ElevationReader.class);
    private final Collection<ElevationMap> elevationMaps;
    private final CoordinateTransformation transformation;

    ElevationReader(Collection<String> filenames, CoordinateTransformation transformation) {

        log.info("Loading " + filenames.size() + " height maps.");
        elevationMaps = filenames.parallelStream()
                .map(ElevationMap::new)
                .collect(Collectors.toList());
        this.transformation = transformation;
    }

    public double getElevationAt(Coord coord) {

        Coord transformed = transformation.transform(coord);
        var position = new DirectPosition2D(transformed.getX(), transformed.getY());

        for (ElevationMap elevationMap : elevationMaps) {

            if (elevationMap.covers(position)) {
                return elevationMap.getElevation(position);
            }
        }

        // if there is no height data we default to 0 height.
        return 0;
    }

    private static class ElevationMap {

        private final static Logger log = Logger.getLogger(ElevationMap.class);
        private final double[] outPixel = new double[1];
        private final GridCoverage2D coverage;
        private final Raster raster;

        ElevationMap(String filename) {

            log.info("Loading height map from: " + filename);
            try {
                var file = new File(filename);
                var reader = new GeoTiffReader(file);
                coverage = reader.read(null);
                log.info("Loading image data into memory. This may take a while if your geo tiff is large.");
                raster = coverage.getRenderedImage().getData();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        boolean covers(DirectPosition2D position) {
            return coverage.getEnvelope2D().contains(position.getX(), position.getY());
        }

        double getElevation(DirectPosition2D position) {

            if (!coverage.getEnvelope2D().contains(position.getX(), position.getY())) {
                throw new IllegalArgumentException("position is not covered by height map. Test with 'covers' first");
            }

            try {
                var gridPosition = coverage.getGridGeometry().worldToGrid(position);
                raster.getPixel(gridPosition.x, gridPosition.y, outPixel);
                return outPixel[0];
            } catch (TransformException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

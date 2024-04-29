package org.matsim.prepare.commercial;


import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * //TODO: Add description
 */
public class RvrTripRelation {
    public static final String column_originCellId = "quell_vz_nr";
    public static final String column_originLocationId = "quell_flaechen_id";
    public static final String column_destinationCellId = "ziel_vz_nr";
    public static final String column_destinationLocationId = "ziel_flaechen_id";
    public static final String column_origin_X = "quell_x";
    public static final String column_origin_Y = "quell_y";
    public static final String column_destination_X = "ziel_x";
    public static final String column_destination_Y = "ziel_y";
    public static final String column_transportType = "segment";
    public static final String column_goodsType = "nst";
    public static final String column_tonesPerYear = "tonnen_gueter";
    public static final String column_parcelHubId = "quelle_id";
    public static final String column_parcelsPerYear = "paketeProJahr";
    public static final String column_parcelOperator = "dienstleister";
    /**
     * Start location of the full trip relation
     */
    private final String originCell;
    /**
     * Start location of the main run; Also the destination of the pre-run (when applicable)
     */
    private final String originLocationId;
    /**
     * Destination of the main run; Also the starting location of the post-run (when applicable)
     */
    private final String destinationLocationId;
    /**
     * Destination of the full trip relation
     */
    private final String destinationCell;

    /**
     * FTL or LTL
     */
    private final String transportType;

    private final String goodsType;
    private final double tonsPerYear;

    private final double originX;
    private final double originY;
    private final double destinationX;
    private final double destinationY;

    private final double parcelsPerYear;
    private final String parcelOperator;
    private final String parcelHubId;

    public static class Builder {

        private String originCell;
        private String originLocationId;
        private String destinationCell;
        private String destinationLocationId;

        private String transportType;

        private String goodsType;
        private double tonsPerYear;
        private double originX;
        private double originY;
        private double destinationX;
        private double destinationY;

        public double parcelsPerYear;
        public String parcelOperator;
        public String parcelHubId;

        public Builder originCell(String value) {
            this.originCell = value;
            return this;
        }

        public Builder originLocationId(String value) {
            this.originLocationId = value;
            return this;
        }

        public Builder destinationCell(String value) {
            this.destinationCell = value;
            return this;
        }

        public Builder destinationLocationId(String value) {
            this.destinationLocationId = value;
            return this;
        }

        public Builder transportType(String value) {
            this.transportType = value;
            return this;
        }

        public Builder goodsType(String value) {
            this.goodsType = value;
            return this;
        }

        public Builder tonsPerYear(double value) {
            this.tonsPerYear = value;
            return this;
        }

        public Builder originX(double value) {
            this.originX = value;
            return this;
        }

        public Builder originY(double value) {
            this.originY = value;
            return this;
        }

        public Builder destinationX(double value) {
            this.destinationX = value;
            return this;
        }

        public Builder destinationY(double value) {
            this.destinationY = value;
            return this;
        }
        public Builder parcelsPerYear(double value) {
            this.parcelsPerYear = value;
            return this;
        }
        public Builder parcelOperator(String value) {
            this.parcelOperator = value;
            return this;
        }
        public Builder parcelHubId(String value) {
            this.parcelHubId = value;
            return this;
        }

        public RvrTripRelation build() {
            return new RvrTripRelation(this);
        }
    }

    private RvrTripRelation(Builder builder) {
        this.originCell = builder.originCell;
        this.originLocationId = builder.originLocationId;
        this.destinationLocationId = builder.destinationLocationId;
        this.destinationCell = builder.destinationCell;

        this.transportType = builder.transportType;

        this.goodsType = builder.goodsType;
        this.tonsPerYear = builder.tonsPerYear;
        this.originX = builder.originX;
        this.originY = builder.originY;
        this.destinationX = builder.destinationX;
        this.destinationY = builder.destinationY;

        this.parcelsPerYear = builder.parcelsPerYear;
        this.parcelOperator = builder.parcelOperator;
        this.parcelHubId = builder.parcelHubId;
    }

    public double getOriginX() {
        return originX;
    }

    public double getOriginY() {
        return originY;
    }

    public double getDestinationX() {
        return destinationX;
    }

    public double getDestinationY() {
        return destinationY;
    }

    public String getOriginCell() {
        return originCell;
    }

    public String getOriginLocationId() {
        return originLocationId;
    }

    public String getDestinationLocationId() {
        return destinationLocationId;
    }

    public String getDestinationCell() {
        return destinationCell;
    }

    public String getTransportType() {
        return transportType;
    }

    public String getGoodsType() {
        return goodsType;
    }

    public double getTonsPerYear() {
        return tonsPerYear;
    }

    public double getParcelsPerYear() {
        return parcelsPerYear;
    }

    public String getParcelOperator() {
        return parcelOperator;
    }

    public String getParcelHubId() {
        return parcelHubId;
    }


    public static List<RvrTripRelation> readTripRelations(Path pathToData, Path KEPdataFolderPath, CoordinateTransformation coordinateTransformation) throws IOException {
        List<RvrTripRelation> tripRelations = new ArrayList<>();

        readRelationsFromMainMatrix(pathToData, tripRelations);
        readRelationsFromKEPMatrix(KEPdataFolderPath, tripRelations, coordinateTransformation);

        return tripRelations;
    }

    private static void readRelationsFromMainMatrix(Path pathToData, List<RvrTripRelation> tripRelations) throws IOException {
        try (CSVParser parser = CSVParser.parse(Files.newBufferedReader(pathToData, StandardCharsets.ISO_8859_1),
                CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter("\t").setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                Builder builder = new Builder();
                // Read locations
                builder.originCell(record.get(column_originCellId)).originLocationId(record.get(column_originLocationId)).
                        destinationCell(record.get(column_destinationCellId)).destinationLocationId(record.get(column_destinationLocationId));

                //read coordinates
                builder.originX(Double.parseDouble(record.get(column_origin_X))).originY(Double.parseDouble(record.get(column_origin_Y))).
                        destinationX(Double.parseDouble(record.get(column_destination_X))).destinationY(Double.parseDouble(record.get(
                                column_destination_Y)));

                // Read transport type  (FTL or LTL)
                builder.transportType(record.get(column_transportType));

                // Read goods type and tons
                builder.goodsType(record.get(column_goodsType)).tonsPerYear(Double.parseDouble(record.get(column_tonesPerYear)));

                // Build trip relation and add to list
                tripRelations.add(builder.build());
            }

        }
    }

    private static void readRelationsFromKEPMatrix(Path KEPdataFolderPath, List<RvrTripRelation> tripRelations,
                                                   CoordinateTransformation coordinateTransformation) throws IOException {
        try (CSVParser parser = CSVParser.parse(Files.newBufferedReader(KEPdataFolderPath, StandardCharsets.ISO_8859_1),
                CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter("\t").setHeader().setSkipHeaderRecord(true).build())) {
            for (CSVRecord record : parser) {
                Builder builder = new Builder();
                // Read hub
                builder.originLocationId(record.get(column_parcelHubId));

                //read coordinates (destination only on 100x100 grid) and transform them
                Coord coordOrigin = CoordUtils.createCoord(Double.parseDouble(record.get(column_origin_X)),
                        Double.parseDouble(record.get(column_origin_Y)));
                coordOrigin = coordinateTransformation.transform(coordOrigin);

                Coord coordDestination = CoordUtils.createCoord(Double.parseDouble(record.get(column_destination_X)),
                        Double.parseDouble(record.get(column_destination_Y)));
                coordDestination = coordinateTransformation.transform(coordDestination);

                builder.originX(coordOrigin.getX()).originY(coordOrigin.getY()).destinationX(coordDestination.getX()).destinationY(
                        coordDestination.getY());

                // Read transport type  (FTL or LTL)
                builder.transportType(CommercialTrafficUtils.TransportType.LTL.toString());

                // Read goods type and parcels per year
                builder.goodsType("150").parcelHubId(record.get(column_parcelHubId)).parcelOperator(record.get(column_parcelOperator)).parcelsPerYear(
                        Double.parseDouble(record.get(column_parcelsPerYear)));

                // Build trip relation and add to list
                tripRelations.add(builder.build());
            }

        }
    }
}


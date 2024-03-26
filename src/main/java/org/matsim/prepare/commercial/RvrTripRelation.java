package org.matsim.prepare.commercial;


import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * //TODO: Add description
 */
public class RvrTripRelation {
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

    // TODO Additional data (currently, we don't have the lookup table for those data)
    // private final String originalTerminal; // Starting terminal for the main run (also the destination for the pre-run)
    // private final String destinationTerminal; // Destination terminal for main run (also the starting terminal for the post-run)

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


    public static List<RvrTripRelation> readTripRelations(Path pathToDataFolder) throws IOException {
        List<RvrTripRelation> tripRelations = new ArrayList<>();
        List<File> inputFiles = findInputFiles(pathToDataFolder.toFile());

        for (File file : inputFiles) {
            try (CSVParser parser = CSVParser.parse(Files.newBufferedReader(file.toPath(), StandardCharsets.ISO_8859_1),
                    CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter("\t").setHeader().setSkipHeaderRecord(true).build())) {
                for (CSVRecord record : parser) {
                    Builder builder = new Builder();
                    // Read locations
                    builder.originCell(record.get("quell_vz_nr")).originLocationId(record.get("quell_flaechen_id")).
                            destinationCell(record.get("ziel_vz_nr")).destinationLocationId(record.get("ziel_flaechen_id"));

                    //rear coordinates
                    builder.originX(Double.parseDouble(record.get("quell_x"))).originY(Double.parseDouble(record.get("quell_y"))).
                            destinationX(Double.parseDouble(record.get("ziel_x"))).destinationY(Double.parseDouble(record.get("ziel_y")));

                    // Read transport type  (FTL or LTL)
                    builder.transportType(record.get("segment"));

                    // Read goods type and tons
                    builder.goodsType(record.get("nst")).tonsPerYear(Double.parseDouble(record.get("tonnen_gueter")));

                    // Build trip relation and add to list
                    tripRelations.add(builder.build());
                }
            }
        }

        return tripRelations;
    }

    /**
     * This method searches all csv files in a given folder.
     */
    static List<File> findInputFiles(File inputFolder) {
        List<File> fileData = new ArrayList<>();

        for (File file : Objects.requireNonNull(inputFolder.listFiles())) {
            if (file.getName().contains(".csv"))
                fileData.add(file);
        }
        Collections.sort(fileData);
        return fileData;
    }
}


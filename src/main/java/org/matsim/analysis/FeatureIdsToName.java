package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.core.utils.gis.ShapeFileReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class FeatureIdsToName {


    public static void main(String[] args) {

        var features = ShapeFileReader.getAllFeatures(args[0]).stream()
                .collect(Collectors.toMap(f -> f.getAttribute(args[1]).toString(), f -> f));

        try (
                var reader = Files.newBufferedReader(Paths.get(args[2]));
                var parser = CSVParser.parse(reader, CSVFormat.DEFAULT.withHeader("feature-id", "population-before", "population-after").withFirstRecordAsHeader());
                var writer = Files.newBufferedWriter(Paths.get(args[3]));
                var printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("feature-id", "feature-name", "population-before", "population-after"))
        ) {

            for (var record : parser) {

                var id = record.get("feature-id");
                var before = record.get("population-before");
                var after = record.get("population-after");
                var name = features.get(id).getAttribute("GNname");

                printer.printRecord(id, name, before, after);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}

package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.io.FileReader;
import java.util.*;
import java.util.stream.Collectors;

public final class CountsOption {

    @CommandLine.Option(names = "--ignored", description = "path to csv with count stations to ignore")
    private String ignored;

    @CommandLine.Option(names = "--manual", description = "path to csv with manual matched count stations and links")
    private String manual;

    @CommandLine.Option(names = "--deliminator", description = "deliminator in csv files", defaultValue = ";")
    private Character deliminator;

    private List<String> ignoredCounts = new ArrayList<>();

    private Counts<Link> manualMatchedCounts = new Counts<>();

    private static final Logger logger = LogManager.getLogger(CountsOption.class);

    /*
    TODO
     ignored counts
     manual matching
     */

    public CountsOption(){

    }

    public CountsOption(@Nullable String ignored, @Nullable String manual, Character deliminator){

        this.ignored = ignored;
        this.manual = manual;
        this.deliminator = deliminator;
    }

    public List<String> getIgnoredCounts() {
        return ignoredCounts;
    }

    public Counts<Link> getManualMatchedCounts() {
        return manualMatchedCounts;
    }

    public CountsOption initialize(){

        if(ignored != null) {
            List<CSVRecord> records = readFile(ignored);
            for(var record: records) ignoredCounts.add(record.get(0));
        }

        if(manual != null) {
            List<CSVRecord> records = readFile(manual);

            List<String> unique = records.stream()
                    .map(record -> record.get(0))
                    .distinct()
                    .collect(Collectors.toList());

            if(unique.size() != records.size()){

                for(String name: unique){

                    var entries = records.stream()
                            .filter(record -> record.get(0).equals(name))
                            .collect(Collectors.toList());

                    if(entries.size() != 24) logger.info("Station {} contains less than 24 values!", name);

                    var first = entries.stream().findFirst().get();
                    String cs = first.get(0);
                    Id<Link> linkId = Id.createLinkId(first.get(1));

                    Count<Link> newCount = manualMatchedCounts.createAndAddCount(linkId, cs);
                    int hour = 1;

                    for(var entry: entries){
                        double volume = Double.parseDouble(entry.get(3));
                        newCount.createVolume(hour++, volume);
                    }

                }
            } else {

                for (CSVRecord record : records) {

                    String name = record.get(0);
                    String idString = record.get(1);
                    Double miv = Double.parseDouble(record.get(2));
                    Id<Link> linkId = Id.createLinkId(idString);

                    manualMatchedCounts.createAndAddCount(linkId, name).createVolume(1, miv);
                }
            }
        }

        return this;
    }

    private List<CSVRecord> readFile(String filepath){

        List<CSVRecord> records;

        try(var reader = new FileReader(filepath)){

            logger.info("Reading file from {}", filepath);
            records = CSVFormat
                    .newFormat(deliminator)
                    .withAllowMissingColumnNames()
                    .withFirstRecordAsHeader()
                    .parse(reader)
                    .getRecords();

            logger.info("Done!");
        } catch (Exception e){
            e.printStackTrace();
            return new ArrayList<>();
        }

        return records;
    }

    public boolean isIgnored(String name){

        return ignoredCounts.contains(name);
    }

    public boolean isManuallyMatched(String name){

        return manualMatchedCounts.getCounts().values().stream()
                .map(Count::getCsLabel)
                .anyMatch(label -> label.equals(name));
    }

    public void mergeWithManualMatched(Counts<Link> counts){

    }

    public static void main(String[] args) {

        String ignored = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\Testdaten\\ignored.csv";
        String manual = "C:\\Users\\ACER\\Desktop\\Uni\\VSP\\Ruhrgebiet\\Testdaten\\manual.csv";

        var option = new CountsOption(ignored, manual, ';');
        option.initialize();

        return;
    }
}

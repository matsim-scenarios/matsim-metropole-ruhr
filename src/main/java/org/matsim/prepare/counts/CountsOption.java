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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class CountsOption {

    @CommandLine.Option(names = "--ignored", description = "path to csv with count stations to ignore")
    private String ignored;

    @CommandLine.Option(names = "--manual", description = "path to csv with manual matched count stations and links")
    private String manual;

    @CommandLine.Option(names = "--deliminator", description = "deliminator in csv files", defaultValue = ";")
    private Character deliminator;

    private final List<String> ignoredCounts = new ArrayList<>();

    private final Counts<Link> manualMatchedCounts = new Counts<>();

    private static final Logger logger = LogManager.getLogger(CountsOption.class);

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

    private boolean isLinkMatched(Id<Link> linkId){

        return manualMatchedCounts.getCounts().values().stream()
                .map(Count::getId)
                .anyMatch(id -> id.equals(linkId));
    }

    /**
     * Read in CSV-files and creates counts for manual matched count data
     * */
    public CountsOption initialize(){

        if(ignored != null) {
            List<CSVRecord> records = readFile(ignored);
            for(var record: records) ignoredCounts.add(record.get(0));
        }

        if(manual != null) {
            List<CSVRecord> records = readFile(manual);

            if(!records.isEmpty() && records.stream().findFirst().get().size() < 3) throw new RuntimeException("The manual-matched file needs 3 columns e.g. 'station_name', 'link_id', and 'volume'");

            List<String> unique = records.stream()
                    .map(record -> record.get(0))
                    .distinct()
                    .collect(Collectors.toList());

            if(unique.size() != records.size()){

                for(String name: unique){

                    var entries = records.stream()
                            .filter(record -> record.get(0).equals(name))
                            .collect(Collectors.toList());

                    if(entries.isEmpty()) continue;
                    if(entries.size() != 24) logger.warn("Station {} contains less than 24 values!", name);

                    var first = entries.stream().findFirst().get();
                    String cs = first.get(0);
                    Id<Link> linkId = Id.createLinkId(first.get(1));

                    if(isLinkMatched(linkId)) continue;

                    Count<Link> newCount = manualMatchedCounts.createAndAddCount(linkId, cs);
                    int hour = 1;

                    for(var entry: entries){
                        double volume = Double.parseDouble(entry.get(2));
                        newCount.createVolume(hour++, volume);
                    }

                }
            } else {

                for (CSVRecord record : records) {

                    String name = record.get(0);
                    String idString = record.get(1);
                    double miv = Double.parseDouble(record.get(2));
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

    /**
     * Merges the manual matched counts with another count file, which is created by code for example
     * */
    public void mergeWithManualMatched(Counts<Link> counts){

        for (Count<Link> actual : this.manualMatchedCounts.getCounts().values()) {

            Count<Link> newCount = counts.createAndAddCount(actual.getId(), actual.getCsLabel());

            for (int i = 1; i <= 24; i++) {

                if (actual.getVolume(i) == null) continue;
                newCount.createVolume(i, actual.getVolume(i).getValue());
            }
        }
    }
}

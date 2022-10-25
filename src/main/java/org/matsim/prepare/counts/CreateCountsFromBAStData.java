package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;
import picocli.CommandLine;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author hzoerner
 *
 */
@CommandLine.Command(name = "counts-from-bast", description = "creates MATSim from BASt Stundenwerte.txt")
public class CreateCountsFromBAStData implements MATSimAppCommand {

    @CommandLine.Option(names = "--network", description = "path to MATSim network", required = true)
    private String network;

    @CommandLine.Option(names = "--bundesstraßenData", description = "path to BASt Bundesstraßen-'Stundenwerte'-.txt file", required = true)
    private String bundesstraßenData;

    @CommandLine.Option(names = "--motorwayData", description = "path to BASt Bundesautobahnen-'Stundenwerte'-.txt file", required = true)
    private String motorwayData;

    @CommandLine.Option(names = "--stationData", description = "path to default BASt count station .csv", required = true)
    private String stationData;

    @CommandLine.Option(names = "--isMotorwayData", description = "Boolean, if BASt data contains motorways or Bundesstraßen", required = true)
    private boolean isMotorwayData;

    @CommandLine.Option(names = "--output", description = "output counts path", defaultValue = "counts-from-bast.xml.gz")
    private String output;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    @CommandLine.Mixin
    private CrsOptions crs = new CrsOptions();

    private static final Logger log = LogManager.getLogger(CreateCountsFromBAStData.class);

    public static void main(String[] args) {
        new CreateCountsFromBAStData().execute(args);
    }

    @Override
    public Integer call() {

        /*
        * TODO
        *  find opposite direction links
        *  integration of Bundesstraßen
        * */

        var stations = readBAStCountStations(stationData, shp, crs);

        matchBAStWithNetwork(network, stations);

        readHourlyTrafficVolume(bundesstraßenData, stations);
        readHourlyTrafficVolume(motorwayData, stations);

        log.info("+++++++ Map aggregated traffic volumes to count stations +++++++");
        Counts counts = new Counts();
        stations.values().stream()
                .filter(station -> station.getMatchedLink() != null)
                .forEach(station -> mapTrafficVolumeToCount(station, counts));

        counts.setYear(2022);

        log.info("+++++++ Write MATSim counts to " + output + " +++++++");
        new CountsWriter(counts).write(output);
        return 0;
    }

    private void mapTrafficVolumeToCount(BAStCountStation station, Counts counts){

        if(station.getTrafficVolume1().isEmpty()) {
            log.info("+++++++ No traffic counts available for station " + station.getName() + " +++++++");
            return;
        }

        Count count = counts.createAndAddCount(station.getMatchedLink().getId(), station.getName());
        var trafficVolumes = station.getTrafficVolume1();

        for (String hour: trafficVolumes.keySet()){

            if(hour.startsWith("0")) hour.replace("0", "");
            int h = Integer.parseInt(hour);
            count.createVolume(h, trafficVolumes.get(hour));
        }
    }

    private HashMap<String, BAStCountStation> convertToMap(List<BAStCountStation> stations){

        HashMap<String, BAStCountStation> map = new HashMap<>();

        for (BAStCountStation station: stations){

            map.putIfAbsent(station.getId(), station);
        }

        return map;
    }

    private void readHourlyTrafficVolume(String pathToDisaggregatedData, Map<String, BAStCountStation> stations){

        log.info("+++++++ Start reading traffic volume data +++++++");

        List<String> hours = new ArrayList<>();
        for(int i = 1; i < 25; i ++){

            String asString = i < 10 ? "0": "";
            asString += Integer.toString(i);

            hours.add(asString);
        }

        try(FileReader reader = new FileReader(pathToDisaggregatedData)){

            List<CSVRecord> preFilteredRecords;

            {
                CSVParser records = CSVFormat
                        .newFormat(';')
                        .withAllowMissingColumnNames()
                        .withFirstRecordAsHeader()
                        .parse(reader);


                var keys = stations.keySet();

                preFilteredRecords = records.getRecords().stream()
                        .filter(record -> keys.contains(record.get("Zst")
                                .replace("\"", "")))//ONLY FOR DEBUGGING
                        .filter(record -> {
                            int day = Integer.parseInt(record.get("Wotag").replace(" ", ""));
                            return day > 1 && day < 5;
                        })
                        .collect(Collectors.toList());
            }

            log.info("+++++++ Start aggregation of traffic volume data +++++++");

            preFilteredRecords.stream()
                    .map(record -> record.get("Zst").replace("\"", ""))
                    .distinct()
                    .forEach(number -> {

                        BAStCountStation station = stations.get(number);
                        String direction = station.getMatchedDir().endsWith("1") ? "KFZ_R1": "KFZ_R2";

                        log.info("Process data for count station " + station.getName());

                        var allEntriesOfStation = preFilteredRecords.stream()
                                .filter(record -> record.get("Zst").replace("\"", "")
                                        .equals(number))
                                .collect(Collectors.toList());

                        for(String hour: hours){
                            var hourlyTrafficVolumes = allEntriesOfStation.stream()
                                    .filter(record -> record.get("Stunde").replace("\"", "")
                                            .equals(hour))
                                    .collect(Collectors.toList());

                            double divisor = hourlyTrafficVolumes.size();
                            Double sum1 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(direction)))
                                    .reduce(Double::sum)
                                    .get();

                            Double sum2 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get("KFZ_R2")))
                                    .reduce(Double::sum)
                                    .get();

                            double mean1 = sum1 / divisor;
                            double mean2 = sum2 / divisor;

                            station.getTrafficVolume1().put(hour, mean1);
                            station.getTrafficVolume2().put(hour, mean2);
                        }
                    });

        } catch (FileNotFoundException e) {
            e.printStackTrace();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Link match(Network network, BAStCountStation station){

        log.info("+++++++ Start matching for station " + station.getName() + " +++++++");

        var link = NetworkUtils.getNearestLink(network, station.getCoord());

        if (link == null){
            log.info("+++++++ Could not match station " + station.getName() + " +++++++");
            return null;
        }

        log.info("+++++++ Matched station " + station.getName() + " to link " + link.getId());
        return link;
    }

    private void matchBAStWithNetwork(String pathToNetwork, Map<String, BAStCountStation> stations){

        Network filteredNetwork;

        {
            Network network = NetworkUtils.readNetwork(pathToNetwork);
            NetworkFilterManager filter = new NetworkFilterManager(network, new NetworkConfigGroup());
            filter.addLinkFilter(link -> link.getAllowedModes().contains(TransportMode.car));
            filter.addLinkFilter(link -> "motorway".equals(link.getAttributes().getAttribute("type")) ||
                    "primary".equals(link.getAttributes().getAttribute("type")) ||
                    "trunk".equals(link.getAttributes().getAttribute("type")));
            filter.addLinkFilter(link -> !link.getId().toString().startsWith("pt"));
            filter.addLinkFilter(link -> !link.getId().toString().startsWith("bike"));

            filteredNetwork = filter.applyFilters();
        }

        for(var station: stations.values()){

            Link matchedLink = match(filteredNetwork, station);
            station.setMatchedLink(matchedLink);
        }
    }

    private Map<String, BAStCountStation> readBAStCountStations(String pathToAggregatedData, ShpOptions shp, CrsOptions crs){

        List<BAStCountStation> stations = new ArrayList<>();

        try(FileReader reader = new FileReader(pathToAggregatedData)){

            CSVParser records = CSVFormat
                    .newFormat(';')
                    .withAllowMissingColumnNames()
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for(var record: records){

                String id = record.get("DZ_Nr");
                String name = record.get("DZ_Name");

                String dir1 = record.get("Hi_Ri1");
                String dir2 = record.get("Hi_Ri2");

                String x = record.get("Koor_UTM32_E").replace(".", "");
                String y = record.get("Koor_UTM32_N").replace(".", "");

                Coord coord = new Coord(Double.parseDouble(x), Double.parseDouble(y));

                BAStCountStation station = new BAStCountStation(id, name, dir1, dir2, coord);
                stations.add(station);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();

        } catch (IOException e) {
            e.printStackTrace();
        }

        final Predicate<BAStCountStation> filter;
        if (shp.getShapeFile() != null) {
            // default input is set to lat lon
            ShpOptions.Index index = shp.createIndex(crs.getInputCRS(), "_");
            filter = station -> index.contains(station.getCoord());
        } else filter = (station) -> true;

        List<BAStCountStation> filtered =  stations.stream()
                .filter(filter)
                .collect(Collectors.toList());

        return convertToMap(filtered);
    }
}
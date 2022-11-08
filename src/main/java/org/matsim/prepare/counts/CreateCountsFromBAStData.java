package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
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

    @CommandLine.Option(names = "--primaryData", description = "path to BASt Bundesstraßen-'Stundenwerte'-.txt file", required = true)
    private String primaryData;

    @CommandLine.Option(names = "--motorwayData", description = "path to BASt Bundesautobahnen-'Stundenwerte'-.txt file", required = true)
    private String motorwayData;

    @CommandLine.Option(names = "--stationData", description = "path to default BASt count station .csv", required = true)
    private String stationData;

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
        * */

        var stations = readBAStCountStations(stationData, shp, crs);

        matchBAStWithNetwork(network, stations);

        readHourlyTrafficVolume(primaryData, stations);
        readHourlyTrafficVolume(motorwayData, stations);

        log.info("+++++++ Map aggregated traffic volumes to count stations +++++++");
        Counts<Link> counts = new Counts<>();
        stations.values().stream()
                .filter(station -> station.getMatchedLink() != null)
                .forEach(station -> mapTrafficVolumeToCount(station, counts));

        counts.setYear(2022);

        log.info("+++++++ Write MATSim counts to " + output + " +++++++");
        new CountsWriter(counts).write(output);
        return 0;
    }

    private void mapTrafficVolumeToCount(BAStCountStation station, Counts<Link> counts){

        if(station.getTrafficVolume1().isEmpty()) {
            log.info("+++++++ No traffic counts available for station " + station.getName() + " +++++++");
            return;
        }

        Count<Link> count = counts.createAndAddCount(station.getMatchedLink().getId(), station.getName());
        Count<Link> countOpp = station.hasOppLink() ? null: counts.createAndAddCount(station.getOppLink().getId(), station.getName());
        var trafficVolumes = station.getTrafficVolume1();
        var trafficVolumesOpp = station.getTrafficVolume2();

        for (String hour: trafficVolumes.keySet()){

            if(hour.startsWith("0")) hour.replace("0", "");
            int h = Integer.parseInt(hour);
            count.createVolume(h, trafficVolumes.get(hour));

            if(countOpp != null){

                count.createVolume(h, trafficVolumesOpp.get(hour));
            }
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
                        String direction1 = station.getMatchedDir();
                        String direction2 = station.getOppDir();

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
                                    .map(record -> Double.parseDouble(record.get(direction1)))
                                    .reduce(Double::sum)
                                    .get();

                            Double sum2 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(direction2)))
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

        Index index = new Index(filteredNetwork);

        for(var station: stations.values()){

            Link matchedLink = match(filteredNetwork, station);
            station.setMatchedLink(matchedLink);

            Link opp = NetworkUtils.findLinkInOppositeDirection(matchedLink);

            if(opp == null){

                opp = index.query(station);

                if(opp == null) {

                    station.setHasNoOppLink();
                } else {
                    station.setOppLink(opp);
                }
                /*
                * TODO
                *  implement some smart query
                * */
            } else {
                station.setOppLink(opp);
            }
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

    private class Index{

        private final STRtree index = new STRtree();
        private final GeometryFactory factory = new GeometryFactory();

        public Index(Network network) {

            var it = network.getLinks().values().iterator();

            while (it.hasNext()) {

                Link link = it.next();
                Envelope env = getLinkEnv(link);

                index.insert(env, link);
            }

            index.build();
        }

        public Link neigherstNeighbourLink(BAStCountStation station){

            Link link = station.getMatchedLink();
            Envelope env = getLinkEnv(link);

            Link result = (Link) index.nearestNeighbour(env, link, new LinkDistance());
            return result;
        }

        public Link query(BAStCountStation station){

            Coordinate p = MGC.coord2Coordinate(station.getCoord());

            List<Link> result = index.query(new Envelope(p));

            if(result.isEmpty()){
                log.info("Could not find any opposite links for " + station.getMatchedLink().getId());
                return null;
            }
            if(result.size() == 2){
                if(result.get(0).getId().equals(station.getMatchedLink().getId())){
                    return result.get(1);
                } else return result.get(0);
            }
            if(result.size() == 1){

                return result.get(0);
            }
            log.info("Too many links were found for link " + station.getMatchedLink().getId());
            return null;
        }

        private Envelope getLinkEnv(Link link){

            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            Coordinate[] coordinates = {MGC.coord2Coordinate(from), MGC.coord2Coordinate(to)};

            Envelope env = factory.createLineString(coordinates).getEnvelopeInternal();

            return env;
        }
    }

    private class LinkDistance implements ItemDistance{

        @Override
        public double distance(ItemBoundable itemBoundable, ItemBoundable itemBoundable1) {

            Link link1 = (Link) itemBoundable.getItem();
            Link link2 = (Link) itemBoundable1.getItem();
            return 1000;
        }
    }
}
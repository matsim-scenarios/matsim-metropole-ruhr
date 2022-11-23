package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
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
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;
import picocli.CommandLine;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author hzoerner
 *
 */
@CommandLine.Command(name = "counts-from-bast", description = "creates MATSim from BASt Stundenwerte.txt")
public class CreateCountsFromBAStData implements MATSimAppCommand {

    @CommandLine.Option(names = "--network", description = "path to MATSim network", required = true)
    private String network;

    @CommandLine.Option(names = "--roadTypes", description = "Define on which roads counts are created")
    private List<String> roadTypes = List.of("motorway", "primary", "trunk");

    @CommandLine.Option(names = "--primaryData", description = "path to BASt Bundesstraßen-'Stundenwerte'-.txt file", required = true)
    private String primaryData;

    @CommandLine.Option(names = "--motorwayData", description = "path to BASt Bundesautobahnen-'Stundenwerte'-.txt file", required = true)
    private String motorwayData;

    @CommandLine.Option(names = "--stationData", description = "path to default BASt count station .csv", required = true)
    private String stationData;

    @CommandLine.Option(names = "--searchRange", description = "range for the buffer around count stations, in which links are queried", defaultValue = "50" )
    private double searchRange;

    @CommandLine.Option(names = "--year", description = "Year of counts", required = true)
    private int year;

    @CommandLine.Option(names = "--carOutput", description = "output car counts path", defaultValue = "car-counts-from-bast.xml.gz")
    private String carOutput;

    @CommandLine.Option(names = "--freightOutput", description = "output freight counts path", defaultValue = "freight-counts-from-bast.xml.gz")
    private String freightOutput;

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
        var stations = readBAStCountStations(stationData, shp, crs);

        matchBAStWithNetwork(network, stations);

        readHourlyTrafficVolume(primaryData, stations);
        readHourlyTrafficVolume(motorwayData, stations);

        clean(stations);

        log.info("+++++++ Map aggregated traffic volumes to count stations +++++++");
        Counts<Link> miv = new Counts<>();
        Counts<Link> freight = new Counts<>();
        stations.values().forEach(station -> mapTrafficVolumeToCount(station, miv, freight));

        miv.setYear(year);
        freight.setYear(year);

        log.info("+++++++ Write MATSim counts to {} and {} +++++++", carOutput, freightOutput);
        new CountsWriter(miv).write(carOutput);
        new CountsWriter(freight).write(freightOutput);
        return 0;
    }

    private void clean(Map<String, BAStCountStation> stations){

        log.info("+++++++ Check stations for duplicates and missing link ids +++++++");

        List<Link> uniqueIds = stations.values().stream()
                .filter(BAStCountStation::hasMatchedLink)
                .filter(BAStCountStation::hasOppLink)
                .map(station -> List.of(station.getMatchedLink(), station.getOppLink()))
                .flatMap(Collection::stream)
                .distinct()
                .collect(Collectors.toList());

        List<String> remove = new ArrayList<>();
        for(BAStCountStation station: stations.values()){

            if(!station.hasMatchedLink() && !station.hasOppLink()){
                remove.add(station.getName());
                continue;
            }

            var matched = station.getMatchedLink();
            var opp = station.getOppLink();

            if(matched.equals(opp)){
                remove.add(station.getName());
                continue;
            }

            if (uniqueIds.contains(matched) && uniqueIds.contains(opp)){
                uniqueIds.remove(matched);
                uniqueIds.remove(opp);
            } else {
                remove.add(station.getName());
            }
        }

        for(String toRemove: remove) stations.remove(toRemove);
        log.info("+++++++ Removed {} stations +++++++", remove.size());
    }

    private void mapTrafficVolumeToCount(BAStCountStation station, Counts<Link> miv, Counts<Link> freight){

        if(station.getMivTrafficVolume1().isEmpty()) {
            log.warn("No traffic counts available for station {}", station.getName());
            return;
        }

        Count<Link> mivCount = miv.createAndAddCount(station.getMatchedLink().getId(), station.getName());
        Count<Link> mivCountOpp = miv.createAndAddCount(station.getOppLink().getId(), station.getName());

        Count<Link> freightCount = freight.createAndAddCount(station.getMatchedLink().getId(), station.getName());
        Count<Link> freightCountOpp = freight.createAndAddCount(station.getOppLink().getId(), station.getName());

        var mivTrafficVolumes = station.getMivTrafficVolume1();
        var mivTrafficVolumesOpp = station.getMivTrafficVolume2();

        var freightTrafficVolumes = station.getFreightTrafficVolume1();
        var freightTrafficVolumesOpp = station.getFreightTrafficVolume2();

        for (String hour: mivTrafficVolumes.keySet()){

            if(hour.startsWith("0")) hour.replace("0", "");
            int h = Integer.parseInt(hour);
            mivCount.createVolume(h, mivTrafficVolumes.get(hour));
            mivCountOpp.createVolume(h, mivTrafficVolumesOpp.get(hour));

            freightCount.createVolume(h, freightTrafficVolumes.get(hour));
            freightCountOpp.createVolume(h, freightTrafficVolumesOpp.get(hour));
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
                        .filter(record -> keys.contains(record.get("Zst")))//ONLY FOR DEBUGGING
                        .filter(record -> {
                            int day = Integer.parseInt(record.get("Wotag").replace(" ", ""));
                            return day > 1 && day < 5;
                        })
                        .collect(Collectors.toList());

                if(preFilteredRecords.isEmpty()) log.warn("Records read from {} don't contain the stations ... ", pathToDisaggregatedData);
            }

            log.info("+++++++ Start aggregation of traffic volume data +++++++");

            preFilteredRecords.stream()
                    .map(record -> record.get("Zst").replace("\"", ""))
                    .distinct()
                    .forEach(number -> {

                        BAStCountStation station = stations.get(number);
                        String direction1 = station.getMatchedDir();
                        String direction2 = station.getOppDir();

                        String mivCol1 = "KFZ_" + direction1;
                        String mivCol2 = "KFZ_" + direction2;
                        String freightCol1 = "Lkw_" + direction1;
                        String freightCol2 = "Lkw_" + direction2;

                        log.info("Process data for count station {}", station.getName());

                        var allEntriesOfStation = preFilteredRecords.stream()
                                .filter(record -> record.get("Zst").replace("\"", "")
                                        .equals(number))
                                .collect(Collectors.toList());

                        for(String hour: hours){
                            var hourlyTrafficVolumes = allEntriesOfStation.stream()
                                    .filter(record -> record.get("Stunde").replace("\"", "")
                                            .equals(hour))
                                    .collect(Collectors.toList());

                            if(hourlyTrafficVolumes.isEmpty()) {
                                log.warn("No volume for station {} at hour {}", station.getName(), hour);
                                continue;
                            }

                            double divisor = hourlyTrafficVolumes.size();
                            Double sumMiv1 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(mivCol1)))
                                    .reduce(Double::sum)
                                    .get();

                            Double sumMiv2 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(mivCol2)))
                                    .reduce(Double::sum)
                                    .get();

                            double meanMiv1 = sumMiv1 / divisor;
                            double meanMiv2 = sumMiv2 / divisor;

                            station.getMivTrafficVolume1().put(hour, meanMiv1);
                            station.getMivTrafficVolume2().put(hour, meanMiv2);

                            //Same procedure for freight
                            Double sumFreight1 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(freightCol1)))
                                    .reduce(Double::sum)
                                    .get();

                            Double sumFreight2 = hourlyTrafficVolumes.stream()
                                    .map(record -> Double.parseDouble(record.get(freightCol2)))
                                    .reduce(Double::sum)
                                    .get();

                            double meanFreight1 = sumFreight1 / divisor;
                            double meanFreight2 = sumFreight2 / divisor;

                            station.getFreightTrafficVolume1().put(hour, meanFreight1);
                            station.getFreightTrafficVolume2().put(hour, meanFreight2);
                        }
                    });

        } catch (IOException e) {
            e.printStackTrace();

        }
    }

    private void match(Network network, Index index, BAStCountStation station){

        var matched = NetworkUtils.getNearestLink(network, station.getCoord());

        if (matched == null){
            log.info("Query is used for matching!");
            matched = index.query(station);
            if(matched == null) {
                station.setHasNoMatchedLink();
                log.warn("Could not match station {}", station.getName());
                return;
            }
        }

        station.setMatchedLink(matched);
        index.remove(matched);

        Link opp = NetworkUtils.findLinkInOppositeDirection(matched);

        if(opp == null) {
            opp = index.query(station);
            if (opp == null) {
                log.warn("Could not match station {} to an opposite link", station.getName());
                station.setHasNoOppLink();
                return;
            }
        }
        station.setOppLink(opp);
        index.remove(opp);
    }

    private List<Predicate<String>> createRoadTypeFilter(List<String> types){

        List<Predicate<String>> filter = new ArrayList<>();

        for(String type: types){

            Predicate<String> p = string -> {
                Pattern pattern = Pattern.compile(type, Pattern.CASE_INSENSITIVE);

                return pattern.matcher(string).find();
            };

            filter.add(p);
        }
        return filter;
    }

    private void matchBAStWithNetwork(String pathToNetwork, Map<String, BAStCountStation> stations){

        Network filteredNetwork;

        List<Predicate<String>> roadTypeFilter = createRoadTypeFilter(roadTypes);

        {
            Network network = NetworkUtils.readNetwork(pathToNetwork);
            NetworkFilterManager filter = new NetworkFilterManager(network, new NetworkConfigGroup());
            filter.addLinkFilter(link -> link.getAllowedModes().contains(TransportMode.car));
            filter.addLinkFilter(link -> roadTypeFilter.stream().
                    anyMatch(predicate -> predicate.test(link.getAttributes().getAttribute("type").toString())));
            filter.addLinkFilter(link -> !link.getId().toString().startsWith("pt"));
            filter.addLinkFilter(link -> !link.getId().toString().startsWith("bike"));

            filteredNetwork = filter.applyFilters();
        }

        Index index = new Index(filteredNetwork, searchRange);

        log.info("+++++++ Match BASt stations with network +++++++");
        for(var station: stations.values()) match(filteredNetwork, index, station);
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
        private final double range;

        public Index(Network network, double searchRange) {

            this.range = searchRange;

            for (Link link : network.getLinks().values()) {

                Envelope env = getLinkEnvelope(link);
                index.insert(env, link);
            }

            index.build();
        }

        public Link query(BAStCountStation station){

            Point p = MGC.coord2Point(station.getCoord());
            Envelope searchArea = p.buffer(this.range).getEnvelopeInternal();

            List<Link> result = index.query(searchArea);

            if(result.isEmpty()) return null;
            if(result.size() == 1) return result.get(0);
            Link closest = result.stream().findFirst().get();

            for(Link l: result){

                if(station.getLinkDirection(l).equals(station.getMatchedDir())) continue;
                if(l.equals(station.getMatchedLink())) continue;

                double distance = link2LineString(l).distance(p);
                double curClosest = link2LineString(closest).distance(p);

                if(distance < curClosest) closest = l;
            }

            return closest;
        }

        private Envelope getLinkEnvelope(Link link){
            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            Coordinate[] coordinates = {MGC.coord2Coordinate(from), MGC.coord2Coordinate(to)};

            return factory.createLineString(coordinates).getEnvelopeInternal();
        }

        private LineString link2LineString(Link link){

            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            Coordinate[] coordinates = {MGC.coord2Coordinate(from), MGC.coord2Coordinate(to)};

            return factory.createLineString(coordinates);
        }

        public void remove(Link link){

            Envelope env = getLinkEnvelope(link);

            index.remove(env, link);
        }
    }
}
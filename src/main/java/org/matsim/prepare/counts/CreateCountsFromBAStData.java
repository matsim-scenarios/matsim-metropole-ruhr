package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;
import picocli.CommandLine;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@CommandLine.Command(name = "counts-from-bast", description = "creates MATSim from BASt Stundenwerte.txt")
public class CreateCountsFromBAStData implements MATSimAppCommand {

    @CommandLine.Option(names = "--network", description = "path to MATSim network", required = true)
    private String network;

    @CommandLine.Option(names = "--disaggregatedData", description = "path to 'Stundenwerte'-.txt file", required = true)
    private String disaggregatedData;

    @CommandLine.Option(names = "--aggregatedData", description = "path to default BASt DTV values", required = true)
    private String aggregatedData;

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
        *  network reading
        *  count mapping/matching
        *  match directions
        *  aggregate count data
        *  join hour-values on data set with coordinates
        * */

        var stations = readBAStCountStations(aggregatedData, shp, crs);

        matchBAStWithNetwork(network, stations);

        Counts counts = new Counts();
        stations.stream()
                .filter(station -> station.getMatchedLink() != null)
                .forEach(station -> counts.createAndAddCount(station.getMatchedLink(), station.getName())
                        .createVolume(1, 10000));

        counts.setYear(2022);

        new CountsWriter(counts).write(output);
        return 0;
    }

    private void addRemovedLinksToNetwork(List<Link> removedLinks, Network network){
        removedLinks.forEach(network::addLink);
    }

    private Id<Link> match(Network network, BAStCountStation station){

        log.info("+++++++ Start matching for station " + station.getName() + " +++++++");

        var link = NetworkUtils.getNearestLink(network, station.getCoord());

        if (link == null){
            log.info("+++++++ Could not match station " + station.getName() + " +++++++");
            return null;
        }

        log.info("+++++++ Matched station " + station.getName() + " to link " + link.getId());
        return link.getId();
    }

    private void matchBAStWithNetwork(String pathToNetwork, List<BAStCountStation> stations){

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

        for(var station: stations){

            Id<Link> matchedId = match(filteredNetwork, station);
            station.setMatchedLink(matchedId);
        }
    }

    private List<BAStCountStation> readBAStCountStations(String pathToAggregatedData, ShpOptions shp, CrsOptions crs){

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

        return stations.stream()
                .filter(filter)
                .collect(Collectors.toList());
    }

    private static class BAStCountStation{

        private final String name;
        private final String id;
        private final String dir1;
        private final String dir2;

        private Id<Link> matchedLink;

        private final Coord coord;

        private int dtv1;
        private int dtv2;

        private boolean hasDTV;

        BAStCountStation(String id, String name, String dir1, String dir2, Coord coord){

            this.coord = coord;
            this.dir1 = dir1;
            this.dir2 = dir2;
            this.id = id;
            this.name = name;


            this.hasDTV = false;
        }

        public void setDtv1(int dtv1) {
            this.dtv1 = dtv1;
        }

        public void setDtv2(int dtv2) {
            this.dtv2 = dtv2;
        }

        public int getDtv1() {
            return dtv1;
        }

        public int getDtv2() {
            return dtv2;
        }

        public String getId() {
            return id;
        }

        public String getDir1() {
            return dir1;
        }

        public String getDir2() {
            return dir2;
        }

        public Coord getCoord() {
            return coord;
        }

        public String getName() {
            return name;
        }

        public Id<Link> getMatchedLink() {
            return matchedLink;
        }

        public void setMatchedLink(Id<Link> matchedLink) {
            this.matchedLink = matchedLink;
        }
    }
}

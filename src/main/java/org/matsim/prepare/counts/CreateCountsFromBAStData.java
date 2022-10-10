package org.matsim.prepare.counts;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.counts.Counts;
import picocli.CommandLine;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private static final Logger log = LogManager.getLogger(CreateCountsFromBAStData.class);

    public static void main(String[] args) {
        new CreateCountsFromBAStData().execute(args);
    }

    @Override
    public Integer call() throws Exception {

        /*
        * TODO
        *  network reading
        *  count mapping/matching
        *  match directions
        *  aggregate count data
        *  join hour-values on data set with coordinates
        * */

        var stations = readBAStCountStations(aggregatedData);
        matchBAStWithNetwork(network, stations);

        Counts counts = new Counts();

        return null;
    }

    private Id<Link> match(Network network, BAStCountStation station){

        List<Link> removed = new ArrayList<>();

        while (true){

            var link = NetworkUtils.getNearestLink(network, station.coord);

            if(link.getAttributes().getAttribute("type").equals("motorway") ||
                    link.getAttributes().getAttribute("type").equals("primary")){

                if(!removed.isEmpty()) removed.forEach(network::addLink);
                return link.getId();
            } else {

                removed.add(link);
                network.removeLink(link.getId());
            }
        }
    }

    private void matchBAStWithNetwork(String pathToNetwork, List<BAStCountStation> stations){

        Network network = NetworkUtils.readNetwork(pathToNetwork);

        for(var station: stations){

            Id<Link> matchedId = match(network, station);
            Link matchedLink = network.getLinks().get(matchedId);
            Link oppDirLink = NetworkUtils.findLinkInOppositeDirection(matchedLink);
        }
        return;
    }

    private List<BAStCountStation> readBAStCountStations(String pathToAggregatedData){

        List<BAStCountStation> stations = new ArrayList<>();

        try(FileReader reader = new FileReader(pathToAggregatedData)){

            CSVParser records = CSVFormat
                    .newFormat(';')
                    .withAllowMissingColumnNames()
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for(var record: records){

                String id = record.get("DZ_Nr");

                String dir1 = record.get("Hi_Ri1");
                String dir2 = record.get("Hi_Ri2");

                String x = record.get("Koor_UTM32_E").replace(".", "");
                String y = record.get("Koor_UTM32_N").replace(".", "");

                Coord coord = new Coord(Double.parseDouble(x), Double.parseDouble(y));

                BAStCountStation station = new BAStCountStation(id, dir1, dir2, coord);
                stations.add(station);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();

        } catch (IOException e) {
            e.printStackTrace();
        }

        return stations;
    }

    private static class BAStCountStation{

        private final String id;
        private final String dir1;
        private final String dir2;

        private final Coord coord;

        private int dtv1;
        private int dtv2;

        private boolean hasDTV;

        BAStCountStation(String id, String dir1, String dir2, Coord coord){

            this.coord = coord;
            this.dir1 = dir1;
            this.dir2 = dir2;
            this.id = id;

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
    }
}

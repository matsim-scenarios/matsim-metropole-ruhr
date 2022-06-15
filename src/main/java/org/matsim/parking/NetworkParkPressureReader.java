package org.matsim.parking;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.matsim.parking.UtilityBasedParkingPressureEventHandler.PARK_PRESSURE_ATTRIBUTE_NAME;

/**
 * @author gryb
 */
public class NetworkParkPressureReader  implements MATSimAppCommand {

    @CommandLine.Option(names = "--network", description = "Path to input network", required = true)
    private Path networkPath;

    @CommandLine.Option(names = "--inputShpFile", description = "Path to input shp File", required = true)
    private Path inputShpFile;

    @CommandLine.Option(names = "--output-network", description = "Path to output network with parking attributes", required = true)
    private Path outputNetwork;

    @CommandLine.Mixin
    private CrsOptions crs;

    @CommandLine.Mixin
    private ShpOptions shp;

    private static final Logger log = Logger.getLogger(NetworkParkPressureReader.class);


    @Override
    public Integer call() throws Exception {

        Network network = NetworkUtils.readNetwork(networkPath.toString());
        NetworkParkPressureReader networkParkPressureReader = new NetworkParkPressureReader();
        addLinkParkAttributes(network, inputShpFile.toString());
        network.getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:25832");
        NetworkUtils.writeNetwork(network,"../public-svn/matsim/scenarios/countries/de/hamburg/hamburg-v2/hamburg-v2.0/reallab2030plus/input/network/hamburg-v2.0-reallab2030plus-network-with-pt-and-parkingPressure.xml.gz");
        log.info("done");


        return null;
    }




    private final Map<String, Double> link2ParkPressure = new HashMap<>();

    public static void main(String[] args) throws IOException {}

    public void addLinkParkAttributes(Network network, String shapeFile) throws IOException {


    /*    for (Link link : network.getLinks().values()) {
            String attribute = PARK_PRESSURE_ATTRIBUTE_NAME;
            if (!this.link2ParkPressure.containsKey(link.getId().toString())) {
                link.getAttributes().putAttribute(attribute, Double.parseDouble(parkPressureScoreParams[2]) * parkPressureScoreConstant);
            } else {
                Double parkPressure = link2ParkPressure.get(link.getId().toString());
                if (parkPressure == 0.7) {
                    link.getAttributes().putAttribute(attribute, Double.parseDouble(parkPressureScoreParams[0]) * parkPressureScoreConstant);
                } else if (parkPressure == 0.85) {
                    link.getAttributes().putAttribute(attribute, Double.parseDouble(parkPressureScoreParams[1]) * parkPressureScoreConstant);
                }
            }
        }*/
    }



}
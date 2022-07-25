package org.matsim.parking;

import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author gryb
 */
public class NetworkParkPressureReader implements MATSimAppCommand {

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

    public static final String ACCESSTIMELINKATTRIBUTECAR = "accesstime_car";
    public static final String EGRESSTIMELINKATTRIBUTECAR = "egresstime_car";

    public static void main(String[] args) throws IOException {
        new NetworkParkPressureReader().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network network = NetworkUtils.readNetwork(networkPath.toString());
        network.getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:25832");
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(inputShpFile.toString());

        for (Link l : network.getLinks().values()) {
            boolean setParkAttribute = false;
            if (l.getAllowedModes().contains(TransportMode.car)) {
                for (SimpleFeature feature : features) {
                    Geometry g = (Geometry) feature.getDefaultGeometry();
                    if (g.contains(MGC.coord2Point(l.getCoord()))) {
                        l.getAttributes().putAttribute("cost", feature.getAttribute("cost"));
                        //this assumes that both are the same
                        l.getAttributes().putAttribute(ACCESSTIMELINKATTRIBUTECAR, feature.getAttribute("accesstime"));
                        l.getAttributes().putAttribute(EGRESSTIMELINKATTRIBUTECAR, feature.getAttribute("egresstime"));
                        setParkAttribute = true;
                    }
                }
                if (setParkAttribute == false) {
                    l.getAttributes().putAttribute("cost", 100000.0);
                    l.getAttributes().putAttribute(ACCESSTIMELINKATTRIBUTECAR, 0);
                    l.getAttributes().putAttribute(EGRESSTIMELINKATTRIBUTECAR, 0);
                }
            }
        }

        NetworkUtils.writeNetwork(network, outputNetwork.toString());
        log.info("done");
        return null;
    }
}
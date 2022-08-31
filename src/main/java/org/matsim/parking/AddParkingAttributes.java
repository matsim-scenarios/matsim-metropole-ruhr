package org.matsim.parking;

import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

public class AddParkingAttributes implements MATSimAppCommand {

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
        new AddParkingAttributes().execute(args);
    }


    @Override
    public Integer call() throws Exception {

        Network network = NetworkUtils.readNetwork(networkPath.toString());
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(inputShpFile.toString());
        log.info("read shp File");

        for (var link : network.getLinks().values()) {

            if (!link.getAllowedModes().contains("pt")) {

                Coord coord = link.getCoord();
                Point point = MGC.coord2Point(coord);

                double oneHourPCost = 0.;
                double extraHourPCost = 0.;
                double maxDailyPCost = 0.;
                double maxParkingTime = 30.;
                double pFine = 0.;
                double resPCosts = 0.;
                double accesstime = 0.;
                double egresstime = 0.;
                Long zoneName = Long.valueOf(100);
                String zoneGroup = "";


                for (SimpleFeature feature : features) {
                    Geometry geometry = (Geometry) feature.getDefaultGeometry();

                    if (geometry.covers(point)) {

                        if (feature.getAttribute("id") != null) {
                            zoneName = (Long) feature.getAttribute("id");
                        }

                        if (feature.getAttribute("GN") != null) {
                            zoneGroup = (String) feature.getAttribute("GN");
                        }

                        if (feature.getAttribute("cost") != null) {
                            if (feature.getAttribute("cost").equals(0)) {
                                feature.setAttribute("cost", 0.0);
                            }
                            oneHourPCost = (Double) feature.getAttribute("cost");
                            extraHourPCost = (Double) feature.getAttribute("cost");
                            maxParkingTime = (Double) feature.getAttribute("cost");
                            maxDailyPCost = (Double) feature.getAttribute("cost");
                            pFine = (Double) feature.getAttribute("cost");
                            resPCosts = (Double) feature.getAttribute("cost");
                        }

                        if (feature.getAttribute("accestime") != null) {
                            accesstime = (Double) feature.getAttribute("accestime");
                        }

                        if (feature.getAttribute("egresstime") != null) {
                            egresstime = (Double) feature.getAttribute("egresstime");
                        }

                        break;
                    }
                }



                link.getAttributes().putAttribute("oneHourPCost", oneHourPCost);
                link.getAttributes().putAttribute("extraHourPCost", extraHourPCost);
                link.getAttributes().putAttribute("maxDailyPCost", maxDailyPCost);
                link.getAttributes().putAttribute("maxPTime", maxParkingTime);
                link.getAttributes().putAttribute("pFine", pFine);
                link.getAttributes().putAttribute("resPCosts", resPCosts);
                link.getAttributes().putAttribute("zoneName", zoneName);
                link.getAttributes().putAttribute("zoneGroup", zoneGroup);
                link.getAttributes().putAttribute(ACCESSTIMELINKATTRIBUTECAR, accesstime);
                link.getAttributes().putAttribute(EGRESSTIMELINKATTRIBUTECAR, egresstime);


                link.getAttributes().putAttribute("accesstime_pt", 0.0);
                link.getAttributes().putAttribute("egresstime_pt", 0.0);
                link.getAttributes().putAttribute("accesstime_bike", 0.0);
                link.getAttributes().putAttribute("egresstime_bike", 0.0);
                link.getAttributes().putAttribute("accesstime_ride", 0.0);
                link.getAttributes().putAttribute("egresstime_ride", 0.0);
            }
        }

        NetworkUtils.writeNetwork(network, outputNetwork.toString());
        return null;
    }

}



package org.matsim.prepare.commercial;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.MultiLineString;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TransferRestrictions {
    /*
    Transfers Restrictions from the shapefile to the given Network.
     */

    private static final String ruhrNetworkPath = "D:\\Projects\\VSP\\RUHR\\metropole-ruhr-v2.0.network_resolutionHigh-with-pt.xml";
    private static final String restrictionPath = "D:\\Projects\\VSP\\RUHR\\commercialTraffic\\Restriktionen_RVR_20231121\\Restriktionen_RVR_20231121.shp";
    private static final String outputPath = "scenarios/metropole-ruhr-v1.0/ruhr_network_with_restrictions.xml.gz";

    public static void main(String[] args){
        //Read in shp and xml files
        Network network = NetworkUtils.readNetwork(ruhrNetworkPath);
        Network network_no_pt = NetworkUtils.createNetwork();
        ShpOptions shp = new ShpOptions(Path.of(restrictionPath), null, null);

        //Create copy of network which only contains links, where car/freight-traffic is allowed (not pt, no bike-only-links, ...)
        for(Node n : network.getNodes().values()){
            if(!n.getId().toString().startsWith("pt") && !n.getId().toString().startsWith("bike")){
                network_no_pt.addNode(n);
            }
        }
        for(Link l : network.getLinks().values()){
            if(l.getAllowedModes().contains("car") || l.getAllowedModes().contains("freight")){
                network_no_pt.addLink(l);
            }
        }

        // Add all freight-modes to all car-links
        Collection<? extends Link> links = network.getLinks().values();
        Set<String> all = new HashSet<>(Arrays.asList("truck8t", "truck18t", "truck26t", "truck40t"));
        for (Link link : links){
            if(link.getAllowedModes().contains("car")){
                Set<String> combined = Stream.concat(link.getAllowedModes().stream(), all.stream()).collect(Collectors.toSet());
                link.setAllowedModes(combined);
            }
        }

        // Remove freight-modes from restricted links
        for(SimpleFeature i : shp.readFeatures()){
            //Transform into LineString object
            MultiLineString way = (MultiLineString) i.getDefaultGeometry();
            LineString waypart = (LineString) way.getGeometryN(0);

            //Read coordinates and prepare Coord
            Coordinate[] coordinates = waypart.getCoordinates();

            //Translate Coordinate into Coord-objects and get the nearest link for every node-pair
            for (int j = 0; j < coordinates.length; j++){
                Coord coord = MGC.coordinate2Coord(coordinates[j]);

                //Finds the center of two nodes and searches for the link from there
                if(j != 0){
                    Coord center = CoordUtils.getCenter(coord, coord);
                    Link nearestLink = NetworkUtils.getNearestLinkExactly(network_no_pt, center);
                    Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(nearestLink);
                    Set<String> allowedModes = new HashSet<>(nearestLink.getAllowedModes()); // Makes a copy, because original is immutable

                    //Sort out links, that were assigned incorrectly
                    double distance = NetworkUtils.getEuclideanDistance(nearestLink.getFromNode().getCoord(), center);
                    double links_euclidean_length = NetworkUtils.getEuclideanDistance(nearestLink.getFromNode().getCoord(), nearestLink.getToNode().getCoord());
                    if(distance > links_euclidean_length+20){
                        continue;
                    }

                    //Get value if numeric
                    float value = -1;
                    try{
                        value = Float.parseFloat(((String)i.getAttribute("wert")).replace(",", "."));
                    } catch(Exception ignored){

                    }

                    //Filter out all modes, that are not allowed
                    switch((String) i.getAttribute("typ")){
                        case "253":
                            // No freight allowed
                            allowedModes.remove("freight");
                            allowedModes.remove("truck40t");
                            allowedModes.remove("truck26t");
                            allowedModes.remove("truck18t");
                            allowedModes.remove("truck8t");
                            break;
                        case "262":
                            // Weight specific restriction
                            if(value > 12){ // # TODO: Das hier scheint in der Dok falsch zu sein: < 12 waere sinnvoller
                                allowedModes.remove("freight");
                                allowedModes.remove("truck40t");
                                allowedModes.remove("truck26t");
                                allowedModes.remove("truck18t");
                                allowedModes.remove("truck8t");
                            } else {
                                allowedModes.remove("freight");
                                allowedModes.remove("truck40t");
                                allowedModes.remove("truck26t");
                                allowedModes.remove("truck18t");
                            }
                            break;
                        case "264":
                            // Height restriction: No freight allowed if restriction is <= 4 meters
                            if (value <= 4 && value != -1){
                                allowedModes.remove("freight");
                                allowedModes.remove("truck40t");
                                allowedModes.remove("truck26t");
                                allowedModes.remove("truck18t");
                                allowedModes.remove("truck8t");
                            }
                            break;
                        case "265":
                            // Width restriction: No freight allowed if restriction is <= 2.5 meters
                            if (value <= 2.5 && value != -1){
                                allowedModes.remove("freight");
                                allowedModes.remove("truck40t");
                                allowedModes.remove("truck26t");
                                allowedModes.remove("truck18t");
                                allowedModes.remove("truck8t");
                            }
                            break;
                        case "266":
                            // Length restriction: No freight > 8t allowed if restriction is <= 10 meters
                            if (value <= 10 && value != -1){
                                allowedModes.remove("freight");
                                allowedModes.remove("truck40t");
                                allowedModes.remove("truck26t");
                                allowedModes.remove("truck18t");
                            }
                            break;
                        default:
                            break;
                    }

                    //Update the allowed link modes
                    nearestLink.setAllowedModes(allowedModes);
                    if(oppositeLink != null){
                        oppositeLink.setAllowedModes(allowedModes);
                    }
                }
            }
        }
        NetworkUtils.writeNetwork(network, outputPath);
    }
}

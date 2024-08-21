package org.matsim.prepare.commercial;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.opengis.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.MultiLineString;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TransferRestrictions {
    /*
    Transfers Restrictions from the shapefile to the given Network.
     */

//    private static final String ruhrNetworkPath = "D:\\Projects\\VSP\\RUHR\\metropole-ruhr-v2.0.network_resolutionHigh-with-pt.xml";
    private static final String ruhrNetworkPath = "D:\\Projects\\VSP\\RUHR\\metropole-ruhr-v2.0_network.xml";
    private static final String restrictionPath = "D:\\Projects\\VSP\\RUHR\\commercialTraffic\\Restriktionen_RVR_20231121\\Restriktionen_RVR_20231121.shp";
    private static final String outputPath = "scenarios/metropole-ruhr-v1.0/ruhr_network_with_restrictions.xml.gz";

    /**
     * Create a copy of the given network with car-links only (no pt, no bike-only-links, ...)
     */
    private static Network copyNetworkWithoutPT(Network network_with_pt){
        Network network_no_pt = NetworkUtils.createNetwork();
        //Create copy of network which only contains links, where car/freight-traffic is allowed (not pt, no bike-only-links, ...)
        for(Node n : network_with_pt.getNodes().values()){
            if(!n.getId().toString().startsWith("pt") && !n.getId().toString().startsWith("bike")){
                network_no_pt.addNode(n);
            }
        }
        for(Link l : network_with_pt.getLinks().values()){
            if(l.getAllowedModes().contains("car") || l.getAllowedModes().contains("freight")){
                network_no_pt.addLink(l);
            }
        }
        return network_no_pt;
    }

    private static void findLinksFromFeature(SimpleFeature i, Network network_no_pt, Map<Id<Link>, String[]> linkId2RestrictionCode){
        //Transform into LineString object (The MultilineString in this file saves all the Geometry in the first LineString)
        //System.out.println(i.getID());
        MultiLineString way = (MultiLineString) i.getDefaultGeometry();
        LineString waypart = (LineString) way.getGeometryN(0); //Get first LineString (only relevant one)
        Coordinate[] coordinates = waypart.getCoordinates(); //All vertices of the LineString

        //TODO DEBUG
        boolean foundLink = false;

        //Check for every vertices-combination if there is a link that the restriction can be assigned to
        for(int j = 1; j < coordinates.length; j++){
            for(int k = j; k < coordinates.length; k++){
                //Get two vertices and compute the Euclidean center of then
                Coord start = MGC.coordinate2Coord(coordinates[k]);
                Coord end = MGC.coordinate2Coord(coordinates[k-j]);
                Coord center = CoordUtils.getCenter(start, end);

                //Find the nearest link to the center vertice
                Link centerNearestLink = NetworkUtils.getNearestLinkExactly(network_no_pt, center);

                //Assure that the link assignment was correct
                //-> Check if the link was already found
                {
                if(linkId2RestrictionCode.containsKey(centerNearestLink.getId())) continue;
                }

                //-> Check if distance of start or end to the found link is too high
                {
                    //Compute the distance from start to the found link
                    double x_s = start.getX();
                    double y_s = start.getY();
                    double x_e = end.getX();
                    double y_e = end.getY();
                    double x_1 = centerNearestLink.getFromNode().getCoord().getX();
                    double y_1 = centerNearestLink.getFromNode().getCoord().getY();
                    double x_2 = centerNearestLink.getToNode().getCoord().getX();
                    double y_2 = centerNearestLink.getToNode().getCoord().getY();
                    double distance_start = Math.abs((y_2-y_1)*x_s - (x_2-x_1)*y_s + x_2*y_1 - y_2*x_1) / Math.sqrt(Math.pow(x_2-y_1, 2) + Math.pow(x_2 - x_1, 2));
                    double distance_end = Math.abs((y_2-y_1)*x_e - (x_2-x_1)*y_e + x_2*y_1 - y_2*x_1) / Math.sqrt(Math.pow(x_2-y_1, 2) + Math.pow(x_2 - x_1, 2));

                    if(distance_start > 10 ||distance_end > 10) continue;
                }

                //-> Check if rotation difference is too high
                {
                    double link_euclidean_length = NetworkUtils.getEuclideanDistance(centerNearestLink.getFromNode().getCoord(), centerNearestLink.getToNode().getCoord());
                    double[] lineAsVector = new double[]{end.getX() - start.getX(), end.getY() - start.getY()};
                    double[] linkAsVector = new double[]{
                            centerNearestLink.getToNode().getCoord().getX() - centerNearestLink.getFromNode().getCoord().getX(),
                            centerNearestLink.getToNode().getCoord().getY() - centerNearestLink.getFromNode().getCoord().getY()};
                    // Compute the rotation-angle between the two using the dot-product: cos(rot)= (a*b) / (||a||*||b||)
                    double rotation = (lineAsVector[0] * linkAsVector[0] + lineAsVector[1] * linkAsVector[1]) /
                            (link_euclidean_length * NetworkUtils.getEuclideanDistance(start, end));
                    //Convert into degree
                    rotation = (Math.acos(Math.abs(rotation)) / Math.PI) * 180;

                    if (rotation > 5) { //5 is an arbitrary choice
                        continue;
                    }
                }

                //Final step: Save the restrictions in the map
                foundLink = true;
                Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(centerNearestLink);

                linkId2RestrictionCode.putIfAbsent(centerNearestLink.getId(),
                        new String[]{(String) i.getAttribute("typ"),
                                ((String)i.getAttribute("wert")).replace(",", ".")});

                if(oppositeLink == null) continue;
                linkId2RestrictionCode.putIfAbsent(oppositeLink.getId(),
                        new String[]{(String) i.getAttribute("typ"),
                                ((String)i.getAttribute("wert")).replace(",", ".")});

            }
        }
    }

    public static void applyRestrictions(String ruhrNetworkPath, String restrictionPath, String outputPath){
        //Read in shp and xml files
        Network network = NetworkUtils.readNetwork(ruhrNetworkPath);
        Network network_no_pt = copyNetworkWithoutPT(network);
        ShpOptions shp = new ShpOptions(Path.of(restrictionPath), null, null);

        // Add all freight-modes to all car-links
        Collection<? extends Link> links = network.getLinks().values();
        Set<String> all = new HashSet<>(Arrays.asList("freight", "truck8t", "truck18t", "truck26t", "truck40t"));
        for (Link link : links){
            if(link.getAllowedModes().contains("car")){
                Set<String> combined = Stream.concat(link.getAllowedModes().stream(), all.stream()).collect(Collectors.toSet());
                link.setAllowedModes(combined);
            }
        }

        Map<Id<Link>, String[]> linkId2RestrictionCode = new HashMap<>();
        //Iterate through every SimpleFeature and find the corresponding Link in the network and save the restriction code in the Map.
        for(SimpleFeature i : shp.readFeatures()) {
            findLinksFromFeature(i, network_no_pt, linkId2RestrictionCode);
        }

        //Apply Restrictions to network
        for(Map.Entry<Id<Link>, String[]> e : linkId2RestrictionCode.entrySet()) {
            Id<Link> id = e.getKey();
            String type = e.getValue()[0];
            float value = -1;
            try{ value = Float.parseFloat(e.getValue()[1]); } catch(Exception ignored) {}
            //System.out.println(id);
            Set<String> allowedModes = new HashSet<>(network.getLinks().get(id).getAllowedModes()); // Makes a copy, because original is immutable

            switch(type){
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
            network.getLinks().get(id).setAllowedModes(allowedModes);
        }
        NetworkUtils.writeNetwork(network, outputPath);
    }

    public static void main(String[] args) {
        TransferRestrictions.applyRestrictions(ruhrNetworkPath, restrictionPath, outputPath);
    }
}


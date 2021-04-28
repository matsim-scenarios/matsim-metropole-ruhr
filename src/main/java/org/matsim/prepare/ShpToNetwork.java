package org.matsim.prepare;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiLineString;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;

public class ShpToNetwork {

	private static final Logger logger = Logger.getLogger(ShpToNetwork.class);
	private final double maxSearchRadius = 2.;

    public static void main (String[] args) {
    	
    	String rootDirectory = null;
		
		if (args.length <= 0) {
			logger.warn("Please set the root directory.");
		} else {
			rootDirectory = args[0];
		}
		
	    final String inputShapeNetwork = rootDirectory + "shared-svn/projects/rvr-metropole-ruhr/data/2021-03-05_radwegeverbindungen_VM_Freizeitnetz_test/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.shp";
		Network network = new ShpToNetwork().run(Paths.get(inputShapeNetwork));
		
		NetworkWriter writer = new NetworkWriter(network);
		
	    final String matSimOutputNetwork ="public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/bicycle-infrastructure/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.xml.gz";
	    writer.write(rootDirectory + matSimOutputNetwork);
    }
    	
    public Network run(Path inputShapeNetwork) {
    	int helpLinkCounter = 0;
    	String idPrefix = "bike_" + FilenameUtils.removeExtension(inputShapeNetwork.getFileName().toString()) + "_";

    	Map<String,Id<Node>> coordString2nodeId = new HashMap<>();
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(inputShapeNetwork.toString());
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network network = scenario.getNetwork();

        for (SimpleFeature feature: features) {
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            try {
                MultiLineString multiLineString = (MultiLineString) geometry;
                Coordinate[] coordinates = multiLineString.getCoordinates();
                
                // coord0 -----> coord1 -----> coord2 (length = 3)

                int coordCounter = 0;
                for (Coordinate coordinate : coordinates) {
                	
                	Coord coordFrom = new Coord(coordinate.getX(), coordinate.getY());
                    String coordFromString = coordFrom.getX() + "-" + coordFrom.getY();
                    
                    Node n1;
                    if (coordString2nodeId.get(coordFromString) == null) {
                        String fromId = idPrefix + feature.getAttribute("fid") + "_" + coordCounter + "_n1";
    					n1 = NetworkUtils.createNode(Id.createNodeId(fromId), coordFrom );
                        coordString2nodeId.put(coordFromString, n1.getId());
                        
                        // tag first multiLine node
                        if (coordCounter == 0) {
                            n1.getAttributes().putAttribute("multiLineStartOrEnd", true);
                        } else {
                        	n1.getAttributes().putAttribute("multiLineStartOrEnd", false);
                        }
                        
                        network.addNode(n1);
                     
                    } else {
                    	n1 = network.getNodes().get(coordString2nodeId.get(coordFromString));
                    }
                                            
                    if (coordCounter < coordinates.length - 1) {
                    	Coord coordTo = new Coord(coordinates[coordCounter + 1].getX(), coordinates[coordCounter + 1].getY());
                        String coordToString = coordTo.getX() + "-" + coordTo.getY();

                        Node n2;
                        if (coordString2nodeId.get(coordToString) == null) {
                            String toId = idPrefix + feature.getAttribute("fid") + "_" + coordCounter + "_n2";
                            n2 = NetworkUtils.createNode(Id.createNodeId(toId), coordTo );
                            coordString2nodeId.put(coordToString, n2.getId());
                            network.addNode(n2);
                            if (coordCounter == coordinates.length - 2) {
                            	// n2 is the last coordinate of this multiLine
                                n2.getAttributes().putAttribute("multiLineStartOrEnd", true);
                            } else {
                            	n2.getAttributes().putAttribute("multiLineStartOrEnd", false);
                            }
                        } else {
                        	n2 = network.getNodes().get(coordString2nodeId.get(coordToString));
                        }
                        
                        // now create the link to n2
                        double length = NetworkUtils.getEuclideanDistance(n1.getCoord(), n2.getCoord());
                        Link l = createLinkWithAttributes(network.getFactory(), n1, n2, idPrefix + feature.getAttribute("fid") + "_" + coordCounter, length);
                        network.addLink(l);
                        Link lReversed = copyWithUUIDAndReverseDirection(network.getFactory(), l);
                        network.addLink(lReversed);
                    }
                 	
                	coordCounter++;
                }

            } catch (NullPointerException e) {
            	logger.warn("skipping feature " + feature.getID() ); // TODO
            }
        }

        QuadTree<Node> quadTree = new QuadTree<>(0.0,0.0, 10000000, 1000000000);
        for (Node n: network.getNodes().values()) {
        	boolean isMultiLineStartOrEnd = (boolean) n.getAttributes().getAttribute("multiLineStartOrEnd");
        	if (isMultiLineStartOrEnd == true) {
                quadTree.put(n.getCoord().getX(), n.getCoord().getY(), n);
        	}
        }

        for (Node n: network.getNodes().values()) {
        	boolean isMultiLineStartOrEnd = (boolean) n.getAttributes().getAttribute("multiLineStartOrEnd");
        	if (isMultiLineStartOrEnd == true) {
        		Collection<Node> nodes = new ArrayList<>();
                nodes.add(n);
                Collection<Node> connections = new ArrayList<>();
                connections.addAll(quadTree.getRing(n.getCoord().getX(),n.getCoord().getY(),0.1,maxSearchRadius));
                for (Node n1: connections) {
                    Link l = NetworkUtils.createLink(Id.createLinkId(idPrefix + "connector_" + helpLinkCounter), n, n1, network, NetworkUtils.getEuclideanDistance(n1.getCoord(), n.getCoord()), 0, 0, 0);
                    network.addLink(l);
                    helpLinkCounter++;
                    Link lReversed = copyWithUUIDAndReverseDirection(network.getFactory(), l);
                    network.addLink(lReversed);
                }
                nodes.clear();
        	}
        }

		return network;
	}

    private static Link createLinkWithAttributes(NetworkFactory factory, Node fromNode, Node toNode, Object id, double length) {
        Link result = factory.createLink(
                Id.createLinkId(id.toString()),
                fromNode, toNode
        );
        result.setAllowedModes(new HashSet<>(Collections.singletonList(TransportMode.bike)));
        result.setCapacity(800);
        result.setFreespeed(5.55); // 20km/h
        result.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 1.0); 
        result.setNumberOfLanes(1);
        result.setLength(length);
        return result;
    }

    private static Link copyWithUUIDAndReverseDirection(NetworkFactory factory, Link link) {
        return createLinkWithAttributes(factory, link.getToNode(), link.getFromNode(), link.getId()+"_reversed", link.getLength());
    }
}

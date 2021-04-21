package org.matsim.prepare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

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
	
    public static void main (String[] args) {
    	
    	Map<String,Id<Node>> coordString2nodeId = new HashMap<>();

        String inputShapeNetwork = "/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/data/2021-03-05_radwegeverbindungen_VM_Freizeitnetz/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.shp";
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(inputShapeNetwork);
        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(config);
        Network network = scenario.getNetwork();

        for (SimpleFeature feature: features) {
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            try {
                MultiLineString multiLineString = (MultiLineString) geometry;
                Coordinate[] coordinates = multiLineString.getCoordinates();
                Coord coordFrom = new Coord(coordinates[0].getX(), coordinates[0].getY());
                String coordFromString = coordFrom.getX() + "-" + coordFrom.getY();
                
                Coord coordTo = new Coord(coordinates[coordinates.length-1].getX(), coordinates[coordinates.length-1].getY());
                String coordToString = coordTo.getX() + "-" + coordTo.getY();
   
                Node n1 = null;
                if (coordString2nodeId.get(coordFromString) == null) {
                    String fromId = feature.getAttribute("fid") + "_0";
					n1 = NetworkUtils.createNode(Id.createNodeId(fromId), coordFrom );
                    coordString2nodeId.put(coordFromString, n1.getId());
                } else {
                	n1 = network.getNodes().get(coordString2nodeId.get(coordFromString));
                }
                Node n2 = null;
                if (coordString2nodeId.get(coordToString) == null) {
                    String toId = feature.getAttribute("fid") + "_1";
                    n2 = NetworkUtils.createNode(Id.createNodeId(toId), coordTo );
                    coordString2nodeId.put(coordToString, n2.getId());
                } else {
                	n2 = network.getNodes().get(coordString2nodeId.get(coordToString));
                }
                
                Link l = NetworkUtils.createLink(Id.createLinkId(""+n1.toString()+n2.toString()), n1, n2, network, NetworkUtils.getEuclideanDistance(n1.getCoord(), n2.getCoord()), 0, 0, 0);
                network.addNode(n1);
                network.addNode(n2);
                network.addLink(l);

            } catch (NullPointerException e) {
            	logger.warn("skipping feature " + feature.getID() ); // TODO
//                throw new RuntimeException(e.toString());
            }
        }

//        SearchableNetwork searchableNetwork = (SearchableNetwork) network;
        QuadTree<Node> quadTree = new QuadTree<>(0.0,0.0, 10000000, 1000000000);
        for (Node n: network.getNodes().values()) {
            quadTree.put(n.getCoord().getX(), n.getCoord().getY(), n);
        }


        for (Node n: network.getNodes().values()) {
            Collection<Node> nodes = new ArrayList<>();
            if (n.getInLinks().isEmpty()) {
//                QuadTree.Rect bounds = new QuadTree.Rect(n.getCoord().getX()+10,n.getCoord().getY()+10,n.getCoord().getX()+100,+n.getCoord().getY()+100);
                nodes.add(n);
                Collection<Node> connections = new ArrayList<>();
                //connections.addAll(quadTree.getRectangle(bounds, nodes));
                connections.addAll(quadTree.getRing(n.getCoord().getX(),n.getCoord().getY(),0.1,10));
                for (Node n1: connections) {

                    Link l = NetworkUtils.createLink(Id.createLinkId("2"+n.getId()+Math.random()), n, n1, network, NetworkUtils.getEuclideanDistance(n1.getCoord(), n.getCoord()), 0, 0, 0);
                    network.addLink(l);
                }

                nodes.clear();
            }
        }


        NetworkWriter writer = new NetworkWriter(network);
        writer.write("/Users/ihab/Documents/workspace/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/bicycle-infrastructure/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.xml.gz");
    }

//    private static void connectNodeToNetwork(Network network, Node node) {
//        // search for possible connections
//        Collection<Node> nodes = getNearestNodes(network, node);
//        nodes.stream()
//                .sorted((node1, node2) -> {
//                    Double dist1 = NetworkUtils.getEuclideanDistance(node1.getCoord(), node.getCoord());
//                    Double dist2 = NetworkUtils.getEuclideanDistance(node2.getCoord(), node.getCoord());
//                    return dist1.compareTo(dist2);
//                })
//                .limit(1)
//                .forEach(nearNode -> {
//                    network.addLink(createLinkWithAttributes(network.getFactory(), node, nearNode));
//                    network.addLink(createLinkWithAttributes(network.getFactory(), nearNode, node));
//                });
//    }

//    private static Collection<Node> getNearestNodes(Network network, Node node) {
//
//        final double distance = 100; // search nodes in a 100m radius
//        Collection<Node> nodes = NetworkUtils.getNearestNodes(network, node.getCoord(), distance);
//        // to avoid being nearest node to be the same as the node
//
//        return nodes;
//    }

    private static Link createLinkWithAttributes(NetworkFactory factory, Node fromNode, Node toNode) {

        Link result = factory.createLink(
                Id.createLinkId("bike-highway_" + UUID.randomUUID().toString()),
                fromNode, toNode
        );
        result.setAllowedModes(new HashSet<>(Collections.singletonList(TransportMode.bike)));
        result.setCapacity(10000); // set to pretty much unlimited
        result.setFreespeed(8.3); // 30km/h
        result.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 1.0); // bikes can reach their max velocity on bike highways
        result.setNumberOfLanes(1);
        result.setLength(NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord()));
        return result;
    }

}

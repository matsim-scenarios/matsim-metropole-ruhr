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
    private static final String inputShapeNetwork = "/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/data/2021-03-05_radwegeverbindungen_VM_Freizeitnetz/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.shp";
    private static final String matSimOutputNetwork ="/Users/ihab/Documents/workspace/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/bicycle-infrastructure/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.xml.gz";

    public static void main (String[] args) {
    	
    	Map<String,Id<Node>> coordString2nodeId = new HashMap<>();
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

                Node n1;
                if (coordString2nodeId.get(coordFromString) == null) {
                    String fromId = feature.getAttribute("fid") + "_0";
					n1 = NetworkUtils.createNode(Id.createNodeId(fromId), coordFrom );
                    coordString2nodeId.put(coordFromString, n1.getId());
                    network.addNode(n1);
                } else {
                	n1 = network.getNodes().get(coordString2nodeId.get(coordFromString));
                }
                Node n2;
                if (coordString2nodeId.get(coordToString) == null) {
                    String toId = feature.getAttribute("fid") + "_1";
                    n2 = NetworkUtils.createNode(Id.createNodeId(toId), coordTo );
                    coordString2nodeId.put(coordToString, n2.getId());
                    network.addNode(n2);
                } else {
                	n2 = network.getNodes().get(coordString2nodeId.get(coordToString));
                }

                double length = (double) feature.getAttribute("st_length_");
                Link l = createLinkWithAttributes(network.getFactory(), n1, n2, feature.getAttribute("fid"), length);
                network.addLink(l);
                Link lReversed = copyWithUUIDAndReverseDirection(network.getFactory(), l);
                network.addLink(lReversed);

            } catch (NullPointerException e) {
            	logger.warn("skipping feature " + feature.getID() ); // TODO
            }
        }

        QuadTree<Node> quadTree = new QuadTree<>(0.0,0.0, 10000000, 1000000000);
        for (Node n: network.getNodes().values()) {
            quadTree.put(n.getCoord().getX(), n.getCoord().getY(), n);
        }

        for (Node n: network.getNodes().values()) {
            Collection<Node> nodes = new ArrayList<>();
            if (n.getInLinks().isEmpty()) {
                nodes.add(n);
                Collection<Node> connections = new ArrayList<>();
                connections.addAll(quadTree.getRing(n.getCoord().getX(),n.getCoord().getY(),0.1,10));
                for (Node n1: connections) {
                    Link l = NetworkUtils.createLink(Id.createLinkId("2"+n.getId()+Math.random()), n, n1, network, NetworkUtils.getEuclideanDistance(n1.getCoord(), n.getCoord()), 0, 0, 0);
                    network.addLink(l);
                    Link lReversed = copyWithUUIDAndReverseDirection(network.getFactory(), l);
                    network.addLink(lReversed);
                }
                nodes.clear();
            }
        }

        NetworkWriter writer = new NetworkWriter(network);
        writer.write(matSimOutputNetwork);
    }


    private static Link createLinkWithAttributes(NetworkFactory factory, Node fromNode, Node toNode, Object id, double length) {
        Link result = factory.createLink(
                Id.createLinkId(id.toString()),
                fromNode, toNode
        );
        result.setAllowedModes(new HashSet<>(Collections.singletonList(TransportMode.bike)));
        result.setCapacity(10000); // set to pretty much unlimited
        result.setFreespeed(8.3); // 30km/h
        result.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 1.0); // bikes can reach their max velocity on bike highways
        result.setNumberOfLanes(1);
        result.setLength(length);
        return result;
    }


    private static Link copyWithUUIDAndReverseDirection(NetworkFactory factory, Link link) {
        return createLinkWithAttributes(factory, link.getToNode(), link.getFromNode(), link.getId()+"_reversed", link.getLength());
    }
}

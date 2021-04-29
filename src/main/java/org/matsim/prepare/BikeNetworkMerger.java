package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.core.network.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

class BikeNetworkMerger {

	private static final double MAX_LINK_LENGTH = 1000.;
	private static final double searchRadius = 10; // search nodes within this radius
	private static final String ID_PREFIX = "bike_";
	private static Logger logger = LoggerFactory.getLogger(BikeNetworkMerger.class);
	private final Network originalNetwork;
	private final List<Link> brokenUpLinksToAdd = new ArrayList<>();
	private final List<Link> longLinksToRemove = new ArrayList<>();

	BikeNetworkMerger(Network originalNetwork) {
		this.originalNetwork = originalNetwork;
	}

	Network mergeBikeHighways(Network bikeNetwork) {

		// break up links into parts < max link length
		this.breakLinksIntoSmallerPieces(bikeNetwork);
		this.copyNodesIntoNetwork(bikeNetwork);

		bikeNetwork.getLinks().values().forEach(link -> {
			// add link copy to original network
			originalNetwork.addLink(copyLink(originalNetwork.getFactory(), link));
		});
		
		bikeNetwork.getNodes().values().forEach(node -> {
			connectNodeToNetwork(originalNetwork, bikeNetwork.getNodes(), node);
		});

		return originalNetwork;
	}

	private void breakLinksIntoSmallerPieces(Network bikeHighways) {

		bikeHighways.getLinks().values().forEach(link -> breakUpLinkIntoSmallerPieces(bikeHighways, link));
		this.longLinksToRemove.forEach(link -> bikeHighways.removeLink(link.getId()));
		this.brokenUpLinksToAdd.forEach(bikeHighways::addLink);
	}

	private void breakUpLinkIntoSmallerPieces(Network bikeNetwork, Link link) {

		double length = NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());

		if (length > MAX_LINK_LENGTH) {

			longLinksToRemove.add(link);
			Node fromNode = link.getFromNode();
			Node toNode = link.getToNode();
			double numberOfParts = Math.ceil(length / (int) MAX_LINK_LENGTH);
			double partLength = length / numberOfParts;
			double lengthFraction = partLength / length;
			double deltaX = toNode.getCoord().getX() - fromNode.getCoord().getX();
			double deltaY = toNode.getCoord().getY() - fromNode.getCoord().getY();
			Node currentNode = fromNode;

			logger.info("link length: " + length);
			logger.info("splitting link into " + numberOfParts + " parts");

			while (numberOfParts > 1) {

				// calculate new coordinate and add a node to the network
				Coord newCoord = new Coord(
						currentNode.getCoord().getX() + deltaX * lengthFraction,
						currentNode.getCoord().getY() + deltaY * lengthFraction
				);
				Node newNode = bikeNetwork.getFactory().createNode(
						Id.createNodeId(ID_PREFIX + UUID.randomUUID().toString()), newCoord
				);
				bikeNetwork.addNode(newNode);
				logger.info("added node with id: " + newNode.getId().toString());

				// connect current and new node with a link and add it to the network
				Link newLink = createLinkWithAttributes(bikeNetwork.getFactory(), currentNode, newNode);
				brokenUpLinksToAdd.add(newLink);

				// wrap up for next iteration
				currentNode = newNode;
				numberOfParts--;
			}

			// last link to be inserted must be connected to currentNode and toNode
			Link lastLink = createLinkWithAttributes(bikeNetwork.getFactory(), currentNode, toNode);
			
			brokenUpLinksToAdd.add(lastLink);
		}
	}

	private void copyNodesIntoNetwork(Network fromNetwork) {
		fromNetwork.getNodes().values().forEach(originalNetwork::addNode);
	}

	private Link copyLink(NetworkFactory factory, Link link) {
		Link result = factory.createLink(
				Id.createLinkId(link.getId()),
				link.getFromNode(), link.getToNode()
		);
		result.setAllowedModes(link.getAllowedModes());
		result.setCapacity(link.getCapacity()); 
		result.setFreespeed(link.getFreespeed());
		result.setNumberOfLanes(link.getNumberOfLanes());
		result.setLength(link.getLength());
		for (String attribute : link.getAttributes().getAsMap().keySet()) {
			result.getAttributes().putAttribute(attribute, link.getAttributes().getAttribute(attribute));
		}		
		return result;		
	}

	private Link createLinkWithAttributes(NetworkFactory factory, Node fromNode, Node toNode) {

		Link result = factory.createLink(
				Id.createLinkId(ID_PREFIX + UUID.randomUUID().toString()),
				fromNode, toNode
		);
		result.setAllowedModes(new HashSet<>(Collections.singletonList(TransportMode.bike)));
		result.setCapacity(800); 
		result.setFreespeed(5.55);
		result.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 1.0);
		result.setNumberOfLanes(1);
		result.setLength(NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord()));
		return result;
	}

	private void connectNodeToNetwork(Network network, Map<Id<Node>, ? extends Node> nodesToAvoid, Node node) {

		// search for possible connections
		Collection<Node> nodes = getNearestNodes(network, node);
		nodes.stream()
				.filter(nearNode -> !nodesToAvoid.containsKey(nearNode.getId()))
				.sorted((node1, node2) -> {
					Double dist1 = NetworkUtils.getEuclideanDistance(node1.getCoord(), node.getCoord());
					Double dist2 = NetworkUtils.getEuclideanDistance(node2.getCoord(), node.getCoord());
					return dist1.compareTo(dist2);
				})
				.limit(1)
				.forEach(nearNode -> {
					network.addLink(createLinkWithAttributes(network.getFactory(), node, nearNode));
					network.addLink(createLinkWithAttributes(network.getFactory(), nearNode, node));
				});
	}

	private Collection<Node> getNearestNodes(Network network, Node node) {
		return NetworkUtils.getNearestNodes(network, node.getCoord(), searchRadius).stream()
				.filter(n -> !n.getId().toString().startsWith("pt")).collect(Collectors.toList());
	}
}
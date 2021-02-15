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

	private static final double MAX_LINK_LENGTH = 200;
	private static final String ID_PREFIX = "bike-highway_";
	private static Logger logger = LoggerFactory.getLogger(BikeNetworkMerger.class);
	private final Network originalNetwork;
	private final List<Link> brokenUpLinksToAdd = new ArrayList<>();
	private final List<Link> longLinksToRemove = new ArrayList<>();

	BikeNetworkMerger(Network originalNetwork) {
		this.originalNetwork = originalNetwork;
	}

	Network mergeBikeHighways(Network bikeHighways) {

		// break up links into parts < 200m
		this.breakLinksIntoSmallerPieces(bikeHighways);
		this.copyNodesIntoNetwork(bikeHighways);

		bikeHighways.getLinks().values().forEach(link -> {

			// copy link and give it some id
			Link newLink = copyWithUUID(originalNetwork.getFactory(), link);
			Link newReverseLink = copyWithUUIDAndReverseDirection(originalNetwork.getFactory(), link);

			connectNodeToNetwork(originalNetwork, bikeHighways.getNodes(), newLink.getFromNode());
			connectNodeToNetwork(originalNetwork, bikeHighways.getNodes(), newLink.getToNode());

			// add new links to original network
			originalNetwork.addLink(newLink);
			originalNetwork.addLink(newReverseLink);
		});

		return originalNetwork;
	}

	private void breakLinksIntoSmallerPieces(Network bikeHighways) {

		bikeHighways.getLinks().values().forEach(link -> breakUpLinkIntoSmallerPieces(bikeHighways, link));
		this.longLinksToRemove.forEach(link -> bikeHighways.removeLink(link.getId()));
		this.brokenUpLinksToAdd.forEach(bikeHighways::addLink);
	}

	private void breakUpLinkIntoSmallerPieces(Network bikeHighways, Link link) {

		double length = NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());

		if (length > MAX_LINK_LENGTH) {

			longLinksToRemove.add(link);
			Node fromNode = link.getFromNode();
			Node toNode = link.getToNode();
			double numberOfParts = Math.ceil(length / 200);
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
				Node newNode = bikeHighways.getFactory().createNode(
						Id.createNodeId(ID_PREFIX + UUID.randomUUID().toString()), newCoord
				);
				bikeHighways.addNode(newNode);
				logger.info("added node with id: " + newNode.getId().toString());

				// connect current and new node with a link and add it to the network
				Link newLink = bikeHighways.getFactory().createLink(
						Id.createLinkId(ID_PREFIX + UUID.randomUUID().toString()),
						currentNode, newNode
				);
				brokenUpLinksToAdd.add(newLink);

				// wrap up for next iteration
				currentNode = newNode;
				numberOfParts--;
			}

			// last link to be inserted must be connected to currentNode and toNode
			Link lastLink = bikeHighways.getFactory().createLink(
					Id.createLinkId(ID_PREFIX + UUID.randomUUID().toString()),
					currentNode, toNode
			);
			brokenUpLinksToAdd.add(lastLink);
		}
	}

	private void copyNodesIntoNetwork(Network fromNetwork) {
		fromNetwork.getNodes().values().forEach(originalNetwork::addNode);
	}

	private Link copyWithUUID(NetworkFactory factory, Link link) {
		return createLinkWithAttributes(factory, link.getFromNode(), link.getToNode());
	}

	private Link copyWithUUIDAndReverseDirection(NetworkFactory factory, Link link) {
		return createLinkWithAttributes(factory, link.getToNode(), link.getFromNode());
	}

	private Link createLinkWithAttributes(NetworkFactory factory, Node fromNode, Node toNode) {

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

		final double distance = 100; // search nodes in a 100m radius
		return NetworkUtils.getNearestNodes(network, node.getCoord(), distance).stream()
				.filter(n -> !n.getId().toString().startsWith("pt")).collect(Collectors.toList());
	}
}
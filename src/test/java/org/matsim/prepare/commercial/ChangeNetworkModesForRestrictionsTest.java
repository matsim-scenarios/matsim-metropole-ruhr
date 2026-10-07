package org.matsim.prepare.commercial;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the geometric matching used to map restriction lines onto network links.
 */
class ChangeNetworkModesForRestrictionsTest {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
	private static final Set<String> TRUCK_MODES = Set.of("truck8t", "truck18t", "truck26t", "truck40t");

	@Test
	void matchesParallelLinkWhenCrossingLinkIsCloserAtMidpoint() {
		Network network = NetworkUtils.createNetwork();
		Link parallel = addLink(network, "parallel", new Coord(0, 0), new Coord(100, 0));
		Link crossing = addLink(network, "crossing", new Coord(50, -50), new Coord(50, 50));

		applyNoTruckRestriction(network, line(new Coordinate(0, 2), new Coordinate(100, 2)));

		assertNoTruckModes(parallel);
		assertTruckModes(crossing);
		assertEquals("No truck > 3,5t allowed", parallel.getAttributes().getAttribute("reasonOfRestriction"));
		assertNull(crossing.getAttributes().getAttribute("reasonOfRestriction"));
	}

	@Test
	void doesNotMapCrossingLinkFromShapeVertex() {
		Network network = NetworkUtils.createNetwork();
		Link restricted = addLink(network, "restricted", new Coord(0, 0), new Coord(100, 0));
		Link crossing = addLink(network, "crossing", new Coord(50, -50), new Coord(50, 50));

		applyNoTruckRestriction(network, line(new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(100, 0)));

		assertNoTruckModes(restricted);
		assertTruckModes(crossing);
		assertNull(crossing.getAttributes().getAttribute("reasonOfRestriction"));
	}

	@Test
	void processesAllPartsOfMultiLineString() {
		Network network = NetworkUtils.createNetwork();
		Link first = addLink(network, "first", new Coord(0, 0), new Coord(100, 0));
		Link second = addLink(network, "second", new Coord(200, 0), new Coord(300, 0));

		Geometry restriction = GEOMETRY_FACTORY.createMultiLineString(new LineString[]{
			line(new Coordinate(0, 2), new Coordinate(100, 2)),
			line(new Coordinate(200, 2), new Coordinate(300, 2))
		});

		applyNoTruckRestriction(network, restriction);

		assertNoTruckModes(first);
		assertNoTruckModes(second);
	}

	@Test
	void ignoresInvalidWeightRestrictionValue() {
		Network network = NetworkUtils.createNetwork();
		Link link = addLink(network, "link", new Coord(0, 0), new Coord(100, 0));

		var rule = ChangeNetworkModesForRestrictions.createRestrictionRule("262", "not-a-number");
		rule.ifPresent(restrictionRule -> ChangeNetworkModesForRestrictions.applyRestrictionToGeometry(
			network,
			line(new Coordinate(0, 0), new Coordinate(100, 0)),
			restrictionRule,
			new IdentityTransformation(),
			new HashMap<>()
		));

		assertTrue(rule.isEmpty());
		assertTruckModes(link);
		assertNull(link.getAttributes().getAttribute("reasonOfRestriction"));
	}

	@Test
	void doesNotChangeOppositeLinkOutsideMatchingNetwork() {
		Network fullNetwork = NetworkUtils.createNetwork();
		Link forward = addLink(fullNetwork, "forward", "a", new Coord(0, 0), "b", new Coord(100, 0));
		Link reverse = addLink(fullNetwork, "reverse", "b", new Coord(100, 0), "a", new Coord(0, 0));

		Network matchingNetwork = NetworkUtils.createNetwork();
		fullNetwork.getNodes().values().forEach(matchingNetwork::addNode);
		matchingNetwork.addLink(forward);

		applyNoTruckRestriction(matchingNetwork, line(new Coordinate(0, 0), new Coordinate(100, 0)));

		assertNoTruckModes(forward);
		assertTruckModes(reverse);
		assertNull(reverse.getAttributes().getAttribute("reasonOfRestriction"));
	}

	private static void applyNoTruckRestriction(Network network, Geometry restriction) {
		var rule = ChangeNetworkModesForRestrictions.createRestrictionRule("253", null).orElseThrow();
		Map<String, Set<Id<Link>>> adjustedLinkIdsByReason = new HashMap<>();
		ChangeNetworkModesForRestrictions.applyRestrictionToGeometry(
			network,
			restriction,
			rule,
			new IdentityTransformation(),
			adjustedLinkIdsByReason
		);
	}

	private static Link addLink(Network network, String id, Coord from, Coord to) {
		return addLink(network, id, id + "-from", from, id + "-to", to);
	}

	private static Link addLink(Network network, String id, String fromNodeId, Coord from, String toNodeId, Coord to) {
		NetworkFactory factory = network.getFactory();
		Node fromNode = getOrCreateNode(network, factory, fromNodeId, from);
		Node toNode = getOrCreateNode(network, factory, toNodeId, to);
		Link link = factory.createLink(Id.createLinkId(id), fromNode, toNode);
		link.setLength(NetworkUtils.getEuclideanDistance(from, to));
		link.setAllowedModes(Set.of("car", "truck8t", "truck18t", "truck26t", "truck40t"));
		network.addLink(link);
		return link;
	}

	private static Node getOrCreateNode(Network network, NetworkFactory factory, String id, Coord coord) {
		Id<Node> nodeId = Id.createNodeId(id);
		Node node = network.getNodes().get(nodeId);
		if (node == null) {
			node = factory.createNode(nodeId, coord);
			network.addNode(node);
		}
		return node;
	}

	private static LineString line(Coordinate... coordinates) {
		return GEOMETRY_FACTORY.createLineString(coordinates);
	}

	private static void assertNoTruckModes(Link link) {
		for (String mode : TRUCK_MODES) {
			assertFalse(link.getAllowedModes().contains(mode));
		}
	}

	private static void assertTruckModes(Link link) {
		assertTrue(link.getAllowedModes().containsAll(TRUCK_MODES));
	}
}

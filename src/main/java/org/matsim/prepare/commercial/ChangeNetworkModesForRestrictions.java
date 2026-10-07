package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.GeometryUtils;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class reads in a network and a shapefile containing restrictions for different network modes (e.g. car, truck8t, truck18t, truck26t, truck40t).
 *     As a result, the network is updated, so that the links, which are restricted for certain vehicle categories, are not allowed to be used by these vehicles.
 */
public class ChangeNetworkModesForRestrictions {

	private static final Logger log = LogManager.getLogger(ChangeNetworkModesForRestrictions.class);
	private static final String DEFAULT_NETWORK_CRS = "EPSG:25832";
	private static final double MIN_RESTRICTION_SEGMENT_LENGTH = 5.0;
	private static final double CANDIDATE_SEARCH_RADIUS = 25.0;
	private static final double MAX_SEGMENT_TO_LINK_DISTANCE = 20.0;
	private static final double MIN_DIRECTION_SIMILARITY = 0.70;
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
	private static final Set<String> TRUCK_MODES = Set.of("truck8t", "truck18t", "truck26t", "truck40t");
	private static final Set<String> TRUCK_MODES2 = Set.of("car", "ride", "bike", "truck40t", "truck8t", "freight", "truck18t", "truck26t");


	private static final String ruhrNetworkPath = "C:\\Users\\erica\\shared\\public-svn\\matsim\\scenarios\\countries\\de\\metropole-ruhr\\metropole-ruhr-v2024\\metropole-ruhr-v2024.2\\input\\metropole-ruhr-v2024.2.network_resolutionHigh-with-pt.xml.gz";
    private static final String restrictionPath = "C:\\Users\\erica\\shared\\shared-svn\\projects\\rvr-metropole-ruhr\\data\\commercialTraffic\\Restriktionen_RVR_20231121\\Restriktionen_RVR_20231121.shp";
    private static final String outputPath = "C:\\Users\\erica\\shared\\public-svn\\matsim\\scenarios\\countries\\de\\metropole-ruhr\\metropole-ruhr-v2024\\metropole-ruhr-v2024.2\\input\\metropole-ruhr-v2024.2.network_resolutionHigh-with-pt-with-Restrictions2.xml.gz";

	public static void main(String[] args){
		//Read in shp and xml files
		Network network = NetworkUtils.readNetwork(ruhrNetworkPath);
		ShpOptions shp = new ShpOptions(Path.of(restrictionPath), null, null);
		Network networkNoPt = createCarNetwork(network);

		addTruckModesToCarLinks(network);

		CoordinateTransformation shapeToNetwork = createShapeToNetworkTransformation(shp, network);
		Map<String, Set<Id<Link>>> adjustedLinkIdsByReason = new HashMap<>();
		LinkGeometryIndex linkIndex = new LinkGeometryIndex(networkNoPt.getLinks().values());

		for(SimpleFeature feature : shp.readFeatures()){
			Optional<RestrictionRule> rule = createRestrictionRule(feature.getAttribute("typ"), feature.getAttribute("wert"));
			if (rule.isEmpty()) {
				continue;
			}

			applyRestrictionToGeometry(networkNoPt, linkIndex, (Geometry) feature.getDefaultGeometry(), rule.get(), shapeToNetwork, adjustedLinkIdsByReason);
		}
		AtomicInteger countLinksPt = new AtomicInteger();
		adjustedLinkIdsByReason.forEach((reason, linkIds) -> log.info("Restriction {}: relevant and integrated for {} links", reason, linkIds.size()));
		network.getLinks().values().forEach(link -> {
			if (link.getAllowedModes().contains("pt")) {
				countLinksPt.getAndIncrement();
			}
		});
		log.info("Number of links with pt before clean up: {}", countLinksPt.get());
		NetworkUtils.writeNetwork(network, outputPath);
		countLinksPt.set(0);
//		NetworkUtils.cleanNetwork(network, NetworkUtils.getModes(network));
		NetworkUtils.cleanNetwork(network, TRUCK_MODES2);
		network.getLinks().values().forEach(link -> {
			if (link.getAllowedModes().contains("pt")) {
				countLinksPt.getAndIncrement();
			}
		});
		log.info("Number of links with pt after clean up: {}", countLinksPt.get());
		NetworkUtils.writeNetwork(network, outputPath.replace(".xml.gz", "_clean.xml.gz"));
//		NetworkUtils.writeNetwork(network, outputPath);

	}

	private static Network createCarNetwork(Network network) {
		Network networkNoPt = NetworkUtils.createNetwork();

		//Create copy of network which only contains links, where car/freight-traffic is allowed (not pt, no bike-only-links, ...)
		for(Node node : network.getNodes().values()){
			if(!node.getId().toString().startsWith("pt") && !node.getId().toString().startsWith("bike")){
				networkNoPt.addNode(node);
			}
		}
		for(Link link : network.getLinks().values()){
			NetworkUtils.removeAllowedMode(link, "freight");
			if(link.getAllowedModes().contains("car") || link.getAllowedModes().contains("freight")){
				networkNoPt.addLink(link);
			}
		}

		String crs = ProjectionUtils.getCRS(network);
		if (crs != null) {
			ProjectionUtils.putCRS(networkNoPt, crs);
		}

		return networkNoPt;
	}

	private static void addTruckModesToCarLinks(Network network) {
		Collection<? extends Link> links = network.getLinks().values();
		for (Link link : links){
			if(link.getAllowedModes().contains("car")){
				Set<String> combined = Stream.concat(link.getAllowedModes().stream(), TRUCK_MODES.stream()).collect(Collectors.toSet());
				link.setAllowedModes(combined);
			}
		}
	}

	private static CoordinateTransformation createShapeToNetworkTransformation(ShpOptions shp, Network network) {
		String networkCrs = ProjectionUtils.getCRS(network);
		if (networkCrs == null || networkCrs.isBlank()) {
			networkCrs = DEFAULT_NETWORK_CRS;
			log.warn("Network CRS is not set; assuming {}", networkCrs);
		}

		String shapeCrs;
		try {
			shapeCrs = shp.getShapeCrs();
		} catch (RuntimeException e) {
			shapeCrs = DEFAULT_NETWORK_CRS;
			log.warn("Could not determine restriction shape CRS; assuming {}. Cause: {}", shapeCrs, e.getMessage());
		}

		if (shapeCrs.equalsIgnoreCase(networkCrs)) {
			return new IdentityTransformation();
		}
		return TransformationFactory.getCoordinateTransformation(shapeCrs, networkCrs);
	}

	static Set<Id<Link>> applyRestrictionToGeometry(Network matchingNetwork, Geometry restrictionGeometry, RestrictionRule rule,
													CoordinateTransformation shapeToNetworkTransformation,
													Map<String, Set<Id<Link>>> adjustedLinkIdsByReason) {
		return applyRestrictionToGeometry(matchingNetwork, new LinkGeometryIndex(matchingNetwork.getLinks().values()), restrictionGeometry,
			rule, shapeToNetworkTransformation, adjustedLinkIdsByReason);
	}

	private static Set<Id<Link>> applyRestrictionToGeometry(Network matchingNetwork, LinkGeometryIndex linkIndex, Geometry restrictionGeometry,
															RestrictionRule rule, CoordinateTransformation shapeToNetworkTransformation,
															Map<String, Set<Id<Link>>> adjustedLinkIdsByReason) {
		Set<Id<Link>> adjustedLinkIds = new HashSet<>();
		if (restrictionGeometry == null) {
			return adjustedLinkIds;
		}

		for (LineString lineString : collectLineStrings(restrictionGeometry)) {
			Coordinate[] coordinates = transformCoordinates(lineString.getCoordinates(), shapeToNetworkTransformation);
			for (int j = 1; j < coordinates.length; j++) {
				LineString segment = GEOMETRY_FACTORY.createLineString(new Coordinate[]{coordinates[j - 1], coordinates[j]});
				if (segment.getLength() < MIN_RESTRICTION_SEGMENT_LENGTH) {
					continue;
				}

				Optional<MatchedLink> matchedLink = findBestMatchingLink(linkIndex, segment);
				if (matchedLink.isEmpty()) {
					continue;
				}

				Link link = matchedLink.get().link();
				applyRestriction(link, rule, adjustedLinkIdsByReason, adjustedLinkIds);

				Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(link);
				if (oppositeLink != null && matchingNetwork.getLinks().containsKey(oppositeLink.getId()) && matchesSegment(oppositeLink, segment)) {
					applyRestriction(oppositeLink, rule, adjustedLinkIdsByReason, adjustedLinkIds);
				}
			}
		}

		return adjustedLinkIds;
	}

	static Optional<RestrictionRule> createRestrictionRule(Object typeAttribute, Object valueAttribute) {
		if (typeAttribute == null) {
			return Optional.empty();
		}

		String type = typeAttribute.toString();
		OptionalDouble value = parseRestrictionValue(valueAttribute);
		return switch (type) {
			case "253" -> Optional.of(new RestrictionRule("No truck > 3,5t allowed", (Set<String>) TRUCK_MODES));
			case "262" -> createWeightRestrictionRule(value);
			case "264" -> value.isPresent() && value.getAsDouble() <= 4
				? Optional.of(new RestrictionRule("HeightRestriction < 4m", (Set<String>) TRUCK_MODES))
				: Optional.empty();
			case "265" -> value.isPresent() && value.getAsDouble() <= 2.5
				? Optional.of(new RestrictionRule("WidthRestriction < 2.5m", (Set<String>) TRUCK_MODES))
				: Optional.empty();
			case "266" -> value.isPresent() && value.getAsDouble() <= 10
				? Optional.of(new RestrictionRule("LengthRestriction < 10m", Set.of("truck18t", "truck26t", "truck40t")))
				: Optional.empty();
			default -> Optional.empty();
		};
	}

	static OptionalDouble parseRestrictionValue(Object valueAttribute) {
		if (valueAttribute == null) {
			return OptionalDouble.empty();
		}

		String value = valueAttribute.toString().trim().replace(",", ".");
		if (value.isBlank()) {
			return OptionalDouble.empty();
		}

		try {
			return OptionalDouble.of(Double.parseDouble(value));
		} catch (NumberFormatException e) {
			return OptionalDouble.empty();
		}
	}

	private static Optional<RestrictionRule> createWeightRestrictionRule(OptionalDouble value) {
		if (value.isEmpty()) {
			return Optional.empty();
		}

		double tons = value.getAsDouble();
		if (tons < 8) {
			return Optional.of(new RestrictionRule("WeightRestriction < " + tons + "t", (Set<String>) TRUCK_MODES));
		} else if (tons < 18) {
			return Optional.of(new RestrictionRule("WeightRestriction < " + tons + "t", Set.of("truck18t", "truck26t", "truck40t")));
		} else if (tons < 26) {
			return Optional.of(new RestrictionRule("WeightRestriction < " + tons + "t", Set.of("truck26t", "truck40t")));
		} else if (tons < 40) {
			return Optional.of(new RestrictionRule("WeightRestriction < " + tons + "t", Set.of("truck40t")));
		}
		return Optional.empty();
	}

	private static List<LineString> collectLineStrings(Geometry geometry) {
		List<LineString> lineStrings = new ArrayList<>();
		collectLineStrings(geometry, lineStrings);
		return lineStrings;
	}

	private static void collectLineStrings(Geometry geometry, List<LineString> lineStrings) {
		if (geometry instanceof LineString lineString) {
			lineStrings.add(lineString);
		} else if (geometry instanceof MultiLineString multiLineString) {
			for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
				collectLineStrings(multiLineString.getGeometryN(i), lineStrings);
			}
		} else if (geometry instanceof GeometryCollection geometryCollection) {
			for (int i = 0; i < geometryCollection.getNumGeometries(); i++) {
				collectLineStrings(geometryCollection.getGeometryN(i), lineStrings);
			}
		} else {
			log.warn("Skipping unsupported restriction geometry type {}", geometry.getGeometryType());
		}
	}

	private static Coordinate[] transformCoordinates(Coordinate[] coordinates, CoordinateTransformation transformation) {
		Coordinate[] transformed = new Coordinate[coordinates.length];
		for (int i = 0; i < coordinates.length; i++) {
			Coord coord = transformation.transform(new Coord(coordinates[i].x, coordinates[i].y));
			transformed[i] = new Coordinate(coord.getX(), coord.getY());
		}
		return transformed;
	}

	private static Optional<MatchedLink> findBestMatchingLink(LinkGeometryIndex linkIndex, LineString segment) {
		Coord midpoint = midpoint(segment);
		return linkIndex.query(midpoint, CANDIDATE_SEARCH_RADIUS).stream()
			.map(link -> matchLink(link, segment))
			.flatMap(Optional::stream)
			.min(ChangeNetworkModesForRestrictions::compareMatches);
	}

	private static boolean matchesSegment(Link link, LineString segment) {
		return matchLink(link, segment).isPresent();
	}

	private static Optional<MatchedLink> matchLink(Link link, LineString segment) {
		LineString linkGeometry = GeometryUtils.createGeotoolsLineString(link);
		double distance = segment.distance(linkGeometry);
		if (distance > MAX_SEGMENT_TO_LINK_DISTANCE) {
			return Optional.empty();
		}

		double directionSimilarity = directionSimilarity(link, segment);
		if (directionSimilarity < MIN_DIRECTION_SIMILARITY) {
			return Optional.empty();
		}

		return Optional.of(new MatchedLink(link, distance, directionSimilarity));
	}

	private static int compareMatches(MatchedLink first, MatchedLink second) {
		int distanceComparison = Double.compare(first.distance(), second.distance());
		if (distanceComparison != 0) {
			return distanceComparison;
		}
		return -Double.compare(first.directionSimilarity(), second.directionSimilarity());
	}

	private static Coord midpoint(LineString segment) {
		Coordinate from = segment.getCoordinateN(0);
		Coordinate to = segment.getCoordinateN(1);
		return new Coord((from.x + to.x) / 2, (from.y + to.y) / 2);
	}

	private static double directionSimilarity(Link link, LineString segment) {
		Coordinate segmentFrom = segment.getCoordinateN(0);
		Coordinate segmentTo = segment.getCoordinateN(1);
		double segmentX = segmentTo.x - segmentFrom.x;
		double segmentY = segmentTo.y - segmentFrom.y;
		double linkX = link.getToNode().getCoord().getX() - link.getFromNode().getCoord().getX();
		double linkY = link.getToNode().getCoord().getY() - link.getFromNode().getCoord().getY();
		double segmentLength = Math.hypot(segmentX, segmentY);
		double linkLength = Math.hypot(linkX, linkY);

		if (segmentLength == 0 || linkLength == 0) {
			return 0;
		}

		return Math.abs(((segmentX * linkX) + (segmentY * linkY)) / (segmentLength * linkLength));
	}

	private static void applyRestriction(Link link, RestrictionRule rule, Map<String, Set<Id<Link>>> adjustedLinkIdsByReason,
										 Set<Id<Link>> adjustedLinkIds) {
		Set<String> allowedModes = new HashSet<>(link.getAllowedModes());
		boolean modesChanged = allowedModes.removeAll(rule.disallowedModes());
		boolean reasonChanged = addRestrictionReason(link, rule.reason());

		if (modesChanged) {
			link.setAllowedModes(allowedModes);
		}

		if (modesChanged || reasonChanged) {
			adjustedLinkIds.add(link.getId());
			adjustedLinkIdsByReason.computeIfAbsent(rule.reason(), k -> new HashSet<>()).add(link.getId());
		}
	}

	private static boolean addRestrictionReason(Link link, String reason) {
		Object existing = link.getAttributes().getAttribute("reasonOfRestriction");
		if (existing == null || existing.toString().isBlank()) {
			link.getAttributes().putAttribute("reasonOfRestriction", reason);
			return true;
		}

		String existingReasons = existing.toString();
		for (String existingReason : existingReasons.split(";")) {
			if (existingReason.trim().equals(reason)) {
				return false;
			}
		}

		link.getAttributes().putAttribute("reasonOfRestriction", existingReasons + "; " + reason);
		return true;
	}

	/**
	 * Effective restriction rule derived from the sign type and its optional value.
	 */
	record RestrictionRule(String reason, Set<String> disallowedModes) {
	}

	/**
	 * Spatial index for querying links close to a restriction segment midpoint.
	 */
	private static final class LinkGeometryIndex {
		private final STRtree index = new STRtree();

		private LinkGeometryIndex(Collection<? extends Link> links) {
			for (Link link : links) {
				Envelope envelope = GeometryUtils.createGeotoolsLineString(link).getEnvelopeInternal();
				index.insert(envelope, link);
			}
			index.build();
		}

		@SuppressWarnings("unchecked")
		private List<Link> query(Coord midpoint, double radius) {
			Envelope envelope = new Envelope(midpoint.getX(), midpoint.getX(), midpoint.getY(), midpoint.getY());
			envelope.expandBy(radius);
			return index.query(envelope);
		}
	}

	/**
	 * Candidate link accepted by the geometric matching filters.
	 */
	private record MatchedLink(Link link, double distance, double directionSimilarity) {
	}
}

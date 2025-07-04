package org.matsim.analysis;

import com.google.common.base.Verify;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.network.CreateAvroNetwork;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.prepare.TagTransitSchedule;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.CreatePseudoNetworkWithLoopLinks;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class PtCounts implements MATSimAppCommand {

	@CommandLine.Option(names = "--vrrSpnvShp",  description = "Path to reference data", defaultValue = "shared-svn/projects/rvr-metropole-ruhr/data/Fahrgastzahlen/2018_VRR/2018_VRR_EPSG25832.shp")
	private String vrrSpnvShp;

	@CommandLine.Option(names = "--transit-schedule",  description = "Path to transit schedule", required = true)
	private String transitSchedule;

	@CommandLine.Option(names="--network", description = "Path to MATSim network", required = true)
	private String network;

	@CommandLine.Option(names="--analysis-output", description = "Path to MATSim analysis", required = true)
	private String analysisOutput;

	@CommandLine.Option(names="--rootDirectory", description = "Path to root directory", required = true)
	private String rootDirectory;

	// more recent matsim-libs versions have upscaling implemented per default in PtStop2StopAnalysis, but this scenario uses an older version
	private double upScaleSampleFactor = 10.0;

	private Map<Id<TransitStopFacility>, Id<TransitStopFacility>> stop2parentStop = new HashMap<>();

	Logger log = LogManager.getLogger(PtCounts.class);

	private static String outputName = "pt-aggregated-for-analysis";

	@Override
	public Integer call() throws Exception {
		Path rootDirectory = Paths.get(this.rootDirectory);

		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.transit().setTransitScheduleFile(transitSchedule);
		config.network().setInputFile(network);

		Scenario simulatedScenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = simulatedScenario.getTransitSchedule();

		Network network = simulatedScenario.getNetwork();

		Scenario simplifiedScenario = createSimplifiedPtNetworkFromExistingNetwork(simulatedScenario);
		List<PassengerVolumes> passengerVolumesMatsim = matchPassengerVolumesToSimplifiedSchedule(simplifiedScenario);

		Map<Id<TransitStopFacility>, Map<Id<TransitStopFacility>, Double>> railPassengersMatsimPerLink =
			passengerVolumesMatsim.stream()
			.filter(p ->
				simplifiedScenario.getTransitSchedule().getTransitLines().get(p.transitLineId).getRoutes().get(p.transitRouteId)
					.getTransportMode().equals("rail")
					&& p.transitLineId.toString().startsWith("nrw")
			&& p.stopPreviousId != null) // removes entries for arrival at first stop with no passengers yet
			.collect(Collectors.groupingBy(PassengerVolumes::stopId,Collectors.groupingBy(PassengerVolumes::stopPreviousId,
				Collectors.summingDouble(PassengerVolumes::passengersAtArrival))));

		ShpOptions vrrShape = new ShpOptions(rootDirectory.resolve(vrrSpnvShp), null, null);
		Collection<SimpleFeature> vrrShpFeatures = vrrShape.readFeatures();

		Scenario vrrDummyScenario = createVRRDummyScenario(vrrShpFeatures, config.global().getCoordinateSystem());

		// route passenger volumes data on vrrDummyNetwork
		Map<Id<TransitStopFacility>, Node> simplifiedMatsimStop2vrrDummyNode = new HashMap<>();
		Map<Id<TransitStopFacility>, Map<Id<TransitStopFacility>, List<Link>>> stop2previousStop2route = new HashMap<>();

		routePassengerVolumesOnNetwork(vrrDummyScenario, railPassengersMatsimPerLink,
			simplifiedMatsimStop2vrrDummyNode, simplifiedScenario, stop2previousStop2route);

		(new NetworkWriter(vrrDummyScenario.getNetwork()))
			.write(rootDirectory.resolve(analysisOutput).resolve( "vrrDummy-network.xml.gz").toString());

		// previous G. Rybczak approach starts here

		Set<Id<Link>> ptLinkIdsInSchedule = new HashSet<>();

		for (TransitLine line : transitSchedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				if(route.getTransportMode().contains("rail")) {
					NetworkRoute netRoute = route.getRoute();
					ptLinkIdsInSchedule.addAll(netRoute.getLinkIds());
				}

			}
		}

		Collection<Link> ptLinks = network.getLinks().values().stream()
			.filter(link -> ptLinkIdsInSchedule.contains(link.getId()))
			.filter(link -> !link.getFromNode().getId().toString().contains("fern")) //filter long distance rail links
			.collect(Collectors.toList());

		GeometryFactory gf = new GeometryFactory();

		//convert pt links to LineString geometries
		Map<Link, LineString> ptLinkGeometries = new HashMap<>();
		for (Link link : ptLinks) {
			LineString line = gf.createLineString(new Coordinate[]{
				new Coordinate(link.getFromNode().getCoord().getX(), link.getFromNode().getCoord().getY()),
				new Coordinate(link.getToNode().getCoord().getX(), link.getToNode().getCoord().getY())
			});
			ptLinkGeometries.put(link, line);
		}

		List<FeatureSegments> featureSegments = new ArrayList<>();

		for (SimpleFeature feature : vrrShpFeatures) {
			Geometry geom = (Geometry) feature.getDefaultGeometry();
			List<LineString> segments = new ArrayList<>();

			if (geom instanceof LineString) {
				segments.addAll(splitLineString((LineString) geom, gf));
			} else if (geom instanceof MultiLineString) {
				MultiLineString mls = (MultiLineString) geom;
				for (int i = 0; i < mls.getNumGeometries(); i++) {
					segments.addAll(splitLineString((LineString) mls.getGeometryN(i), gf));
				}
			} else {
				System.out.println("Unsupported geometry: " + geom.getGeometryType());
			}

			featureSegments.add(new FeatureSegments(feature, segments));
		}


		int totalLineSegments = featureSegments.stream().mapToInt(fs -> fs.segments().size()).sum();
		System.out.println("Number of line strings: " + totalLineSegments);

		List<LineString> allSegments = featureSegments.stream()
				.flatMap(fs -> fs.segments().stream())
				.collect(Collectors.toList());

		writeLineStringsToShapefile(allSegments, "pt_counts.shp");


		double bufferDistance = 100.0; // meters
		Map<SimpleFeature, Set<Link>> featureMatches = matchFeaturesToPtLinks(featureSegments, ptLinkGeometries, bufferDistance);

		// Collect all matched links (flatten)
		Set<Link> matchedLinks = featureMatches.values().stream()
				.flatMap(Set::stream)
				.collect(Collectors.toSet());

		writeFeaturesWithMatchToCsv(featureSegments, featureMatches, "features_with_match.csv");

		System.out.println("Matched PT links: " + matchedLinks.size());


		writeLinksToCsv(matchedLinks, "matched_pt_links.csv");
		writeFilteredNetwork(simulatedScenario, matchedLinks, "filtered_network.xml");
		return 0;
	}

	private void routePassengerVolumesOnNetwork(Scenario vrrDummyScenario, Map<Id<TransitStopFacility>, Map<Id<TransitStopFacility>, Double>> railPassengersMatsimPerLink, Map<Id<TransitStopFacility>, Node> simplifiedMatsimStop2vrrDummyNode, Scenario simplifiedScenario, Map<Id<TransitStopFacility>, Map<Id<TransitStopFacility>, List<Link>>> stop2previousStop2route) {
		FreeSpeedTravelTime travelTime = new FreeSpeedTravelTime();
		LeastCostPathCalculatorFactory fastAStarLandmarksFactory = new SpeedyALTFactory();
		OnlyTimeDependentTravelDisutilityFactory disutilityFactory = new OnlyTimeDependentTravelDisutilityFactory();
		TravelDisutility travelDisutility = disutilityFactory.createTravelDisutility(travelTime);
		LeastCostPathCalculator router = fastAStarLandmarksFactory.createPathCalculator(vrrDummyScenario.getNetwork(), travelDisutility,
			travelTime);

		QuadTree<Node> vrrNodesQT = buildQuadTree(vrrDummyScenario.getNetwork());

		for (Link link : vrrDummyScenario.getNetwork().getLinks().values()) {
			link.getAttributes().putAttribute("matsim_pax_volumes", 0.0d);
		}

		for (Map.Entry<Id<TransitStopFacility>, Map<Id<TransitStopFacility>, Double>> toStop2previousStopsEntries : railPassengersMatsimPerLink.entrySet()) {
			Node vrrToNode = simplifiedMatsimStop2vrrDummyNode.get(toStop2previousStopsEntries.getKey());
			TransitStopFacility simplifiedMatsimToStop = simplifiedScenario.getTransitSchedule().getFacilities().get(toStop2previousStopsEntries.getKey());
			if (vrrToNode == null) {
				vrrToNode = vrrNodesQT.getClosest(simplifiedMatsimToStop.getCoord().getX(), simplifiedMatsimToStop.getCoord().getY());
				simplifiedMatsimStop2vrrDummyNode.put(simplifiedMatsimToStop.getId(), vrrToNode);
			}
			Map<Id<TransitStopFacility>, List<Link>> previousStops2Routes = stop2previousStop2route.computeIfAbsent(simplifiedMatsimToStop.getId(), m -> new HashMap<>());

			for (var previousStop2PassengerVolume : toStop2previousStopsEntries.getValue().entrySet()) {
				Node vrrFromNode = simplifiedMatsimStop2vrrDummyNode.get(previousStop2PassengerVolume.getKey());
				TransitStopFacility simplifiedMatsimFromStop = simplifiedScenario.getTransitSchedule().getFacilities().get(previousStop2PassengerVolume.getKey());
				if (vrrFromNode == null) {
					vrrFromNode = vrrNodesQT.getClosest(simplifiedMatsimFromStop.getCoord().getX(), simplifiedMatsimFromStop.getCoord().getY());
					simplifiedMatsimStop2vrrDummyNode.put(simplifiedMatsimFromStop.getId(), vrrFromNode);
				}

				List<Link> savedRoute = previousStops2Routes.get(simplifiedMatsimFromStop.getId());
				if (savedRoute == null) {
					savedRoute = new ArrayList<>();
					LeastCostPathCalculator.Path route = router.calcLeastCostPath(vrrFromNode, vrrToNode, 0.0d, null, null);
					savedRoute.addAll(route.links);
				}
				for (Link link : savedRoute) {
					link.getAttributes().putAttribute("matsim_pax_volumes",
						(double) link.getAttributes().getAttribute("matsim_pax_volumes") + previousStop2PassengerVolume.getValue());
				}
			}
		}

		for (Link link : vrrDummyScenario.getNetwork().getLinks().values()) {
			// add opposite direction pax volumes to compare with bidirectional sums in vrr data
			Link oppositeLink = NetworkUtils.findLinkInOppositeDirection(link);
			double matsimPaxVolumesBidirectional = (double) link.getAttributes().getAttribute("matsim_pax_volumes")
				+ (double) oppositeLink.getAttributes().getAttribute("matsim_pax_volumes");
			double vrrPaxVolumesBidirectional = (double) link.getAttributes().getAttribute("BELEGUNG_M");
			double diffPaxVolumesBidirectional = matsimPaxVolumesBidirectional - vrrPaxVolumesBidirectional;
			double sqv = 1 / (1 + Math.sqrt(Math.pow(diffPaxVolumesBidirectional, 2) / (10000 * vrrPaxVolumesBidirectional)));
			link.getAttributes().putAttribute("matsim_pax_volumes_bidirectional", matsimPaxVolumesBidirectional);
			link.getAttributes().putAttribute("diff_pax_volumes_bidirectional", diffPaxVolumesBidirectional);
			link.getAttributes().putAttribute("sqv_pax_volumes_bidirectional", sqv);
		}
	}

	private Scenario createVRRDummyScenario(Collection<SimpleFeature> vrrShpFeatures, String coordinateSystem) {
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem(coordinateSystem);
		Scenario vrrDummyScenario = ScenarioUtils.createScenario(config);

		for (SimpleFeature feature : vrrShpFeatures) {
			Geometry geom = (Geometry) feature.getDefaultGeometry();

			if (geom instanceof LineString) {
				createNodesAndLinkForLineString((LineString) geom, feature, vrrDummyScenario);
			} else if (geom instanceof MultiLineString) {
				MultiLineString mls = (MultiLineString) geom;
				for (int i = 0; i < mls.getNumGeometries(); i++) {
					createNodesAndLinkForLineString((LineString) mls.getGeometryN(i), feature, vrrDummyScenario);
				}
			} else {
				System.out.println("Unsupported geometry: " + geom.getGeometryType());
			}
		}

		return vrrDummyScenario;
	}

	private void createNodesAndLinkForLineString(LineString line, SimpleFeature feature, Scenario vrrDummyScenario) {
		Coordinate[] coords = line.getCoordinates();
		Coord fromCoord = CoordUtils.createCoord(coords[0]);
		Coord toCoord = CoordUtils.createCoord(coords[1]);

		String fromStopName = (String) feature.getAttribute("STATIONSNA");
		String toStopName = (String) feature.getAttribute("STATIONS_1");

		NetworkFactory networkFactory = vrrDummyScenario.getNetwork().getFactory();

		Id<Node> fromNodeId = Id.createNodeId(fromStopName);
		Node fromNode = vrrDummyScenario.getNetwork().getNodes().get(fromNodeId);
		if (fromNode == null) {
			// create and add
			fromNode = networkFactory.createNode(fromNodeId, fromCoord);
			fromNode.getAttributes().putAttribute("vrrName", fromStopName);
			fromNode.getAttributes().putAttribute("DS100Name", feature.getAttribute("DS100_AB"));
			vrrDummyScenario.getNetwork().addNode(fromNode);
		} else {
			// check consistency
			Verify.verify(CoordUtils.calcEuclideanDistance(fromNode.getCoord(), fromCoord) < 1.0);
			Verify.verify(fromNode.getAttributes().getAttribute("vrrName").equals(fromStopName));
			Verify.verify(fromNode.getAttributes().getAttribute("DS100Name").equals(feature.getAttribute("DS100_AB")));
		}

		Id<Node> toNodeId = Id.createNodeId(toStopName);
		Node toNode = vrrDummyScenario.getNetwork().getNodes().get(toNodeId);
		if (toNode == null) {
			// create and add
			toNode = networkFactory.createNode(toNodeId, toCoord);
			toNode.getAttributes().putAttribute("vrrName", toStopName);
			toNode.getAttributes().putAttribute("DS100Name", feature.getAttribute("DS100_AN"));
			vrrDummyScenario.getNetwork().addNode(toNode);
		} else {
			// check consistency
			Verify.verify(CoordUtils.calcEuclideanDistance(toNode.getCoord(), toCoord) < 1.0);
			Verify.verify(toNode.getAttributes().getAttribute("vrrName").equals(toStopName));
			Verify.verify(toNode.getAttributes().getAttribute("DS100Name").equals(feature.getAttribute("DS100_AN")));
		}

		// need both directions for routing
		createAndAddLink(feature, vrrDummyScenario, networkFactory, fromStopName, toStopName, fromNode, toNode);
		createAndAddLink(feature, vrrDummyScenario, networkFactory, toStopName, fromStopName, toNode, fromNode);
	}

	private static void createAndAddLink(SimpleFeature feature, Scenario vrrDummyScenario, NetworkFactory networkFactory, String fromStopName, String toStopName, Node fromNode, Node toNode) {
		Link link = networkFactory.createLink(Id.createLinkId(fromStopName + "-" + toStopName), fromNode, toNode);
		link.getAttributes().putAttribute("vrrShpLinkId", feature.getAttribute("ID"));
		link.getAttributes().putAttribute("DS100_AB", feature.getAttribute("DS100_AB"));
		link.getAttributes().putAttribute("DS100_AN", feature.getAttribute("DS100_AN"));
		link.getAttributes().putAttribute("BELEGUNG_M", feature.getAttribute("BELEGUNG_M"));
		vrrDummyScenario.getNetwork().addLink(link);
	}

	private QuadTree<Node> buildQuadTree(Network network) {
		double minx = Double.POSITIVE_INFINITY;
		double miny = Double.POSITIVE_INFINITY;
		double maxx = Double.NEGATIVE_INFINITY;
		double maxy = Double.NEGATIVE_INFINITY;
		for (Node n : network.getNodes().values()) {
			if (n.getCoord().getX() < minx) {
				minx = n.getCoord().getX();
			}
			if (n.getCoord().getY() < miny) {
				miny = n.getCoord().getY();
			}
			if (n.getCoord().getX() > maxx) {
				maxx = n.getCoord().getX();
			}
			if (n.getCoord().getY() > maxy) {
				maxy = n.getCoord().getY();
			}
		}
		minx -= 1.0;
		miny -= 1.0;
		maxx += 1.0;
		maxy += 1.0;
		// yy the above four lines are problematic if the coordinate values are much smaller than one. kai, oct'15

		log.info("building QuadTree for nodes: xrange(" + minx + "," + maxx + "); yrange(" + miny + "," + maxy + ")");
		QuadTree<Node> quadTree = new QuadTree<>(minx, miny, maxx, maxy);
		for (Node n : network.getNodes().values()) {
			quadTree.put(n.getCoord().getX(), n.getCoord().getY(), n);
		}
		return quadTree;
	}

	public static void main(String[] args) {
		new PtCounts().execute(args);
	}

	private static List<LineString> splitLineString(LineString line, GeometryFactory geometryFactory) {
		List<LineString> segments = new ArrayList<>();
		Coordinate[] coords = line.getCoordinates();

		for (int i = 0; i < coords.length - 1; i++) {
			Coordinate[] segmentCoords = new Coordinate[]{coords[i], coords[i + 1]};
			LineString segment = geometryFactory.createLineString(segmentCoords);
			segments.add(segment);
		}

		return segments;
	}

	private static void writeLineStringsToShapefile(List<LineString> lineStrings, String filePath) throws Exception {
		// Define feature type (geometry + attributes)
		SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
		typeBuilder.setName("lines");
		typeBuilder.setCRS(CRS.decode("EPSG:25832", true));
		typeBuilder.add("the_geom", LineString.class);
		typeBuilder.add("id", Integer.class);
		final SimpleFeatureType TYPE = typeBuilder.buildFeatureType();

		// Create Shapefile
		File file = new File(filePath);
		ShapefileDataStoreFactory dataStoreFactory = new ShapefileDataStoreFactory();

		Map<String, Serializable> params = new HashMap<>();
		params.put("url", file.toURI().toURL());
		params.put("create spatial index", Boolean.TRUE);

		ShapefileDataStore dataStore = (ShapefileDataStore) dataStoreFactory.createNewDataStore(params);
		dataStore.createSchema(TYPE);
		dataStore.setCharset(StandardCharsets.UTF_8);

		// Write features
		Transaction transaction = new DefaultTransaction("create");

		try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer =
				 dataStore.getFeatureWriterAppend(dataStore.getTypeNames()[0], transaction)) {

			int id = 0;
			for (LineString line : lineStrings) {
				SimpleFeature feature = writer.next();
				feature.setAttribute("the_geom", line);
				feature.setAttribute("id", id++);
				writer.write();
			}

			transaction.commit();
			System.out.println("Shapefile written to " + filePath);

		} catch (Exception e) {
			transaction.rollback();
			throw e;
		} finally {
			transaction.close();
		}
	}

	private static void writeLinksToCsv(Collection<Link> links, String filePath) throws IOException {
		try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
			// Write header
			writer.println("link_id,from_x,from_y,to_x,to_y,length,modes");

			// Write each link
			for (Link link : links) {
				String line = String.join(",",
					link.getId().toString(),
					String.valueOf(link.getFromNode().getCoord().getX()),
					String.valueOf(link.getFromNode().getCoord().getY()),
					String.valueOf(link.getToNode().getCoord().getX()),
					String.valueOf(link.getToNode().getCoord().getY()),
					String.valueOf(link.getLength()),
					String.join(" ", link.getAllowedModes())
				);
				writer.println(line);
			}
		}

		System.out.println("Written PT links to CSV: " + filePath);
	}

	private static void writeFilteredNetwork(Scenario scenario, Collection<Link> matchedLinks, String outputFile) {
		NetworkFactory factory = scenario.getNetwork().getFactory();
		Network filteredNetwork = NetworkUtils.createNetwork();

		// Collect used nodes
		Set<Node> usedNodes = new HashSet<>();
		for (Link link : matchedLinks) {
			usedNodes.add(link.getFromNode());
			usedNodes.add(link.getToNode());
		}

		// Add nodes to filtered network
		for (Node node : usedNodes) {
			Node newNode = factory.createNode(node.getId(), node.getCoord());
			filteredNetwork.addNode(newNode);
		}

		// Add links to filtered network
		for (Link oldLink : matchedLinks) {
			Node fromNode = filteredNetwork.getNodes().get(oldLink.getFromNode().getId());
			Node toNode = filteredNetwork.getNodes().get(oldLink.getToNode().getId());

			Link newLink = factory.createLink(oldLink.getId(), fromNode, toNode);
			newLink.setLength(oldLink.getLength());
			newLink.setFreespeed(oldLink.getFreespeed());
			newLink.setCapacity(oldLink.getCapacity());
			newLink.setNumberOfLanes(oldLink.getNumberOfLanes());
			newLink.setAllowedModes(oldLink.getAllowedModes());
			// Copy any additional attributes if needed here

			filteredNetwork.addLink(newLink);
		}

		// Write network to file
		new NetworkWriter(filteredNetwork).write(outputFile);
		System.out.println("Filtered network written to " + outputFile + " with " + filteredNetwork.getLinks().size() + " links and " + filteredNetwork.getNodes().size() + " nodes.");
	}

	private static void writeFeaturesWithMatchToCsv(
			List<FeatureSegments> featureSegments,
			Map<SimpleFeature, Set<Link>> featureMatches,
			String csvFilePath
	) throws IOException {

		if (featureSegments.isEmpty()) {
			System.out.println("No features to write.");
			return;
		}

		try (PrintWriter writer = new PrintWriter(new FileWriter(csvFilePath))) {
			// Write header based on the first feature
			SimpleFeature firstFeature = featureSegments.get(0).feature();
			List<String> attributeNames = new ArrayList<>();
			firstFeature.getFeatureType().getAttributeDescriptors().forEach(desc -> {
				attributeNames.add(desc.getLocalName());
			});
			attributeNames.add("matched_links");

			writer.println(String.join(",", attributeNames));

			for (FeatureSegments fs : featureSegments) {
				SimpleFeature feature = fs.feature();

				// Get matched links for this feature
				Set<String> matchedLinkIds = featureMatches.getOrDefault(feature, Set.of()).stream()
						.map(link -> link.getId().toString())
						.collect(Collectors.toCollection(TreeSet::new)); // TreeSet for consistent ordering

				// Format matched link IDs as a semicolon-separated string
				String matchedLinksStr = String.join(";", matchedLinkIds);

				// Write all original attributes + matched_links column to CSV
				List<String> values = new ArrayList<>();
				for (String attrName : attributeNames) {
					if ("matched_links".equals(attrName)) {
						String safeValue = matchedLinksStr.replace("\"", "\"\"");
						if (safeValue.contains(",") || safeValue.contains("\"") || safeValue.contains(";")) {
							safeValue = "\"" + safeValue + "\"";
						}
						values.add(safeValue);
					} else {
						Object attrValue = feature.getAttribute(attrName);
						String safeValue = attrValue == null ? "" : attrValue.toString().replace("\"", "\"\"");
						if (safeValue.contains(",") || safeValue.contains("\"")) {
							safeValue = "\"" + safeValue + "\"";
						}
						values.add(safeValue);
					}
				}
				writer.println(String.join(",", values));
			}
		}

		System.out.println("Written features with matched link IDs to CSV: " + csvFilePath);
	}


	private static Map<SimpleFeature, Set<Link>> matchFeaturesToPtLinks(
			List<FeatureSegments> featureSegments,
			Map<Link, LineString> ptLinkGeometries,
			double bufferDistance
	) {
		Map<SimpleFeature, Set<Link>> matches = new LinkedHashMap<>();

		for (FeatureSegments fs : featureSegments) {
			Set<Link> matched = new HashSet<>();
			for (LineString segment : fs.segments()) {
				Geometry buffer = segment.buffer(bufferDistance);
				for (Map.Entry<Link, LineString> entry : ptLinkGeometries.entrySet()) {
					if (buffer.contains(entry.getValue())) {
						matched.add(entry.getKey());
					}
				}
			}
			matches.put(fs.feature(), matched);
		}

		return matches;
	}

	record FeatureSegments(SimpleFeature feature, List<LineString> segments) {}

	/** simplified pt network, transit-schedule etc. to display in Simwrapper
	 * Gives different number of transit routes than original transit schedule */
	private void createSimplifiedPtNetworkFromGtfs() {
		Path rootDirectory = Paths.get(this.rootDirectory);

		// copied from CreateSupply: TODO: either make public there or implement other gtfs2matsim options directly there
		Path gtfsData1 = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/gtfs/20230106_gtfs_nrw_neue_service_ids_korrektur1.zip");
		Path gtfsData2 = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/gtfs/gtfs-schienenfernverkehr-de_2021-08-19.zip");
		String gtfsDataDate1 = "2023-01-17";
		String gtfsDataDate2 = "2021-08-19";
		String gtfsData1Prefix = "nrw";
		//private static final String gtfsData2Prefix = "nwl";
		String gtfsData2Prefix = "fern";
		Path ruhrShape = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/shp-files/ruhrgebiet_boundary/ruhrgebiet_boundary.shp");


		Path roadNetwork = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.1/input/metropole-ruhr-v2024.1.network_resolutionHigh.xml.gz");
		Path outputDir = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.1/input");
		String outputName = "metropole-ruhr-v2024.1-pt-aggregated-all-modes";

		new CreateTransitScheduleFromGtfs().execute(
			rootDirectory.resolve(gtfsData1).toString(), rootDirectory.resolve(gtfsData2).toString(),
			"--date", gtfsDataDate1, gtfsDataDate2,
			"--prefix", gtfsData1Prefix + "," + gtfsData2Prefix,
			"--target-crs", "EPSG:25832",
			"--network", rootDirectory.resolve(roadNetwork).toString(),
			"--output", rootDirectory.resolve(outputDir).toString(),
			"--copy-late-early=true",
			"--validate=true",
			"--pseudo-network=withLoopLinks",
			"--merge-stops=mergeToGtfsParentStation",
			"--name", outputName
		);

		// --------------------------------------------------------------------

		new TagTransitSchedule().execute(
			"--input", rootDirectory.resolve(outputDir) + "/" + outputName + "-transitSchedule.xml.gz",
			"--shp", rootDirectory.resolve(ruhrShape).toString(),
			"--output", rootDirectory.resolve(outputDir) + "/" + outputName + "-transitSchedule.xml.gz"
		);
	}

	private Scenario createSimplifiedPtNetworkFromExistingNetwork(Scenario simulatedScenario) {
		Scenario simplifiedScenario = ScenarioUtils.createScenario(simulatedScenario.getConfig());

		TransitSchedule simplifiedSchedule = simplifiedScenario.getTransitSchedule();
		TransitScheduleFactory scheduleFactory = simplifiedSchedule.getFactory();

		int noParentStopCounter = 0;

		for (TransitStopFacility simulatedStop : simulatedScenario.getTransitSchedule().getFacilities().values()) {
			TransitStopFacility simulatedParentStop;

			// unfortunately most TransitStopFacilities are at below track level (pseudo network without loops needs these)
			// and have as a stop area a track, not the parent station to which belongs the track
			// try to find the parent station
			// FIXME: caution: this assumes that as in the nrw data set all parent_stations are called nrw:XX:XXXXX_parent,
			// see also https://wiki.openstreetmap.org/wiki/DE:Key:ref:IFOPT
			String firstLevelParentStationString;
			if (simulatedStop.getStopAreaId() != null) {
				firstLevelParentStationString = simulatedStop.getStopAreaId().toString();
			} else {
				firstLevelParentStationString = simulatedStop.getId().toString();
			}
			Id<TransitStopFacility> parentStationId;
			if (firstLevelParentStationString.endsWith("_Parent")) {
				parentStationId = Id.create(firstLevelParentStationString, TransitStopFacility.class);
			} else {
				String[] parentStationSplit = firstLevelParentStationString.split(":");
				if (parentStationSplit.length >= 3) {
					// should be 5 (nrw:XX:XXXXX:XX:XX)
					parentStationId = Id.create(parentStationSplit[0] + ":" + parentStationSplit[1] + ":" + parentStationSplit[2] + "_Parent",
						TransitStopFacility.class);
				} else {
					// fall back to detailed level stop if no parent station could be found
					parentStationId = simulatedStop.getId();
				}

			}
			simulatedParentStop = simulatedScenario.getTransitSchedule().getFacilities().get(parentStationId);


			if (simulatedParentStop == null) {
				// fall back to detailed level stop if no parent station could be found
				noParentStopCounter++;
				simulatedParentStop = simulatedStop;
			}

			TransitStopFacility simplifiedParentStop = scheduleFactory
				.createTransitStopFacility(simulatedParentStop.getId(), simulatedParentStop.getCoord(), false);
			simplifiedParentStop.setName(simulatedParentStop.getName());
			stop2parentStop.put(simulatedStop.getId(), simplifiedParentStop.getId());
			if (!simplifiedSchedule.getFacilities().containsKey(simplifiedParentStop.getId())) {
				simplifiedSchedule.addStopFacility(simplifiedParentStop);
			}
		}

		log.warn("No parent stop found for " + noParentStopCounter + " of " +
			simulatedScenario.getTransitSchedule().getFacilities().size() +" stop facilities. Using stop facility directly instead.");

		for (TransitLine simulatedLine : simulatedScenario.getTransitSchedule().getTransitLines().values()) {
			TransitLine simplifiedLine = scheduleFactory.createTransitLine(simulatedLine.getId());
			simplifiedSchedule.addTransitLine(simplifiedLine);
			for (TransitRoute simulatedRoute: simulatedLine.getRoutes().values()) {
				List<TransitRouteStop> simplifiedRouteStops = new ArrayList<>();
				for (TransitRouteStop simulatedRouteStop : simulatedRoute.getStops()) {
					simplifiedRouteStops.add(scheduleFactory
						.createTransitRouteStop(
							simplifiedSchedule.getFacilities().get(stop2parentStop.get(simulatedRouteStop.getStopFacility().getId())),
							simulatedRouteStop.getArrivalOffset(), simulatedRouteStop.getDepartureOffset()));
				}

				TransitRoute simplifiedRoute = scheduleFactory.createTransitRoute(simulatedRoute.getId(), null, simplifiedRouteStops,
					simulatedRoute.getDescription());
				simplifiedRoute.setTransportMode(simulatedRoute.getTransportMode());

				for (Departure simulatedDeparture : simulatedRoute.getDepartures().values()) {
					simplifiedRoute.addDeparture(scheduleFactory.createDeparture(simulatedDeparture.getId(), simulatedDeparture.getDepartureTime()));
				}

				simplifiedLine.addRoute(simplifiedRoute);
			}
		}
		// TransitVehicles not necessary

		new CreatePseudoNetworkWithLoopLinks(simplifiedSchedule, simplifiedScenario.getNetwork(), "pt_", 100.0, 100000.0).createNetwork();
		simplifiedScenario.getNetwork().getAttributes().putAttribute("coordinateReferenceSystem",
			simulatedScenario.getNetwork().getAttributes().getAttribute("coordinateReferenceSystem"));
		simplifiedScenario.getTransitSchedule().getAttributes().putAttribute("coordinateReferenceSystem",
			simulatedScenario.getTransitSchedule().getAttributes().getAttribute("coordinateReferenceSystem"));

		for (TransitStopFacility stop: simplifiedSchedule.getFacilities().values()) {
			if (stop.getCoord()== null) {
				log.error(stop.getId() + " has coord null");
			}
		}

		TransitScheduleValidator.ValidationResult checkResult = TransitScheduleValidator.validateAll(simplifiedScenario.getTransitSchedule(), simplifiedScenario.getNetwork());
		List<String> warnings = checkResult.getWarnings();
		if (!warnings.isEmpty())
			log.warn("TransitScheduleValidator warnings: {}", String.join("\n", warnings));

		if (checkResult.isValid()) {
			log.info("TransitSchedule and Network valid according to TransitScheduleValidator");
		} else {
			log.error("TransitScheduleValidator errors: {}", String.join("\n", checkResult.getErrors()));
			throw new RuntimeException("TransitSchedule and/or Network invalid");
		}

		Path rootDirectory = Paths.get(this.rootDirectory);

		(new MatsimVehicleWriter(simulatedScenario.getTransitVehicles())).writeFile(rootDirectory.resolve(analysisOutput).resolve(outputName + "-transitVehicles.xml.gz").toString());
		(new NetworkWriter(simplifiedScenario.getNetwork())).write(rootDirectory.resolve(analysisOutput).resolve(outputName + "-network.xml.gz").toString());
		(new TransitScheduleWriter(simplifiedSchedule)).writeFile(rootDirectory.resolve(analysisOutput).resolve(outputName + "-transitSchedule.xml.gz").toString());

		new CreateAvroNetwork().execute("--network", rootDirectory.resolve(analysisOutput).resolve(outputName + "-network.xml.gz").toString(),
			"--output", rootDirectory.resolve(analysisOutput).resolve(outputName + "-network.avro").toString(),
			"--mode-filter=none");
		return simplifiedScenario;
	}

	private List<PassengerVolumes> matchPassengerVolumesToSimplifiedSchedule(Scenario simplifiedScenario) {
		Path rootDirectory = Paths.get(this.rootDirectory);
		// TODO: add unzipping
		Path ptPaxVolumesFile = Paths.get("runs-svn/rvr-ruhrgebiet/v2024.1/no-intermodal/002.pt_stop2stop_departures.csv");

		Reader reader = null;
		try {
			reader = Files.newBufferedReader(rootDirectory.resolve(ptPaxVolumesFile));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		CSVFormat csvFormat = CSVFormat.Builder
			.create()
			.setDelimiter(";")
			.setHeader()
			.build();
		CSVParser csvParser = null;
		try {
			csvParser = csvFormat.parse(reader);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		List<CSVRecord> records = csvParser.getRecords();
		List<PassengerVolumes> passengerVolumes = new ArrayList<>(records.size());
		for (CSVRecord record: records) {
			Id<TransitLine> transitLineId = Id.create(record.get("transitLine"), TransitLine.class);
			Id<TransitRoute> transitRouteId = Id.create(record.get("transitRoute"), TransitRoute.class);
			Id<Departure> departureId = Id.create(record.get("departure"), Departure.class);
			Id<TransitStopFacility> stop = stop2parentStop.get(Id.create(record.get("stop"), TransitStopFacility.class));
			Id<TransitStopFacility> stopPrevious = stop2parentStop.get(Id.create(record.get("stopPrevious"), TransitStopFacility.class));
			List<Id<Link>> linkIdsSincePreviousStopSimulatedScenario = new ArrayList<>();
			linkIdsSincePreviousStopSimulatedScenario.addAll(Arrays.stream(record.get("linkIdsSincePreviousStop").split(","))
				.map(string -> Id.createLinkId(string)).collect(Collectors.toList()));

			// FIXME: dirty: copied naming convention from CreatePseudoNetworkWithLoopLinks.createAndAddLink()
			List<Id<Link>> linkIdsSincePreviousStop = new ArrayList<>();
			if (stopPrevious!=null) {
				linkIdsSincePreviousStop.add(Id.createLinkId("pt_" + stopPrevious + "-"  + "pt_" + stop));
			}
			linkIdsSincePreviousStop.add(Id.createLinkId("pt_" + stop));

			PassengerVolumes entry = new PassengerVolumes(transitLineId, transitRouteId, departureId, stop,
				Integer.parseInt(record.get("stopSequence")), stopPrevious, Double.parseDouble(record.get("arrivalTimeScheduled")),
				Double.parseDouble(record.get("arrivalDelay")), Double.parseDouble(record.get("departureTimeScheduled")),
				Double.parseDouble(record.get("departureDelay")),
				Double.parseDouble(record.get("passengersAtArrival")) * upScaleSampleFactor,
				Double.parseDouble(record.get("totalVehicleCapacity")),
				Double.parseDouble(record.get("passengersAlighting")) * upScaleSampleFactor,
				Double.parseDouble(record.get("passengersBoarding")) * upScaleSampleFactor,
				linkIdsSincePreviousStop,
				Id.create(record.get("stop"), TransitStopFacility.class), Id.create(record.get("stopPrevious"), TransitStopFacility.class),
				linkIdsSincePreviousStopSimulatedScenario);
			passengerVolumes.add(entry);
		}

		writePassengerVolumesCsv(IOUtils.getFileUrl(rootDirectory.resolve(analysisOutput).resolve(outputName + "-pax_volumes.csv.gz").toString()),
			";", ",", passengerVolumes);

		return passengerVolumes;
	}


	record PassengerVolumes (Id<TransitLine> transitLineId, Id<TransitRoute> transitRouteId, Id<Departure> departureId, Id<TransitStopFacility> stopId,
							 int stopSequence, Id<TransitStopFacility> stopPreviousId, double arrivalTimeScheduled, double arrivalDelay,
							 double departureTimeScheduled, double departureDelay, double passengersAtArrival, double totalVehicleCapacity,
							 double passengersAlighting, double passengersBoarding, List<Id<Link>> linkIdsSincePreviousStop,
							 Id<TransitStopFacility> stopIdSimulatedScenario, Id<TransitStopFacility> stopPreviousIdSimulatedScenario,
							 List<Id<Link>> linkIdsSincePreviousStopSimulatedScenario
							 )
	{

	}

	static Comparator<PassengerVolumes> stop2StopEntryByTransitLineComparator =
		Comparator.nullsLast(Comparator.comparing(PassengerVolumes::transitLineId));
	static Comparator<PassengerVolumes> stop2StopEntryByTransitRouteComparator =
		Comparator.nullsLast(Comparator.comparing(PassengerVolumes::transitRouteId));
	static Comparator<PassengerVolumes> stop2StopEntryByDepartureComparator =
		Comparator.nullsLast(Comparator.comparing(PassengerVolumes::departureId));
	static Comparator<PassengerVolumes> stop2StopEntryByStopSequenceComparator =
		Comparator.nullsLast(Comparator.comparing(PassengerVolumes::stopSequence));

	public void writePassengerVolumesCsv(URL url, String columnSeparator, String listSeparatorInsideColumn, List<PassengerVolumes> entries) {
		final String[] HEADER = {"transitLine", "transitRoute", "departure", "stop", "stopSequence",
			"stopPrevious", "arrivalTimeScheduled", "arrivalDelay", "departureTimeScheduled", "departureDelay",
			"passengersAtArrival", "totalVehicleCapacity", "passengersAlighting", "passengersBoarding",
			"linkIdsSincePreviousStop"/*, "stopIdSimulatedScenario", "stopPreviousIdSimulatedScenario",
			"linkIdsSincePreviousStopSimulatedScenario"*/};

		entries.sort(stop2StopEntryByTransitLineComparator.
			thenComparing(stop2StopEntryByTransitRouteComparator).
			thenComparing(stop2StopEntryByDepartureComparator).
			thenComparing(stop2StopEntryByStopSequenceComparator));
		try (CSVPrinter printer = new CSVPrinter(IOUtils.getBufferedWriter(url),
			CSVFormat.Builder.create()
				.setDelimiter(columnSeparator)
				.setHeader(HEADER)
				.build())
		) {
			for (PassengerVolumes entry : entries) {
				printer.print(entry.transitLineId);
				printer.print(entry.transitRouteId);
				printer.print(entry.departureId);
				printer.print(entry.stopId);
				printer.print(entry.stopSequence);
				printer.print(entry.stopPreviousId);
				printer.print(entry.arrivalTimeScheduled);
				printer.print(entry.arrivalDelay);
				printer.print(entry.departureTimeScheduled);
				printer.print(entry.departureDelay);
				printer.print(entry.passengersAtArrival);
				printer.print(entry.totalVehicleCapacity);
				printer.print(entry.passengersAlighting);
				printer.print(entry.passengersBoarding);
				printer.print(entry.linkIdsSincePreviousStop.stream().map(Object::toString).collect(Collectors.joining(listSeparatorInsideColumn)));
//				printer.print(entry.stopIdSimulatedScenario);
//				printer.print(entry.stopPreviousIdSimulatedScenario);
//				printer.print(entry.linkIdsSincePreviousStopSimulatedScenario.stream().map(Object::toString).collect(Collectors.joining(listSeparatorInsideColumn)));
				printer.println();
			}
		} catch (IOException e) {
			log.error(e);
		}
	}
}

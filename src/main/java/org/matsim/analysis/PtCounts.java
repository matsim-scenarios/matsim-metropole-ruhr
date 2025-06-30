package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.prepare.CreateSupply;
import org.matsim.prepare.TagTransitSchedule;
import org.matsim.pt.transitSchedule.api.*;
import picocli.CommandLine;

import java.io.*;
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

	@CommandLine.Option(names="--rootDirectory", description = "Path to root directory", required = true)
	private String rootDirectory;

	@Override
	public Integer call() throws Exception {
//		createAggregatedPtNetwork();


		Path rootDirectory = Paths.get(this.rootDirectory);

		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.transit().setTransitScheduleFile(transitSchedule);
		config.network().setInputFile(network);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();

		Network network = scenario.getNetwork();

		readPassengerVolumes(scenario);

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

		ShpOptions vrrShape = new ShpOptions(rootDirectory.resolve(vrrSpnvShp), null, null);
		Collection<SimpleFeature> features = vrrShape.readFeatures();
		List<FeatureSegments> featureSegments = new ArrayList<>();

		for (SimpleFeature feature : features) {
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
		writeFilteredNetwork(scenario, matchedLinks, "filtered_network.xml");
		return 0;
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

	/** aggregated pt network, transit-schedule etc. to display in Simwrapper*/
	private void createAggregatedPtNetwork() {
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

	private void readPassengerVolumes(Scenario scenario) {
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

			TransitStopFacility fromTransitStop = scenario.getTransitSchedule().getFacilities().get(Id.create(record.get("stop"), TransitStopFacility.class));
			TransitStopFacility toTransitStop = scenario.getTransitSchedule().getFacilities().get(Id.create(record.get("stopPrevious"), TransitStopFacility.class));

			List<Id<Link>> linkIdsSincePreviousStop = new ArrayList<>();
			linkIdsSincePreviousStop.add(Id.createLinkId(record.get("linkIdsSincePreviousStop"))); //TODO: separate string list

			PassengerVolumes entry = new PassengerVolumes(transitLineId, transitRouteId, departureId, fromTransitStop.getId(),
				Integer.parseInt(record.get("stopSequence")), toTransitStop.getId(), Double.parseDouble(record.get("arrivalTimeScheduled")),
				Double.parseDouble(record.get("arrivalDelay")), Double.parseDouble(record.get("departureTimeScheduled")),
				Double.parseDouble(record.get("departureDelay")), Double.parseDouble(record.get("passengersAtArrival")),
				Double.parseDouble(record.get("totalVehicleCapacity")), Double.parseDouble(record.get("passengersAlighting")),
				Double.parseDouble(record.get("passengersBoarding")), linkIdsSincePreviousStop, fromTransitStop.getStopAreaId(),
				toTransitStop.getStopAreaId());
			passengerVolumes.add(entry);

		}
	}


	record PassengerVolumes (Id<TransitLine> transitLineId, Id<TransitRoute> transitRouteId, Id<Departure> departureId, Id<TransitStopFacility> stopId,
							 int stopSequence, Id<TransitStopFacility> stopPreviousId, double arrivalTimeScheduled, double arrivalDelay,
							 double departureTimeScheduled, double departureDelay, double passengersAtArrival, double totalVehicleCapacity,
							 double passengersAlighting, double passengersBoarding, List<Id<Link>> linkIdsSincePreviousStop,
							 Id<TransitStopArea> stopAreaId, Id<TransitStopArea> stopAreaPreviousId) {}
	{

	}

}

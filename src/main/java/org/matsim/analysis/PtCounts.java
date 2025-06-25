package org.matsim.analysis;

import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import picocli.CommandLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class PtCounts implements MATSimAppCommand {

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--transit-schedule",  description = "Path to transit schedule", required = true)
	private String transitSchedule;

	@CommandLine.Option(names="--network", description = "Path to MATSim network", required = true)
	private String network;

	@Override
	public Integer call() throws Exception {
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.transit().setTransitScheduleFile("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v2024.0/10pct/016.output_transitSchedule.xml.gz");
		config.network().setInputFile(network);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();

		Network network = scenario.getNetwork();

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




		Collection<SimpleFeature> features = shp.readFeatures();


		List<LineString> lineStrings = new ArrayList<>();

		for (SimpleFeature feature : features) {
			Geometry geom = (Geometry) feature.getDefaultGeometry();

			if (geom instanceof LineString) {
				lineStrings.addAll(splitLineString((LineString) geom, gf));
			} else if (geom instanceof MultiLineString) {
				MultiLineString mls = (MultiLineString) geom;
				for (int i = 0; i < mls.getNumGeometries(); i++) {
					LineString ls = (LineString) mls.getGeometryN(i);
					lineStrings.addAll(splitLineString(ls, gf));
				}
			} else {
				System.out.println("Unsupported geometry: " + geom.getGeometryType());
			}


		}
		System.out.println("Number of line strings: " + lineStrings.size());

		writeLineStringsToShapefile(lineStrings, "pt_counts.shp");

		Set<Link> matchedLinks = new HashSet<>();

		double bufferDistance = 100.0; // meters

		for (LineString line : lineStrings) {
			Geometry buffer = line.buffer(bufferDistance);
			for (Map.Entry<Link, LineString> entry : ptLinkGeometries.entrySet()) {
				if (buffer.intersects(entry.getValue())) {
					matchedLinks.add(entry.getKey());
				}
			}
		}

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



}

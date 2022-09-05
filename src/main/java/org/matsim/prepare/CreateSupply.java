package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.OsmBicycleReader;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.prepare.counts.CombinedCountsWriter;
import org.matsim.prepare.counts.LongTermCountsCreator;
import org.matsim.prepare.counts.RawDataVehicleTypes;
import org.matsim.prepare.counts.ShortTermCountsCreator;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CreateSupply {

	public enum NetworkResolution {Low, Medium, High}

	private static final NetworkResolution networkResolution = NetworkResolution.High;

	private static final Path osmData = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/osm/germany-coarse_nordrhein-westfalen-2021-07-09_merged.osm.pbf");
	private static final Path ruhrShape = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/shp-files/ruhrgebiet_boundary/ruhrgebiet_boundary.shp");
	private static final Path heightData = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-05-29_RVR_Grid_10m.tif");

	private static final Path nrwShape = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/shp-files/nrw/dvg2bld_nw.shp");

	private static final Path gtfsData1 = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/gtfs/vrr_20211118_gtfs_vrr_shapes.zip");
	private static final Path gtfsData2 = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/gtfs/gtfs-nwl-20210215.zip");
	private static final Path gtfsData3 = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/gtfs/gtfs-schienenfernverkehr-de_2021-08-19.zip");

	private static final String gtfsDataDate1 = "2021-11-17";
	private static final String gtfsDataDate2 = "2021-02-04";
	private static final String gtfsDataDate3 = "2021-08-19";

	private static final String gtfsData1Prefix = "vrr";
	private static final String gtfsData2Prefix = "nwl";
	private static final String gtfsData3Prefix = "fern";

	private static final Path inputShapeNetwork1 = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-03-05_radwegeverbindungen_VM_Freizeitnetz/2021-03-05_radwegeverbindungen_VM_Freizeitnetz.shp");
	private static final Path inputShapeNetwork2 = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-03-05_radwegeverbindungen_VM_Knotenpunktnetz/2021-03-05_radwegeverbindungen_VM_Knotenpunktnetz.shp");
	private static final Path inputShapeNetwork3 = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-08-19_radwegeverbindungen_RRWN_Bestandsnetz/2021-08-19_RRWN_Bestandsnetz.shp");
	// for now, we will focus on the 'Bestandsnetz'. Once, we are done with calibration, we will also generate the network for the 'Zielnetz' by replacing the previous line with the following.
	// private static final Path inputShapeNetwork3 = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-08-19_radwegeverbindungen_RRWN_Bestandsnetz_Zielnetz/2021-08-19_RRWN_Bestandsnetz_Zielnetz.shp");

	private static final Path outputDirPublic = Paths.get("public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/");

	private static final Path outputDirCounts = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/");
	private static final Path longTermCountsRoot = Paths.get("shared-svn/projects/matsim-ruhrgebiet/original_data/counts/long_term_counts");
	private static final Path longTermCountsIdMapping = Paths.get("shared-svn/projects/matsim-ruhrgebiet/original_data/counts/mapmatching/countId-to-nodeId-long-term-counts.csv");
	private static final Path shortTermCountsRoot = Paths.get("shared-svn/projects/matsim-ruhrgebiet/original_data/counts/short_term_counts");
	private static final Path shortTermCountsIdMapping = Paths.get("shared-svn/projects/matsim-ruhrgebiet/original_data/counts/mapmatching/countId-to-nodeId-short-term-counts.csv");

	// we use UTM-32 as coordinate system
	private static final CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:25832");
	private static final Logger logger = LogManager.getLogger(CreateSupply.class);

	public static void main(String[] args) {

		String rootDirectory;

		if (args.length <= 0) {
			throw new IllegalArgumentException("Please set root directory");
		} else {
			rootDirectory = args[0];
		}

		new CreateSupply().run(Paths.get(rootDirectory));
	}

	private void run(Path rootDirectory) {

		// ----------------------------------------- Preparation ---------------------------------------

		var outputDir = rootDirectory.resolve(outputDirPublic);
		var outputDirForCounts = rootDirectory.resolve(outputDirCounts);

		OutputDirectoryLogging.catchLogEntries();
		try {
			OutputDirectoryLogging.initLoggingWithOutputDirectory(outputDir.resolve("").toString());
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		var ruhrGeometries = ShapeFileReader.getAllFeatures(rootDirectory.resolve(ruhrShape).toString()).stream()
				.map(feature -> (Geometry) feature.getDefaultGeometry())
				.collect(Collectors.toList());

		var nrwGeometries = ShapeFileReader.getAllFeatures(rootDirectory.resolve(nrwShape).toString()).stream()
				.map(feature -> (Geometry) feature.getDefaultGeometry())
				.collect(Collectors.toList());

		var nodeIdsToKeep = List.of(parseNodeIdsToKeep(rootDirectory.resolve(longTermCountsIdMapping)), parseNodeIdsToKeep(rootDirectory.resolve(shortTermCountsIdMapping))).stream()
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());

		// ----------------------------------------- Create Network ----------------------------------------------------

		var networkBuilder = new OsmBicycleReader.Builder()
				.setCoordinateTransformation(transformation)
				.setIncludeLinkAtCoordWithHierarchy((coord, level) -> isIncludeLink(coord, level, ruhrGeometries, nrwGeometries))
				.setPreserveNodeWithId(nodeIdsToKeep::contains)
				.setAfterLinkCreated((link, tags, direction) -> onLinkCreated(link))
				.addOverridingLinkProperties(OsmTags.SERVICE, new LinkProperties(10, 1, 10 / 3.6, 100 * 0.25, false)); // set hierarchy level for service roads to 10 to exclude them

		if (networkResolution == NetworkResolution.Low) {
			// exclude tracks and cycleways
			networkBuilder
					.addOverridingLinkProperties(OsmTags.TRACK, new LinkProperties(10, 1, 10 / 3.6, 100 * 0.25, false)) // set hierarchy level to 10 to exclude them
					.addOverridingLinkProperties(OsmTags.CYCLEWAY, new LinkProperties(10, 1, 10 / 3.6, 100 * 0.25, false)); // set hierarchy level to 10 to exclude them
		} else if (networkResolution == NetworkResolution.Medium) {
			// exclude tracks
			networkBuilder.addOverridingLinkProperties(OsmTags.TRACK, new LinkProperties(10, 1, 10 / 3.6, 100 * 0.25, false)); // set hierarchy level to 10 to exclude them
		} else if (networkResolution == NetworkResolution.High) {
			// nothing to exclude
		} else {
			throw new RuntimeException("Unknown network resolution. Aborting...");
		}

		var network = networkBuilder
				.build()
				.read(rootDirectory.resolve(osmData));

		//new NetworkWriter(network).write(rootDirectory.resolve(outputDir.resolve("metropole-ruhr-v1.0.network-only-OSM-and-PT_resolution" + networkResolution + ".xml.gz")).toString());

		// ----------------------------- Add bicycles and write network ------------------------------------------------

		Network network1 = new ShpToNetwork().run(rootDirectory.resolve(inputShapeNetwork1));
		new NetworkWriter(network1).write(outputDir.resolve("metropole-ruhr-v1.4.network-onlyBikeNetwork1.xml.gz").toString());
		new BikeNetworkMerger(network).mergeBikeHighways(network1);

		Network network2 = new ShpToNetwork().run(rootDirectory.resolve(inputShapeNetwork2));
		new NetworkWriter(network2).write(outputDir.resolve("metropole-ruhr-v1.4.network-onlyBikeNetwork2.xml.gz").toString());
		new BikeNetworkMerger(network).mergeBikeHighways(network2);

		Network network3 = new ShpToNetwork().run(rootDirectory.resolve(inputShapeNetwork3));
		new NetworkWriter(network3).write(outputDir.resolve("metropole-ruhr-v1.4.network-onlyBikeNetwork3.xml.gz").toString());
		new BikeNetworkMerger(network).mergeBikeHighways(network3);

		var cleaner = new MultimodalNetworkCleaner(network);
		cleaner.run(Set.of(TransportMode.car));
		cleaner.run(Set.of(TransportMode.ride));
		cleaner.run(Set.of(TransportMode.bike));

		//-------------------------- add height information to network -------------------------------------------------

		//TODO get correct transformation
		var elevationReader = new ElevationReader(List.of(rootDirectory.resolve(heightData).toString()), new IdentityTransformation());

		for (Node node : network.getNodes().values()) {
			var elevation = elevationReader.getElevationAt(node.getCoord());
			node.setCoord(new Coord(node.getCoord().getX(), node.getCoord().getY(), elevation));
		}

		String networkOut = outputDir.resolve("metropole-ruhr-v1.4.network_resolution" + networkResolution + ".xml.gz").toString();
		new NetworkWriter(network).write(networkOut);

		// --------------------------------------- Create Pt -----------------------------------------------------------

		new CreateTransitScheduleFromGtfs().execute(
				rootDirectory.resolve(gtfsData1).toString(), rootDirectory.resolve(gtfsData2).toString(), rootDirectory.resolve(gtfsData3).toString(),
				"--date", gtfsDataDate1, gtfsDataDate2, gtfsDataDate3,
				"--prefix", gtfsData1Prefix + "," + gtfsData2Prefix + "," + gtfsData3Prefix,
				"--target-crs", "EPSG:25832",
				"--network", networkOut,
				"--output", outputDir.toString(),
				"--name", "metropole-ruhr-v1.4"
		);

		// --------------------------------------- Create Counts -------------------------------------------------------

		var longTermCounts = new LongTermCountsCreator.Builder()
				.setLoggingFolder(outputDirForCounts + "/")
				.withNetwork(network)
				.withRootDir(rootDirectory.resolve(longTermCountsRoot).toString())
				.withIdMapping(rootDirectory.resolve(longTermCountsIdMapping).toString())
				.withStationIdsToOmit(5002L, 50025L)
				.useCountsWithinGeometry(rootDirectory.resolve(ruhrShape).toString())
				.build()
				.run();

		var shortTermCounts = new ShortTermCountsCreator.Builder()
				.setLoggingFolder(outputDirForCounts + "/")
				.withNetwork(network)
				.withRootDir(rootDirectory.resolve(shortTermCountsRoot).toString())
				.withIdMapping(rootDirectory.resolve(shortTermCountsIdMapping).toString())
				.withStationIdsToOmit(5002L, 50025L)
				.useCountsWithinGeometry(rootDirectory.resolve(ruhrShape).toString())
				.build()
				.run();

		CombinedCountsWriter.writeCounts(outputDirForCounts.resolve("metropole-ruhr-v1.4.counts.xml.gz"),
				longTermCounts.get(RawDataVehicleTypes.Pkw.toString()), shortTermCounts.get(RawDataVehicleTypes.Pkw.toString()));
	}

	private boolean isIncludeLink(Coord coord, int level, Collection<Geometry> ruhrGeometries, List<Geometry> nrwGeometries) {

		// include all motorways, trunk (and maybe also primary?) roads
		if (level <= LinkProperties.LEVEL_TRUNK) {
			return true;
		}

		// include all roads to secondary level within NRW
		if (level <= LinkProperties.LEVEL_SECONDARY && nrwGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(coord)))) {
			return true;
		}

		// within Ruhr area include all other streets bigger than tracks
		if (level <= LinkProperties.LEVEL_LIVING_STREET && ruhrGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(coord)))) {
			return true;
		}

		// within shape include all cycleways and all tracks (the designated bicycle tag is ignored)
		if (level <= 9 && ruhrGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(coord)))) {
			return true;
		}

		return false;
	}

	private void onLinkCreated(Link link) {
		// all links which allow cars should also allow ride
		if (link.getAllowedModes().contains(TransportMode.car)) {
			var modes = new HashSet<>(link.getAllowedModes());
			modes.add(TransportMode.ride);
			link.setAllowedModes(modes);
		}

		// all regular bike links should have an infrastructure speed factor of 0.5
		if (link.getAllowedModes().contains(TransportMode.bike)) {
			link.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 0.5);
		}
	}

	private Set<Long> parseNodeIdsToKeep(Path mapping) {

		Set<Long> result = new HashSet<>();
		try (FileReader reader = new FileReader(mapping.toString())) {
			for (CSVRecord record : CSVFormat.newFormat(';').withFirstRecordAsHeader().parse(reader)) {

				try {
					result.add(Long.parseLong(record.get(1)));
					result.add(Long.parseLong(record.get(2)));
				} catch (NumberFormatException e) {
					logger.error("Could not parse count-station: " + record.get(0));
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return result;
	}

}

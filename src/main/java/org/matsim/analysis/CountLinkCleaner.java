package org.matsim.analysis;

import org.geotools.api.data.SimpleFeatureReader;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;

import org.geotools.data.collection.ListFeatureCollection;
import java.io.File;
import java.util.*;

public class CountLinkCleaner {

	private static final GeometryFactory GF = new GeometryFactory();

	public static void main(String[] args) throws Exception {
		String networkFile = "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-1pct/009.output_network.xml.gz";
		String inputGpkg = "/Users/gregorr/Downloads/20251002_Übersicht_Zählstellen_Rad_LinkIDs.gpkg";
		String outputGpkg = "/Users/gregorr/Downloads/20251002_Übersicht_Zählstellen_Rad_LinkIDs_fixed.gpkg";

		Network network = NetworkUtils.readNetwork(networkFile);

		System.out.println("Network size: " + network.getLinks().size());

		GeoPackage inputPackage = new GeoPackage(new File(inputGpkg));
		inputPackage.init();

		FeatureEntry entry = inputPackage.features().get(0);

		List<SimpleFeature> cleanedFeatures = new ArrayList<>();

		try (SimpleFeatureReader reader = inputPackage.reader(entry, null, null)) {

			while (reader.hasNext()) {

				SimpleFeature feature = SimpleFeatureBuilder.copy(reader.next());

				Point point = (Point) feature.getDefaultGeometry();
				String linksString = (String) feature.getAttribute("LinksInMATSim");

				if (linksString != null && !linksString.isBlank()) {
					Set<Link> matchedLinks = parseLinks(linksString, network);
					Set<Link> cleaned = selectNearestComponent(point, matchedLinks);
					feature.setAttribute("LinksInMATSim", linksToString(cleaned));
				}

				cleanedFeatures.add(feature);
			}
		}

		SimpleFeatureType featureType = cleanedFeatures.get(0).getFeatureType();

		DefaultFeatureCollection cleanedCollection =
			new DefaultFeatureCollection(null, featureType);

		cleanedCollection.addAll(cleanedFeatures);

		GeoPackage outputPackage = new GeoPackage(new File(outputGpkg));
		outputPackage.init();

		FeatureEntry outEntry = new FeatureEntry();
		outEntry.setTableName(entry.getTableName());

		outputPackage.add(outEntry, cleanedCollection);

		outputPackage.close();
		inputPackage.close();
	}

	private static Set<Link> parseLinks(String string, Network network) {
		Set<Link> links = new HashSet<>();
		String[] ids = string.split(",");
		for (String s : ids) {
			s = s.trim();
			if (s.isEmpty()) continue;
			Link link = network.getLinks().get(Id.createLinkId(s));
			if (link != null) links.add(link);
		}
		return links;
	}

	private static String linksToString(Set<Link> links) {
		StringBuilder builder = new StringBuilder();
		boolean first = true;
		for (Link link : links) {
			if (!first) builder.append(",");
			builder.append(link.getId());
			first = false;
		}
		return builder.toString();
	}

	private static Set<Link> selectNearestComponent(Point point, Set<Link> links) {
		List<Set<Link>> components = connectedComponents(links);
		if (components.size() <= 1) return links;

		double bestDistance = Double.MAX_VALUE;
		Set<Link> best = null;
		for (Set<Link> component : components) {
			double distance = componentDistance(point, component);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = component;
			}
		}
		return best;
	}

	private static List<Set<Link>> connectedComponents(Set<Link> links) {
		List<Set<Link>> components = new ArrayList<>();
		Set<Link> visited = new HashSet<>();
		for (Link start : links) {
			if (visited.contains(start)) continue;
			Set<Link> component = new HashSet<>();
			Queue<Link> queue = new LinkedList<>();
			queue.add(start);
			visited.add(start);
			while (!queue.isEmpty()) {
				Link current = queue.poll();
				component.add(current);
				for (Link neighbour : neighbours(current, links)) {
					if (!visited.contains(neighbour)) {
						visited.add(neighbour);
						queue.add(neighbour);
					}
				}
			}
			components.add(component);
		}
		return components;
	}

	private static Set<Link> neighbours(Link link, Set<Link> links) {
		Set<Link> neighbours = new HashSet<>();
		Node from = link.getFromNode();
		Node to = link.getToNode();
		for (Link other : links) {
			if (other == link) continue;
			if (other.getFromNode().equals(from)
				|| other.getToNode().equals(from)
				|| other.getFromNode().equals(to)
				|| other.getToNode().equals(to)) {
				neighbours.add(other);
			}
		}
		return neighbours;
	}

	private static double componentDistance(Point point, Set<Link> component) {
		double minDistance = Double.MAX_VALUE;
		for (Link link : component) {
			LineString line = linkGeometry(link);
			double distance = DistanceOp.distance(point, line);
			if (distance < minDistance) {
				minDistance = distance;
			}
		}
		return minDistance;
	}

	private static LineString linkGeometry(Link link) {
		Coordinate from = new Coordinate(
			link.getFromNode().getCoord().getX(),
			link.getFromNode().getCoord().getY()
		);
		Coordinate to = new Coordinate(
			link.getToNode().getCoord().getX(),
			link.getToNode().getCoord().getY()
		);
		return GF.createLineString(new Coordinate[]{from, to});
	}
}

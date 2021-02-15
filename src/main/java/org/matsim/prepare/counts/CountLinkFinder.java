/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

/**
 *
 */
package org.matsim.prepare.counts;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * @author tschlenther
 *
 */
public class CountLinkFinder {

	private Network network;
	private List<Path> allFoundPaths = new ArrayList<Path>();
	private LeastCostPathCalculator dijkstra;

	/**
	 *
	 */
	public CountLinkFinder(Network net) {
		this.network = net;
		SimpleTravelDistanceDisutility travelDistance = new SimpleTravelDistanceDisutility();
		dijkstra = new DijkstraFactory().createPathCalculator(network, travelDistance, travelDistance);
	}


	public Id<Link> getFirstLinkOnTheWayFromNodeToNode(Node from, Node to) {

		Path path = dijkstra.calcLeastCostPath(from, to, 1.0, null, null);
		if (path == null || path.links.isEmpty()) {
			return null;
		}
		this.allFoundPaths.add(path);
		return path.links.get(0).getId();
	}

	public void writeNetworkThatShowsAllFoundPaths(String outputPath) {
		Network netCopy = NetworkUtils.createNetwork();
		NetworkFactory fac = netCopy.getFactory();
		for (Path currentPath : this.allFoundPaths) {
			for (int i = 0; i < currentPath.links.size(); i++) {
				Link l = currentPath.links.get(i);
				Id<Link> linkID = l.getId();
				if (netCopy.getLinks().containsKey(linkID)) {
					String id = linkID.toString() + "_v2";
					linkID = Id.createLinkId(id);
				}
				Node from = netCopy.getNodes().get(l.getFromNode().getId());
				if (from == null) {
					from = fac.createNode(l.getFromNode().getId(), l.getFromNode().getCoord());
					netCopy.addNode(from);
				}
				Node to = netCopy.getNodes().get(l.getToNode().getId());
				if (to == null) {
					to = fac.createNode(l.getToNode().getId(), l.getToNode().getCoord());
					netCopy.addNode(to);
				}
				Link linkCopy = fac.createLink(linkID, from, to);
				linkCopy.setCapacity(i);
				linkCopy.setLength(l.getLength());
				netCopy.addLink(linkCopy);
			}
		}
		NetworkWriter writer = new NetworkWriter(netCopy);
		writer.write(outputPath);
	}

	public int getNrOfFoundPaths() {
		return this.allFoundPaths.size();
	}

	private static final class SimpleTravelDistanceDisutility implements TravelTime, TravelDisutility {

		/**
		 *
		 * @param link
		 * @return travel distance
		 */
		private double calcTravelDistance(Link link) {
			return link.getLength();
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
			return this.calcTravelDistance(link);
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return this.calcTravelDistance(link);
		}

		@Override
		public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
			return this.calcTravelDistance(link);
		}

	}

}



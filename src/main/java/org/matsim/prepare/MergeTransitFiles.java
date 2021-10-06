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
package org.matsim.prepare;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;


/**
 * 
 * Merges several schedule files...
 * 
 * @author ikaddoura
 *
 */
@Deprecated
public class MergeTransitFiles {
		
	public static void mergeVehicles(Vehicles baseTransitVehicles, Vehicles transitVehicles) {
		for (VehicleType vehicleType : transitVehicles.getVehicleTypes().values()) {
			VehicleType vehicleType2 = baseTransitVehicles.getFactory().createVehicleType(vehicleType.getId());
			vehicleType2.setNetworkMode(vehicleType.getNetworkMode());
			vehicleType2.setPcuEquivalents(vehicleType.getPcuEquivalents());
			vehicleType2.setDescription(vehicleType.getDescription());
			vehicleType2.getCapacity().setSeats(vehicleType.getCapacity().getSeats());
			
			baseTransitVehicles.addVehicleType(vehicleType2);
		}
		
		for (Vehicle vehicle : transitVehicles.getVehicles().values()) {
			Vehicle vehicle2 = baseTransitVehicles.getFactory().createVehicle(vehicle.getId(), vehicle.getType());
			baseTransitVehicles.addVehicle(vehicle2);
		}
		
	}
	
	/**
	 * Merges two schedules into one, by copying all stops, lines and so on from the addSchedule to the baseSchedule.
	 *
	 */
	public static void mergeSchedule(final TransitSchedule baseSchedule, final String id, final TransitSchedule addSchedule) {
		
		for (TransitStopFacility stop : addSchedule.getFacilities().values()) {
			Id<TransitStopFacility> newStopId = Id.create(id + "_" + stop.getId(), TransitStopFacility.class);
			TransitStopFacility stop2 = baseSchedule.getFactory().createTransitStopFacility(newStopId, stop.getCoord(), stop.getIsBlockingLane());
			stop2.setLinkId(stop.getLinkId());
			stop2.setName(stop.getName());
			baseSchedule.addStopFacility(stop2);
		}
		for (TransitLine line : addSchedule.getTransitLines().values()) {
			TransitLine line2 = baseSchedule.getFactory().createTransitLine(Id.create(id + "_" + line.getId(), TransitLine.class));
			
			for (TransitRoute route : line.getRoutes().values()) {
				
				List<TransitRouteStop> stopsWithNewIDs = new ArrayList<>();
				for (TransitRouteStop routeStop : route.getStops()) {
					Id<TransitStopFacility> newFacilityId = Id.create(id + "_" + routeStop.getStopFacility().getId(), TransitStopFacility.class);
					TransitStopFacility stop = baseSchedule.getFacilities().get(newFacilityId);
					stopsWithNewIDs.add(baseSchedule.getFactory().createTransitRouteStop(stop , routeStop.getArrivalOffset().seconds(), routeStop.getDepartureOffset().seconds()));
				}
				
				TransitRoute route2 = baseSchedule.getFactory().createTransitRoute(route.getId(), route.getRoute(), stopsWithNewIDs, route.getTransportMode());
				route2.setDescription(route.getDescription());
				
				for (Departure departure : route.getDepartures().values()) {
					Departure departure2 = baseSchedule.getFactory().createDeparture(departure.getId(), departure.getDepartureTime());
					departure2.setVehicleId(departure.getVehicleId());
					route2.addDeparture(departure2);
				}
				line2.addRoute(route2);
			}
			baseSchedule.addTransitLine(line2);
		}
	}
}

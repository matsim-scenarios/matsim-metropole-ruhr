
/* *********************************************************************** *
 * project: org.matsim.*
 * OpenBerlinIntermodalPtDrtRouterAnalysisModeIdentifier.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
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

 package org.matsim.run;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.TransportPlanningMainModeIdentifier;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.router.AnalysisMainModeIdentifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Based on {@link TransportPlanningMainModeIdentifier}
 * 
 * @author nagel / gleich
 *
 */
public final class IntermodalPtAnalysisModeIdentifier implements AnalysisMainModeIdentifier {
	private final List<String> modeHierarchy = new ArrayList<>() ;
	private static final Logger log = LogManager.getLogger(IntermodalPtAnalysisModeIdentifier.class);
	public static final String ANALYSIS_MAIN_MODE_PT_WITH_BIKE_USED_FOR_ACCESS_OR_EGRESS = "pt_w_bike_used";
	public static final String ANALYSIS_MAIN_MODE_PT_WITH_CAR_USED_FOR_ACCESS_OR_EGRESS = "pt_w_car_used";
	public static final String ANALYSIS_MAIN_MODE_PT_WITH_BIKE_AND_CAR_USED_FOR_ACCESS_OR_EGRESS = "pt_w_bike_and_car_used";

	@Inject
	public IntermodalPtAnalysisModeIdentifier() {
		modeHierarchy.add( TransportMode.walk ) ;
		modeHierarchy.add( TransportMode.bike );
		modeHierarchy.add( TransportMode.ride ) ;
		modeHierarchy.add( TransportMode.car ) ;
		modeHierarchy.add( TransportMode.pt ) ;
		modeHierarchy.add( "freight" );
		
		// NOTE: This hierarchical stuff is not so great: is park-n-ride a car trip or a pt trip?  Could weigh it by distance, or by time spent
		// in respective mode.  Or have combined modes as separate modes.  In any case, can't do it at the leg level, since it does not
		// make sense to have the system calibrate towards something where we have counted the car and the pt part of a multimodal
		// trip as two separate trips. kai, sep'16
	}

	@Override public String identifyMainMode( List<? extends PlanElement> planElements ) {
		int mainModeIndex = -1 ;
		List<String> modesFound = new ArrayList<>();
		for ( PlanElement pe : planElements ) {
			int index;
			String mode;
			if ( pe instanceof Leg ) {
				Leg leg = (Leg) pe ;
				mode = leg.getMode();
			} else {
				continue;
			}
			if (mode.equals(TransportMode.non_network_walk)) {
				// skip, this is only a helper mode in case walk is routed on the network
				continue;
			}
			modesFound.add(mode);
			index = modeHierarchy.indexOf( mode ) ;
			if ( index < 0 ) {
				throw new RuntimeException("unknown mode=" + mode ) ;
			}
			if ( index > mainModeIndex ) {
				mainModeIndex = index ;
			}
		}
		if (mainModeIndex == -1) {
			throw new RuntimeException("no main mode found for trip " + planElements.toString() ) ;
		}
		
		String mainMode = modeHierarchy.get( mainModeIndex ) ;
		// differentiate pt monomodal/intermodal
		if (mainMode.equals(TransportMode.pt)) {
			boolean bikeUsed = false;
			boolean carUsed = false;
			for (String modeFound: modesFound) {
				if (modeFound.equals(TransportMode.pt)) {
					continue;
				} else if (modeFound.equals(TransportMode.walk)) {
					continue;
				} else if (modeFound.equals(TransportMode.bike)) {
					bikeUsed = true;
				} else if (modeFound.equals(TransportMode.car)) {
					carUsed = true;
				} else {
					log.error("unknown intermodal pt trip: " + planElements.toString());
					throw new RuntimeException("unknown intermodal pt trip");
				}
			}
			
			if (bikeUsed) {
				if (carUsed) {
					return this.ANALYSIS_MAIN_MODE_PT_WITH_BIKE_AND_CAR_USED_FOR_ACCESS_OR_EGRESS;
				} else {
					return this.ANALYSIS_MAIN_MODE_PT_WITH_BIKE_USED_FOR_ACCESS_OR_EGRESS;
				}
			} else {
				if (carUsed) {
					return this.ANALYSIS_MAIN_MODE_PT_WITH_CAR_USED_FOR_ACCESS_OR_EGRESS;
				} else {
					return TransportMode.pt;
				}
			}
			
		} else {
			return mainMode;
		}
	}
}

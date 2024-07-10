/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2013 by the members listed in the COPYING,        *
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

package org.matsim.analysis;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math.stat.StatUtils;
//import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.dvrp.vrpagent.VrpAgentLogic;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.collections.Tuple;


/**
 * This analysis computes the effective value of travel time savings (VTTS) for each agent and each trip.
 * The basic idea is to repeat the scoring for an earlier arrival time (or shorter travel time) and to compute the score difference.
 * The score difference is used to compute the agent's trip-specific VTTS applying a linearization.
 *
 * @author ikaddoura
 *
 */
public class VTTSHandler implements ActivityStartEventHandler, ActivityEndEventHandler, PersonDepartureEventHandler, TransitDriverStartsEventHandler {
	static final double UNDEFINED_TIME = Double.NEGATIVE_INFINITY;

	//TODO Reimplement logger
//	private final static Logger log = Logger.getLogger(VTTSHandler.class);

	//Amount of incomplete or corrupted computations
	private static int incompletedPlanWarning = 0; 	// Amount of agents, which did not complete their activity after end of the simulation
	private static int noCarVTTSWarning = 0; 		// TODO #!
	private static int noTripVTTSWarning = 0; 		// TODO #!
	private static int noTripNrWarning = 0; 		// TODO #!

	private final Scenario scenario;
	private int currentIteration;

	//Persons, acts and modes that should not be considered for VTTS
	private final Set<Id<Person>> personIdsToBeIgnored = new HashSet<>();
	private final String[] stageActivitiesSubStrings;
	private final String[] modesToBeSkipped;

	private final Set<Id<Person>> departedPersonIds = new HashSet<>();							//Map of all agents (which are currently travelling) #!
	private final Map<Id<Person>, Double> personId2currentActivityStartTime = new HashMap<>();	//Map of all agents (which are in an act) to the act-start-time
	private final Map<Id<Person>, Double> personId2firstActivityEndTime = new HashMap<>();		//Map of all agents (which are/were in an act) to their first activity
	private final Map<Id<Person>, String> personId2currentActivityType = new HashMap<>();		//Map of all agents (which are in an act) to the act-type
	private final Map<Id<Person>, String> personId2firstActivityType = new HashMap<>();			//Map of all agents (which are/were in an act) to the type of their first activity
	private final Map<Id<Person>, Integer> personId2currentTripNr = new HashMap<>();			//Map of all agents (which are currently travelling) to the index of their current trip
	private final Map<Id<Person>, String> personId2currentTripMode = new HashMap<>();			//Map of all agents (which are currently travelling) to the type of their current trip

	private final Map<Id<Person>, List<Double>> personId2VTTSh = new HashMap<>();				//Map of all persons to the VTTS per hour (total average of all trip-VTTS)
	private final Map<Id<Person>, Map<Integer, Double>> personId2TripNr2VTTSh = new HashMap<>();//Map of all persons to the VTTS per hour of a single trip
	private final Map<Id<Person>, Map<Integer, String>> personId2TripNr2Mode = new HashMap<>(); //Map of all persons to the mode of a trip

	// to get the trip number for any given time
	private final Map<Id<Person>, Map<Integer, Double>> personId2TripNr2DepartureTime = new HashMap<>();

	private final double defaultVTTS_moneyPerHour; // for the car mode! TODO #?

	/**
	 * Returns an {@link EventHandler}-child for the VTTS-computation of a scenario for all agents.
	 * After reading an EventFile the <i>print</i>-methods ({@link #printVTTS}, {@link #printCarVTTS},
	 * {@link #printAvgVTTSperPerson}, {@link #printVTTSstatistics} ) can be used to save the results into a csv-file.
	 * @param scenario Scenario with config and population.
	 *                 PlanCalcScore-module or {@link SnzActivities#addScoringParams} should be included. Otherwise, the calculation may crash!
	 * @param helpLegModes List of modes to ignore during the VTTS calculation
	 * @param stageActivitySubString Substring of all activities to ignore during the VTTS calculation
	 */
	public VTTSHandler(Scenario scenario, String[] helpLegModes, String stageActivitySubString) {
		if (scenario.getConfig().scoring().getMarginalUtilityOfMoney() == 0.) {
//			log.warn("The marginal utility of money must not be 0.0. The VTTS is computed in Money per Time.");
		}
		this.modesToBeSkipped = helpLegModes;
		this.stageActivitiesSubStrings = new String[]{stageActivitySubString};
		this.scenario = scenario;
		this.currentIteration = Integer.MIN_VALUE;
		this.defaultVTTS_moneyPerHour =
				(this.scenario.getConfig().scoring().getPerforming_utils_hr()
				+ this.scenario.getConfig().scoring().getModes().get( TransportMode.car ).getMarginalUtilityOfTraveling() * (-1.0)
				) / this.scenario.getConfig().scoring().getMarginalUtilityOfMoney();
	}

	/**
	 * Returns an {@link EventHandler}-child for the VTTS-computation of a scenario for all agents.
	 * After reading an EventFile the <i>print</i>-methods ({@link #printVTTS}, {@link #printCarVTTS},
	 * {@link #printAvgVTTSperPerson}, {@link #printVTTSstatistics} ) can be used to save the results into a csv-file.
	 * @param scenario Scenario with config and population.
	 *                 PlanCalcScore-module or {@link SnzActivities#addScoringParams} should be included. Otherwise, the calculation may crash!
	 * @param helpLegModes List of modes to ignore during the VTTS calculation
	 * @param stageActivitiesSubStrings If an activity contains one of these substrings, it will be ignored (For Standard MATSim configuration use {@code "interaction"}).
	 */
	public VTTSHandler(Scenario scenario, String[] helpLegModes, String[] stageActivitiesSubStrings) {
		if (scenario.getConfig().scoring().getMarginalUtilityOfMoney() == 0.) {
//			log.warn("The marginal utility of money must not be 0.0. The VTTS is computed in Money per Time.");
		}
		this.modesToBeSkipped = helpLegModes;
		this.stageActivitiesSubStrings = stageActivitiesSubStrings;
		this.scenario = scenario;
		this.currentIteration = Integer.MIN_VALUE;
		this.defaultVTTS_moneyPerHour =
			(this.scenario.getConfig().scoring().getPerforming_utils_hr()
				+ this.scenario.getConfig().scoring().getModes().get( TransportMode.car ).getMarginalUtilityOfTraveling() * (-1.0)
			) / this.scenario.getConfig().scoring().getMarginalUtilityOfMoney();
	}

	// ===== Parsing-methods =========================================================================================================================

	@Override
	public void reset(int iteration) {
		this.currentIteration = iteration;
//		log.warn("Resetting VTTS information from previous iteration.");

		incompletedPlanWarning = 0;
		noCarVTTSWarning = 0;

		this.personIdsToBeIgnored.clear();
		this.departedPersonIds.clear();
		this.personId2currentActivityStartTime.clear();
		this.personId2firstActivityEndTime.clear();
		this.personId2currentActivityType.clear();
		this.personId2firstActivityType.clear();
		this.personId2currentTripNr.clear();
		this.personId2currentTripMode.clear();

		this.personId2VTTSh.clear();
		this.personId2TripNr2VTTSh.clear();
		this.personId2TripNr2Mode.clear();

		this.personId2TripNr2DepartureTime.clear();
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		personIdsToBeIgnored.add(event.getDriverId());
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		if (isModeToBeSkipped(event.getLegMode()) || this.personIdsToBeIgnored.contains(event.getPersonId())) return;

		this.departedPersonIds.add(event.getPersonId());
		this.personId2currentTripMode.put(event.getPersonId(), event.getLegMode());

		if (this.personId2currentTripNr.containsKey(event.getPersonId())){
			this.personId2currentTripNr.put(event.getPersonId(), this.personId2currentTripNr.get(event.getPersonId()) + 1);
		} else {
			this.personId2currentTripNr.put(event.getPersonId(), 1);
		}

		if (this.personId2TripNr2DepartureTime.containsKey(event.getPersonId())) {
			this.personId2TripNr2DepartureTime.get(event.getPersonId()).put(this.personId2currentTripNr.get(event.getPersonId()), event.getTime());
		} else {
			Map<Integer, Double> tripNr2departureTime = new HashMap<>();
			tripNr2departureTime.put(this.personId2currentTripNr.get(event.getPersonId()), event.getTime());
			this.personId2TripNr2DepartureTime.put(event.getPersonId(), tripNr2departureTime);

		}
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (isActivityToBeSkipped(event.getActType()) || this.personIdsToBeIgnored.contains(event.getPersonId())) return;

		this.personId2currentActivityStartTime.put(event.getPersonId(), event.getTime());
		this.personId2currentActivityType.put(event.getPersonId(), event.getActType());
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		if (event.getActType().equals(VrpAgentLogic.BEFORE_SCHEDULE_ACTIVITY_TYPE)) this.personIdsToBeIgnored.add(event.getPersonId());
		if (isActivityToBeSkipped(event.getActType()) || this.personIdsToBeIgnored.contains(event.getPersonId())) return;

		if (this.personId2currentActivityStartTime.containsKey(event.getPersonId())) {
			// This is not the first activity...

			// ... now process all congestion events thrown during the trip to the activity which has just ended, ...
			computeVTTS(event.getPersonId(), event.getTime());

			// ... update the status of the 'current' activity...
			this.personId2currentActivityType.remove(event.getPersonId());
			this.personId2currentActivityStartTime.remove(event.getPersonId());

			// ... and remove all processed congestion events.
			this.departedPersonIds.remove(event.getPersonId());

		} else {
			// This is the first activity. The first and last / overnight activity are / is considered in a final step.
			// Therefore, the relevant information has to be stored.
			this.personId2firstActivityEndTime.put(event.getPersonId(), event.getTime());
			this.personId2firstActivityType.put(event.getPersonId(), event.getActType());
		}
	}

	// ===== Computation-methods =====================================================================================================================

	/**
	 * This method has to be called after parsing the events. Here, the last / overnight activity is taken into account.
	 */
	public void computeFinalVTTS() {
		for (Id<Person> affectedPersonId : this.departedPersonIds) {
			computeVTTS(affectedPersonId);
		}
	}

	/**
	 * Computes the Disutility of Activity-Delay (of an agent) using linearization. The linear function uses the delta of the current score
	 * and the score of 1 second earlier arrival as slope. The scoring is done for the act, that the agent is currently doing. Call this method when
	 * an activity is ending ({@link ActivityEndEvent}). <br>
	 * <i>NOTE: Since the scoring-function behaves logarithmic this approximated value is higher than the actual disutility for time savings > 1s!</i>
	 * @param personId Person to compute the DelayDisutility for
	 * @param activityEndTime End time of the current activity
	 * @return Disutility of Activity-Delay (unit: disutility/second)
	 */
	private double computeDelayDisutility(Id<Person> personId, double activityEndTime){
		//TODO Replace deprecated functions used in this method

		//Get subpopulation of agent
		String subpop = null;
		if (this.scenario.getPopulation().getPersons().get(personId) != null &&
			this.scenario.getPopulation().getPersons().get(personId).getAttributes().getAttribute(this.scenario.getConfig().plans().getSubpopulationAttributeName()) != null) {
			subpop = (String) this.scenario.getPopulation().getPersons().get(personId).getAttributes().getAttribute(this.scenario.getConfig().plans().getSubpopulationAttributeName());
		}

		//Prepare MarginalSumScoringFunction
		final MarginalSumScoringFunction marginalSumScoringFunction =
			new MarginalSumScoringFunction(
				new ScoringParameters.Builder(scenario.getConfig().scoring(), scenario.getConfig().scoring().getScoringParameters(subpop), scenario.getConfig().scenario()).build());

		//Make sure, that the activityEndTime is defined
		if (activityEndTime == UNDEFINED_TIME) {
			// The end time is undefined, which means that someone called the wrong method. We print a log-warning and call the corresponding method.
			// TODO log warning
			return computeDelayDisutility(personId);
		}

		//Handle the current activity
		Activity activity = PopulationUtils.createActivityFromLinkId(this.personId2currentActivityType.get(personId), null);
		activity.setStartTime(this.personId2currentActivityStartTime.get(personId));
		activity.setEndTime(activityEndTime);

		return marginalSumScoringFunction.getNormalActivityDelayDisutility(activity, 1.0);
	}

	/**
	 * Computes the Disutility of Activity-Delay (of an agent) using linearization. The linear function uses the delta of the current score
	 * and the score of 1 second earlier arrival as slope. The scoring is done for the act, that the agent is currently doing. Call this method for
	 * all agents, still in an activity after the mobsim ({@link AfterMobsimEvent}). <br>
	 * <i>NOTE: Since the scoring-function behaves logarithmic this approximated value is higher than the actual disutility for time savings > 1s!</i>
	 * @param personId Person to compute the DelayDisutility for
	 * @return Disutility of Activity-Delay (unit: disutility/second)
	 */
	private double computeDelayDisutility(Id<Person> personId){
		//TODO Replace deprecated functions used in this method

		//Get subpopulation of agent
		String subpop = null;
		if (this.scenario.getPopulation().getPersons().get(personId) != null &&
			this.scenario.getPopulation().getPersons().get(personId).getAttributes().getAttribute(this.scenario.getConfig().plans().getSubpopulationAttributeName()) != null) {
			subpop = (String) this.scenario.getPopulation().getPersons().get(personId).getAttributes().getAttribute(this.scenario.getConfig().plans().getSubpopulationAttributeName());
		}

		//Prepare MarginalSumScoringFunction
		final MarginalSumScoringFunction marginalSumScoringFunction =
			new MarginalSumScoringFunction(
				new ScoringParameters.Builder(scenario.getConfig().scoring(), scenario.getConfig().scoring().getScoringParameters(subpop), scenario.getConfig().scenario()).build());

		//Handle the first and last OR overnight activity. This is figured out by the scoring function itself (depending on the activity types).
		Activity activityMorning = PopulationUtils.createActivityFromLinkId(this.personId2firstActivityType.get(personId), null);
		activityMorning.setEndTime(this.personId2firstActivityEndTime.get(personId));

		Activity activityEvening = PopulationUtils.createActivityFromLinkId(this.personId2currentActivityType.get(personId), null);
		activityEvening.setStartTime(this.personId2currentActivityStartTime.get(personId));

		return marginalSumScoringFunction.getOvernightActivityDelayDisutility(activityMorning, activityEvening, 1.0);
	}

	/**
	 * Compute the VTTS values for an agent. Results are saved/updated in {@link #personId2VTTSh}, {@link #personId2TripNr2VTTSh}.
	 * This method should be executed every time an agent ends its activity (e.g. by attaching it to an {@link ActivityEndEventHandler}).
	 * @param personId ID of the agent to process
	 * @param activityEndTime The end time of the activity from {@link ActivityEndEvent}.
	 */
	private void computeVTTS(Id<Person> personId, double activityEndTime) {
		if (this.personId2currentTripMode.get(personId) == null) {
			// No mode stored for this person and trip. This indicates that the current trip mode was skipped.
			// Thus, do not compute any VTTS for this trip.
			return;
		}

		double activityDelayDisutilityOneSec = 0.;

		// First, check if the plan completed is completed, i.e. if the agent has arrived at an activity
		if (this.personId2currentActivityType.containsKey(personId) && this.personId2currentActivityStartTime.containsKey(personId)) {
			activityDelayDisutilityOneSec = computeDelayDisutility(personId, activityEndTime); //TODO will crash on NOT_DEFINED
		} else {
			// No, there is no information about the current activity which indicates that the trip (with the delay) was not completed.

			//Only print 10 log warnings to prevent spam
			if (incompletedPlanWarning <= 10) {
/*
				log.warn("Agent " + personId + " has not yet completed the plan/trip (the agent is probably stucking). Cannot compute the disutility of being late at this activity. "
						+ "Something like the disutility of not arriving at the activity is required. Try to avoid this by setting a smaller stuck time period.");
				log.warn("Setting the disutilty of being delayed on the previous trip using the config parameters; assuming the marginal disutility of being delayed at the (hypothetical) activity to be equal to beta_performing: " + this.scenario.getConfig().planCalcScore().getPerforming_utils_hr());
*/

				if (incompletedPlanWarning == 10) {
//						log.warn(Gbl.FUTURE_SUPPRESSED);
				}
				incompletedPlanWarning++;
			}
			//Use beta_perf as compromise solution (This may reduce the accuracy of the computed VTTS!)
			activityDelayDisutilityOneSec = (1.0 / 3600.) * this.scenario.getConfig().scoring().getPerforming_utils_hr();
		}

		// Calculate the agent's trip delay disutility.
		// (Could be done similar to the activity delay disutility. As long as it is computed linearly, the following should be okay.)
		String mode = this.personId2currentTripMode.get(personId);
		double marginalUtilityOfTraveling = 0.;
		if (this.scenario.getConfig().scoring().getModes().get(mode) != null) {
			marginalUtilityOfTraveling = this.scenario.getConfig().scoring().getModes().get(mode).getMarginalUtilityOfTraveling();
		} else {
/*
			log.warn("Could not identify the marginal utility of traveling for mode " + mode + ". "
					+ "Setting this value to zero. (Probably using subpopulations...)");
*/
		}
		double tripDelayDisutilityOneSec = (1.0 / 3600.) * marginalUtilityOfTraveling * (-1);

		// Translate the disutility into monetary units.
		double delayCostPerSec_usingActivityDelayOneSec = (activityDelayDisutilityOneSec + tripDelayDisutilityOneSec) / this.scenario.getConfig().scoring().getMarginalUtilityOfMoney();

		// Store the VTTS for analysis purposes
		if (this.personId2VTTSh.containsKey(personId)) {
			//Update the entries

			this.personId2VTTSh.get(personId).add(delayCostPerSec_usingActivityDelayOneSec * 3600);
			this.personId2TripNr2VTTSh.get(personId).put(this.personId2currentTripNr.get(personId), delayCostPerSec_usingActivityDelayOneSec * 3600);
			this.personId2TripNr2Mode.get(personId).put(this.personId2currentTripNr.get(personId), this.personId2currentTripMode.get(personId));

		} else {
			//Create new entries in all maps

			List<Double> vTTSh = new ArrayList<>();
			vTTSh.add(delayCostPerSec_usingActivityDelayOneSec * 3600.);
			this.personId2VTTSh.put(personId, vTTSh);

			Map<Integer, Double> tripNr2VTTSh = new HashMap<>();
			tripNr2VTTSh.put(this.personId2currentTripNr.get(personId), delayCostPerSec_usingActivityDelayOneSec * 3600.);
			this.personId2TripNr2VTTSh.put(personId, tripNr2VTTSh);

			Map<Integer, String> tripNr2Mode = new HashMap<>();
			tripNr2Mode.put(this.personId2currentTripNr.get(personId), this.personId2currentTripMode.get(personId));
			this.personId2TripNr2Mode.put(personId, tripNr2Mode);
		}
	}

	/**
	 * Compute the VTTS values for an agent. Results are saved/updated in {@link #personId2VTTSh}, {@link #personId2TripNr2VTTSh}.
	 * This method should be executed at the end of the mobsim ({@link AfterMobsimEvent}).
	 * @param personId ID of the agent to process
	 */
	private void computeVTTS(Id<Person> personId) {
		if (this.personId2currentTripMode.get(personId) == null) {
			// No mode stored for this person and trip. This indicates that the current trip mode was skipped.
			// Thus, do not compute any VTTS for this trip.
			return;
		}

		double activityDelayDisutilityOneSec = 0.;

		// First, check if the plan completed is completed, i.e. if the agent has arrived at an activity
		if (this.personId2currentActivityType.containsKey(personId) && this.personId2currentActivityStartTime.containsKey(personId)) {
			activityDelayDisutilityOneSec = computeDelayDisutility(personId);
		} else {
			// No, there is no information about the current activity which indicates that the trip (with the delay) was not completed.

			if (incompletedPlanWarning <= 10) {
/*
				log.warn("Agent " + personId + " has not yet completed the plan/trip (the agent is probably stucking). Cannot compute the disutility of being late at this activity. "
						+ "Something like the disutility of not arriving at the activity is required. Try to avoid this by setting a smaller stuck time period.");
				log.warn("Setting the disutilty of being delayed on the previous trip using the config parameters; assuming the marginal disutility of being delayed at the (hypothetical) activity to be equal to beta_performing: " + this.scenario.getConfig().planCalcScore().getPerforming_utils_hr());
*/

				if (incompletedPlanWarning == 10) {
//						log.warn(Gbl.FUTURE_SUPPRESSED);
				}
				incompletedPlanWarning++;
			}
			//Use beta_perf as compromise solution (This may reduce the accuracy of the computed VTTS!)
			activityDelayDisutilityOneSec = (1.0 / 3600.) * this.scenario.getConfig().scoring().getPerforming_utils_hr();
		}

		// Calculate the agent's trip delay disutility.
		// (Could be done similar to the activity delay disutility. As long as it is computed linearly, the following should be okay.)
		String mode = this.personId2currentTripMode.get(personId);
		double marginalUtilityOfTraveling = 0.;
		if (this.scenario.getConfig().scoring().getModes().get(mode) != null) {
			marginalUtilityOfTraveling = this.scenario.getConfig().scoring().getModes().get(mode).getMarginalUtilityOfTraveling();
		} else {
/*
			log.warn("Could not identify the marginal utility of traveling for mode " + mode + ". "
					+ "Setting this value to zero. (Probably using subpopulations...)");
*/
		}
		double tripDelayDisutilityOneSec = (1.0 / 3600.) * marginalUtilityOfTraveling * (-1);

		// Translate the disutility into monetary units.
		double delayCostPerSec_usingActivityDelayOneSec = (activityDelayDisutilityOneSec + tripDelayDisutilityOneSec) / this.scenario.getConfig().scoring().getMarginalUtilityOfMoney();

		// Store the VTTS for analysis purposes
		if (this.personId2VTTSh.containsKey(personId)) {
			//Update the entries

			this.personId2VTTSh.get(personId).add(delayCostPerSec_usingActivityDelayOneSec * 3600);
			this.personId2TripNr2VTTSh.get(personId).put(this.personId2currentTripNr.get(personId), delayCostPerSec_usingActivityDelayOneSec * 3600);
			this.personId2TripNr2Mode.get(personId).put(this.personId2currentTripNr.get(personId), this.personId2currentTripMode.get(personId));

		} else {
			//Create new entries in all maps

			List<Double> vTTSh = new ArrayList<>();
			vTTSh.add(delayCostPerSec_usingActivityDelayOneSec * 3600.);
			this.personId2VTTSh.put(personId, vTTSh);

			Map<Integer, Double> tripNr2VTTSh = new HashMap<>();
			tripNr2VTTSh.put(this.personId2currentTripNr.get(personId), delayCostPerSec_usingActivityDelayOneSec * 3600.);
			this.personId2TripNr2VTTSh.put(personId, tripNr2VTTSh);

			Map<Integer, String> tripNr2Mode = new HashMap<>();
			tripNr2Mode.put(this.personId2currentTripNr.get(personId), this.personId2currentTripMode.get(personId));
			this.personId2TripNr2Mode.put(personId, tripNr2Mode);
		}
	}

	// ===== Print-methods ===========================================================================================================================

	/**
	 * Prints VTTS per hour for every person and trip (money/hour) as a csv-file in the following format: {@code person Id;TripNr;Mode;VTTS (money/hour)}
	 * @param fileName Output-file path
	 */
	public void printVTTS(String fileName) {

		File file = new File(fileName);

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));
			bw.write("person Id;TripNr;Mode;VTTS (money/hour)");
			bw.newLine();

			for (Id<Person> personId : this.personId2TripNr2VTTSh.keySet()){
				for (Integer tripNr : this.personId2TripNr2VTTSh.get(personId).keySet()){
					bw.write(personId + ";" + tripNr + ";" + this.personId2TripNr2Mode.get(personId).get(tripNr) + ";" + this.personId2TripNr2VTTSh.get(personId).get(tripNr));
					bw.newLine();
				}
			}

			bw.close();
//			log.info("Output written to " + fileName);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Prints VTTS per hour for every person and trip (money/hour), filtered by {@link TransportMode#car} mode as a csv-file in the following format:
	 * {@code person Id;TripNr;Mode;VTTS (money/hour)}
	 * @param fileName Output-file path
	 */
	public void printCarVTTS(String fileName) {
		printVTTS(fileName, TransportMode.car);
	}

	/**
	 * Prints VTTS per hour for every agent and trip (money/hour), filtered by the given mode as a csv-file in the following format:
	 * {@code person Id;TripNr;Mode;VTTS (money/hour)}
	 * @param fileName Output-file path
	 * @param mode
	 */
	public void printVTTS(String fileName, String mode) {

		File file = new File(fileName);

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));
			bw.write("person Id;TripNr;Mode;VTTS (money/hour)");
			bw.newLine();

			for (Id<Person> personId : this.personId2TripNr2VTTSh.keySet()){
				for (Integer tripNr : this.personId2TripNr2VTTSh.get(personId).keySet()){
					if (this.personId2TripNr2Mode.get(personId).get(tripNr).equals(mode)) {
						bw.write(personId + ";" + tripNr + ";" + this.personId2TripNr2Mode.get(personId).get(tripNr) + ";" + this.personId2TripNr2VTTSh.get(personId).get(tripNr));
						bw.newLine();
					}
				}
			}

			bw.close();
//			log.info("Output written to " + fileName);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Prints the average VTTS of an agent for all trips (money/hour) as a csv-file  in the following format: {@code person Id;VTTS (money/hour)}
	 * @param fileName Output-file path
	 */
	public void printAvgVTTSperPerson(String fileName) {

		File file = new File(fileName);

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));
			bw.write("person Id;VTTS (money/hour)");
			bw.newLine();

			for (Id<Person> personId : this.personId2VTTSh.keySet()){
				double vttsSum = 0.;
				double counter = 0;
				for (Double vTTS : this.personId2VTTSh.get(personId)){
					vttsSum = vttsSum + vTTS;
					counter++;
				}
				bw.write(personId + ";" + (vttsSum / counter) );
				bw.newLine();
			}

			bw.close();
//			log.info("Output written to " + fileName);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * TODO #?
	 * @param fileName
	 * @param mode
	 * @param fromToTime_sec
	 */
	public void printVTTSstatistics(String fileName, String mode, Tuple<Double, Double> fromToTime_sec) {
		//TODO Make this work, it always returns NaN

		List<Double> vttsFiltered = new ArrayList<>();

		for (Id<Person> personId : this.personId2TripNr2VTTSh.keySet()){
			for (Integer tripNr : this.personId2TripNr2VTTSh.get(personId).keySet()){

				boolean considerTrip = true;

				if (mode != null) {
					if (this.personId2TripNr2Mode.get(personId).get(tripNr).equals(mode)) {
						// consider this trip
					} else {
						considerTrip = false;
					}
				}

				if (fromToTime_sec != null) {
					if (this.personId2TripNr2DepartureTime.get(personId).get(tripNr) >= fromToTime_sec.getFirst()
							&& this.personId2TripNr2DepartureTime.get(personId).get(tripNr) < fromToTime_sec.getSecond()) {
						// consider this trip
					} else {
						considerTrip = false;
					}
				}

				if (considerTrip) {
					vttsFiltered.add(this.personId2TripNr2VTTSh.get(personId).get(tripNr));
				}

			}
		}

		double[] vttsArray = new double[vttsFiltered.size()];

		int counter = 0;
		for (Double vtts : vttsFiltered) {
			vttsArray[counter] = vtts;
			counter++;
		}

		File file = new File(fileName);

		try {

			BufferedWriter bw = new BufferedWriter(new FileWriter(file));

			bw.write("5% percentile ; " + StatUtils.percentile(vttsArray, 5.0));
			bw.newLine();

			bw.write("25% percentile ; " + StatUtils.percentile(vttsArray, 25.0));
			bw.newLine();

			bw.write("50% percentile (median) ; " + StatUtils.percentile(vttsArray, 50.0));
			bw.newLine();

			bw.write("75% percentile ; " + StatUtils.percentile(vttsArray, 75.0));
			bw.newLine();

			bw.write("95% percentile ; " + StatUtils.percentile(vttsArray, 95.0));
			bw.newLine();

			bw.write("mean ; " + StatUtils.mean(vttsArray));
			bw.newLine();

			bw.write("MIN ; " + StatUtils.min(vttsArray));
			bw.newLine();

			bw.write("MAX ; " + StatUtils.max(vttsArray));
			bw.newLine();

			bw.write("Variance ; " + StatUtils.variance(vttsArray));
			bw.newLine();

			bw.close();
//			log.info("Output written to " + fileName);

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	// ===== Helper-methods ==========================================================================================================================

	/**
	 *
	 * @param id
	 * @param time
	 *
	 * This method returns the car mode VTTS in money per hour for a person at a given time and can for example be used to calculate a travel disutility during routing.
	 * Based on the time, the trip Nr is computed and based on the trip number the VTTS is looked up.
	 * In case there is no VTTS information available such as in the initial iteration before event handling, the default VTTS is returned.
	 *
	 * @return
	 */
	public double getCarVTTS(Id<Person> id, double time) {
		//TODO Refactor

		if (this.personId2TripNr2DepartureTime.containsKey(id)) {

			int tripNrOfGivenTime = Integer.MIN_VALUE;
			double departureTime = Double.MAX_VALUE;
			for (Integer tripNr : this.personId2TripNr2DepartureTime.get(id).keySet()) {
				if (time >= this.personId2TripNr2DepartureTime.get(id).get(tripNr)) {
					if (this.personId2TripNr2DepartureTime.get(id).get(tripNr) <= departureTime) {
						departureTime = this.personId2TripNr2DepartureTime.get(id).get(tripNr);
						tripNrOfGivenTime = tripNr;
					}
				}
			}

			if (tripNrOfGivenTime == Integer.MIN_VALUE) {

				if (noTripNrWarning <= 3) {
/*
					log.warn("Could not identify the trip number of person " + id + " at time " + time + "."
							+ " Trying to use the average car VTTS...");
*/
				}
				if (noTripNrWarning == 3) {
//					log.warn("Additional warnings of this type are suppressed.");
				}
				noTripNrWarning++;
				return this.getAvgVTTSh(id, TransportMode.car);

			} else {
				if (this.personId2TripNr2VTTSh.containsKey(id)) {

					if (this.personId2TripNr2Mode.get(id).get(tripNrOfGivenTime).equals(TransportMode.car)) {
						// everything fine
						double vtts = this.personId2TripNr2VTTSh.get(id).get(tripNrOfGivenTime);
						return vtts;

					} else {


						if (noCarVTTSWarning <= 3) {
/*
							log.warn("In the previous iteration at the given time " + time + " the agent " + id + " was performing a trip with a different mode (" + this.personId2TripNr2Mode.get(id).get(tripNrOfGivenTime) + ")."
									+ "Trying to use the average car VTTS.");
*/
							if (noCarVTTSWarning == 3) {
//								log.warn("Additional warnings of this type are suppressed.");
							}
							noCarVTTSWarning++;
						}
						return this.getAvgVTTSh(id, TransportMode.car);
					}

				} else {
					if (noTripVTTSWarning <= 3) {
/*
						log.warn("Could not find the VTTS of person " + id + " and trip number " + tripNrOfGivenTime + " (time: " + time + ")."
								+ " Trying to use the average car VTTS...");
*/
					}
					if (noTripVTTSWarning == 3) {
//						log.warn("Additional warnings of this type are suppressed.");
					}
					noTripVTTSWarning++;
					return this.getAvgVTTSh(id, TransportMode.car);
				}
			}

		} else {

			if (this.currentIteration == Integer.MIN_VALUE) {
				// the initial iteration before handling any events
				return this.defaultVTTS_moneyPerHour;
			} else {
				throw new RuntimeException("This is not the initial iteration and there is no information available from the previous iteration. Aborting...");
			}
		}
	}

	public Map<Id<Person>, Map<Integer, Double>> getPersonId2TripNr2VTTSh() {
		return personId2TripNr2VTTSh;
	}

	public Map<Id<Person>, Map<Integer, String>> getPersonId2TripNr2Mode() {
		return personId2TripNr2Mode;
	}

	public double getAvgVTTSh(Id<Person> id) {
		double sum = 0.;
		int counter = 0;

		if (this.personId2VTTSh.containsKey(id)) {

			for (Double vtts : this.personId2VTTSh.get(id)) {
				sum += vtts;
				counter++;
			}

			double avgVTTS = sum / counter;
			return avgVTTS;

		} else {

//			log.warn("Couldn't find any VTTS of person " + id + ". Using the default VTTS...");
			return this.defaultVTTS_moneyPerHour;
		}
	}

	public double getAvgVTTSh(Id<Person> id, String mode) {
		double sum = 0.;
		int counter = 0;

		if (this.personId2TripNr2VTTSh.containsKey(id)) {
			for (Integer tripNr : this.personId2TripNr2VTTSh.get(id).keySet()) {
				if (this.personId2TripNr2Mode.get(id).get(tripNr).equals(mode)) {
					sum += this.personId2TripNr2VTTSh.get(id).get(tripNr);
					counter++;
				}
			}

			if (counter == 0) {
//				log.warn("Couldn't find any VTTS of person " + id + " with transport mode + " + mode + ". Using the default VTTS...");
				return this.defaultVTTS_moneyPerHour;

			} else {
				double avgVTTSmode = sum / counter;
				return avgVTTSmode;
			}

		} else {
//			log.warn("Couldn't find any VTTS of person " + id + ". Using the default VTTS...");
			return this.defaultVTTS_moneyPerHour;
		}
	}

	private boolean isModeToBeSkipped(String legMode) {
		for (String modeToBeSkipped : this.modesToBeSkipped) {
			if (legMode.equals(modeToBeSkipped)) {
				return true;
			}
		}
		return false;
	}

	private boolean isActivityToBeSkipped(String actType) {
		for(String toIgnore : stageActivitiesSubStrings){
			if (actType.contains(toIgnore)) return true;
		}
		return false;
	}
}

/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
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

import org.matsim.api.core.v01.population.Activity;
//import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
//import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.TypicalDurationScoreComputation;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.ScoringParameters;
import org.matsim.core.utils.misc.Time;

/**
 * @author ikaddoura
 *
 */
public class MarginalSumScoringFunction {
	//TODO Reimplement logger
//	private final static Logger log = Logger.getLogger(MarginalSumScoringFunction.class);

	CharyparNagelActivityScoring activityScoringA;
	CharyparNagelActivityScoring activityScoringB;

	public MarginalSumScoringFunction(ScoringParameters params) {
		//TODO Check if this is still needed
		/*PlanCalcScoreConfigGroup.ActivityParams taxiActParams = new PlanCalcScoreConfigGroup.ActivityParams("TaxiPickup");
		taxiActParams.setTypicalDurationScoreComputation(TypicalDurationScoreComputation.relative);
		taxiActParams.setScoringThisActivityAtAll(false);
		taxiActParams.setTypicalDuration(1.0);

		ActivityUtilityParameters actUtilityParams = new ActivityUtilityParameters.Builder(taxiActParams).build();
		params.utilParams.put("TaxiPickup", actUtilityParams);*/

		activityScoringA = new CharyparNagelActivityScoring(params);
		activityScoringB = new CharyparNagelActivityScoring(params);
	}

	/**
	 * Computes, how great the impact of delay is to the score of an activity.
	 * This is done by computing the score, if the activity had been started earlier by {@code delay} seconds, which is given.
	 * @param activity Activity to check
	 * @param delay Delay in seconds
	 * @return (usually negative) difference of activity-score with and without delay (DelayDisutility): {@code scoreWithoutDelay - scoreWithDelay}
	 */
	public final double getNormalActivityDelayDisutility(Activity activity, double delay) {

		//Scrong Function for Activity with delay
		SumScoringFunction sumScoringA = new SumScoringFunction();
		sumScoringA.addScoringFunction(activityScoringA);

		SumScoringFunction sumScoringB = new SumScoringFunction();
		sumScoringB.addScoringFunction(activityScoringB);

		// Check that activity is not the first or the last activity
		if(activity.getStartTime().isUndefined() || activity.getEndTime().isUndefined()){
			throw new RuntimeException("Missing start or end time! The provided activity is probably the first or last activity. Aborting...");
		}

		// With CharyparNagel, scoreA0 and scoreB0 will always be 0
		double scoreA0 = sumScoringA.getScore();
		double scoreB0 = sumScoringB.getScore();

		Activity activityWithoutDelay = PopulationUtils.createActivity(activity);
		activityWithoutDelay.setStartTime(activity.getStartTime().seconds() - delay); // TODO check if seconds is the right unit

//		log.info("activity: " + activity.toString());
//		log.info("activityWithoutDelay: " + activityWithoutDelay.toString());

		sumScoringA.handleActivity(activity);
		sumScoringB.handleActivity(activityWithoutDelay);

		sumScoringA.finish();
		sumScoringB.finish();

		double scoreA1 = sumScoringA.getScore();
		double scoreB1 = sumScoringB.getScore();

		double scoreWithDelay = scoreA1 - scoreA0;
		double scoreWithoutDelay = scoreB1 - scoreB0;

		return scoreWithoutDelay - scoreWithDelay; //ActivityDelayDisutility
	}

	/**
	 * Computes, how great the impact of delay is to the score of two activities (which are usually the same OvernightActivity of an agent).
	 * This is done by computing the score, if the activity had been started earlier by {@code delay} seconds, which is given.
	 * @param activityMorning The first activity of an agent-plan
	 * @param activityEvening The last activity of an agent-plan
	 * @param delay Delay in seconds
	 * @return (usually negative) difference of activity-score with and without delay (DelayDisutility): {@code scoreWithoutDelay - scoreWithDelay}
	 */
	public final double getOvernightActivityDelayDisutility(Activity activityMorning, Activity activityEvening, double delay) {

		SumScoringFunction delegateA = new SumScoringFunction() ;
		delegateA.addScoringFunction(activityScoringA);

		SumScoringFunction delegateB = new SumScoringFunction() ;
		delegateB.addScoringFunction(activityScoringB);

		// Check that morningActivity is the first activity
		if(activityMorning.getStartTime().isDefined() || activityMorning.getEndTime().isUndefined()){
			throw new RuntimeException("activityMorning is not the first activity. Or why does it have a start time? Aborting...");
		}

		// Check that eveningActivity is the last activity
		if(activityEvening.getStartTime().isUndefined() || activityEvening.getEndTime().isDefined()){
			throw new RuntimeException("activityEvening is not the last activity. Or why does it have an end time? Aborting...");
		}

		// With CharyparNagel, scoreA0 and scoreB0 will always be 0
		double scoreA0 = delegateA.getScore();
		double scoreB0 = delegateB.getScore();

		delegateA.handleActivity(activityMorning);
		delegateB.handleActivity(activityMorning);

		Activity activityEveningWithoutDelay = PopulationUtils.createActivity(activityEvening);
		activityEveningWithoutDelay.setStartTime(activityEvening.getStartTime().seconds() - delay);

//		log.info("activityMorning: " + activityMorning.toString());
//		log.info("activityEvening: " + activityEvening.toString());
//		log.info("activityEveningWithoutDelay: " + activityEveningWithoutDelay.toString());

		delegateA.handleActivity(activityEvening);
		delegateB.handleActivity(activityEveningWithoutDelay);

		delegateA.finish();
		delegateB.finish();

		double scoreA1 = delegateA.getScore();
		double scoreB1 = delegateB.getScore();

		double scoreWithDelay = scoreA1 - scoreA0;
		double scoreWithoutDelay = scoreB1 - scoreB0;

		return scoreWithoutDelay - scoreWithDelay; //activityDelayDisutility
	}

}

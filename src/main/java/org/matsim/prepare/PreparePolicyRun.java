package org.matsim.prepare;

import org.matsim.application.prepare.population.FixSubtourModes;
import org.matsim.prepare.clean.CleanFacilities;
import org.matsim.prepare.clean.PreparePopulationForPolicy;

/**
 * Utility class to prepare population and facilities for a policy run using an existing base case.
 * This script ensures to remove invalid links, facilities and transit routes that are not valid anymore.
 */
public class PreparePolicyRun {

	public static void main(String[] args) {

		// The updated network
		String network = "<path to network file>";

		// The updated transit schedule
		String transitSchedule = "<path to transit schedule file>";

		// Calibrated population
		String population = "<path to population file>";

		// Generated facilities from base case
		String facilities = "<path to facilities file>";

		new CleanFacilities().execute(
			"--network", network,
			"--facilities", facilities,
			"--output", "cleaned-facilities.xml.gz"
		);

		new PreparePopulationForPolicy().execute(
			"--population", population,
			"--network", network,
			"--facilities", "cleaned-facilities.xml.gz",
			"--transit-schedule", transitSchedule,

			// These two files need to be used in the policy run
			"--output-population", "prepared-population.xml.gz",
			"--output-facilities", "prepared-facilities.xml.gz"
		);

		// Due to network and facility changes, subtours need to be corrected again
		new FixSubtourModes().execute(
			"--input", "prepared-population.xml.gz",
			"--output", "prepared-population.xml.gz",
			"--all-plans"
		);
	}

}

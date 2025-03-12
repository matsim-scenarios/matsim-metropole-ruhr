package org.matsim.prepare;

import org.matsim.application.prepare.population.FixSubtourModes;
import org.matsim.prepare.clean.PreparePopulationForPolicy;

/**
 * Utility class to prepare population and facilities for a policy run using an existing base case.
 * This script ensures to remove invalid links, facilities and transit routes that are not valid anymore.
 */
public class PreparePolicyRun {

	public static void main(String[] args) {

		// The updated network
		String network = "<path to network file>";

		// Calibrated population
		String population = "<path to population file>";

		new PreparePopulationForPolicy().execute(
			"--population", population,
			"--network", network,

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

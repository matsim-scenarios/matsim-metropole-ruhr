package org.matsim.run;

import org.matsim.application.MATSimApplication;

/**
 * Run the Metropole Ruhr scenario with default configuration.
 */
class RunMetropoleRuhrScenario {

	public static void main(String[] args) {
		MATSimApplication.runWithDefaults(MetropoleRuhrScenario.class, args);
	}

}

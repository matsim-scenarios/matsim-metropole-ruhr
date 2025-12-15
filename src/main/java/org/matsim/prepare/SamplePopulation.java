package org.matsim.prepare;

import org.matsim.api.core.v01.population.Population;
import org.matsim.application.prepare.population.DownSamplePopulation;
import org.matsim.core.population.PopulationUtils;


/**
 * Sample a population file down to a given size. Needed this to test reduced commercial demand
 */
public class SamplePopulation {

	public static void main(String[] args) {

		DownSamplePopulation downSamplePopulation = new DownSamplePopulation();

		String inputFile ="/Users/gregorr/Volumes/math-cluster/matsim-metropole-ruhr/commercialTraffic/output/mergedPopulations/metropole-ruhr-v2024.1-10pct.plans_withReplaced_KWM.xml.gz";

		String [] argsForDownSampling = {inputFile,
				"--sample-size", "0.10",
				"--samples", "0.03",
		};
		downSamplePopulation.execute(argsForDownSampling);
	}
}

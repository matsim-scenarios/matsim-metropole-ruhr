package org.matsim.prepare;

import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;

public class ReducePopulation {

	public static void main(String[] args) {
		Population population = PopulationUtils.readPopulation("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v2024.0/10pct/016.output_plans.xml.gz");
		PopulationUtils.sampleDown(population, 0.85);
		PopulationUtils.writePopulation(population, "/Users/gregorr/Volumes/math-cluster/matsim-metropole-ruhr/calibration-2.0-downsamplePopulation/15pctLess/016.output_plans_15pctLess.xml.gz");
	}

}

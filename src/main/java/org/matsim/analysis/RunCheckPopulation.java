package org.matsim.analysis;

import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.CheckPopulation;

import java.io.IOException;

public class RunCheckPopulation  {

	public static void main(String[] args) throws IOException {
		String[] argsForPopulationAnalysis = new String[]{
			"/Users/gregorr/Volumes/math-cluster/matsim-metropole-ruhr/calibration-2.0/calibration-2.0-10pct/runs/016/016.output_plans.xml.gz",
			"--shp", "scenarios/metropole-ruhr-v2024.0/input/area/area.shp",
			"--input-crs", "EPSG:25832",
			"--shp-crs", "EPSG:25832"
		};
		CheckPopulation checkPopulation = new CheckPopulation();
		checkPopulation.execute(argsForPopulationAnalysis);
	}


}

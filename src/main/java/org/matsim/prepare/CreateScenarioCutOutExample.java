package org.matsim.prepare;


import org.matsim.application.prepare.scenario.CreateScenarioCutOut;

public class CreateScenarioCutOutExample {

	public static void main(String[] args) {
		String[] inputScenario = new String[]{
			"--shp-crs",
			"EPSG:25832",
			"--shp",
			"/Users/gregorr/Documents/work/respos/shared-svn/projects/GlaMoBi/data/shp-files/Gladbeck.shp",
			"--population",
			"/Users/gregorr/Documents/work/stuff/testCutOut/007.output_plans.xml.gz",
			"--network",
			"/Users/gregorr/Documents/work/stuff/testCutOut/007.output_network.xml.gz",
			"--facilities",
			"/Users/gregorr/Documents/work/stuff/testCutOut/007.output_facilities.xml.gz",
			"--events",
			"/Users/gregorr/Documents/work/stuff/testCutOut/007.output_events.xml.gz",
			"--output-network",
			"/Users/gregorr/Documents/work/stuff/testCutOut/networkGladbeck.xml.gz",
			"--output-network-change-events",
			"/Users/gregorr/Documents/work/stuff/testCutOut/networkChangeEventsGladbeck.xml.gz",
			"--output-population",
			"/Users/gregorr/Documents/work/stuff/testCutOut/populationGladbeck.xml.gz",
			"--output-facilities",
			"/Users/gregorr/Documents/work/stuff/testCutOut/facilitiesGladbeck.xml.gz",
			"--input-crs",
			"EPSG:25832",
			"--network-modes",
			"bike,car,freight,truck8t,truck18t,truck26t,truck40t,ride"

		};

		if (args.length == 0) {
			args = inputScenario;
		}
	
		new CreateScenarioCutOut().execute(args);
	}
}

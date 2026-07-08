package org.matsim.analysis;

import org.matsim.analysis.linkpaxvolumes.LinkPaxVolumesAnalysis;
import org.matsim.analysis.linkpaxvolumes.LinkPaxVolumesWriter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
/*
Simple class to read events and write link passenger volumes.
This class is not used in the code base, but is provided as an example of how to use the LinkPaxVolumesAnalysis class.
Please use the default output and only use this to create the link pax volumes of older versions of the scenario.
 */

public class LinkPaxVol {
	public static void main(String[] args) {

		//set the path to the events file
		String eventsFile = "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/004.output_events.xml.gz";
		//set the path to the config file
		Config config = ConfigUtils.loadConfig("/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/004.output_config.xml");
		//load the scenario
		config.network().setInputFile("/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/004.output_network.xml.gz");
		config.plans().setInputFile(null);
		config.transit().setTransitScheduleFile(null);
		config.transit().setVehiclesFile(null);
		config.vehicles().setVehiclesFile("/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/004.output_vehicles.xml.gz");
		Scenario scenario = ScenarioUtils.loadScenario(config);
		// event reader add event handler  for linkPaxVolumes
		LinkPaxVolumesAnalysis linkPaxVolumesAnalysis = new LinkPaxVolumesAnalysis(scenario.getVehicles(), scenario.getTransitVehicles());

		EventsManager eventsManager = EventsUtils.createEventsManager();
		eventsManager.addHandler(linkPaxVolumesAnalysis);
		MatsimEventsReader eventsReader = new MatsimEventsReader(eventsManager);
		eventsReader.readFile(eventsFile);
		// write the results
		LinkPaxVolumesWriter linkPaxVolumesWriter = new LinkPaxVolumesWriter(linkPaxVolumesAnalysis, scenario.getNetwork(), scenario.getConfig().global().getDefaultDelimiter());
		linkPaxVolumesWriter.writeLinkVehicleAndPaxVolumesAllPerDayCsv( "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/gr_analysis/linkPaxVolumesAllPerDay.csv.gz");
		linkPaxVolumesWriter.writeLinkVehicleAndPaxVolumesPerNetworkModePerHourCsv( "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.2/output/metropole-ruhr-v2024.2-10pct/gr_analysis/linkPaxVolumesPerNetworkModePerHour.csv.gz");
	}
}

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
		String eventsFile = "";
		//set the path to the config file
		Config config = ConfigUtils.loadConfig("");
		//load the scenario
		Scenario scenario = ScenarioUtils.loadScenario(config);
		// event reader add event handler  for linkPaxVolumes
		LinkPaxVolumesAnalysis linkPaxVolumesAnalysis = new LinkPaxVolumesAnalysis(scenario.getVehicles(), scenario.getTransitVehicles());

		EventsManager eventsManager = EventsUtils.createEventsManager();
		eventsManager.addHandler(linkPaxVolumesAnalysis);
		MatsimEventsReader eventsReader = new MatsimEventsReader(eventsManager);
		eventsReader.readFile(eventsFile);
		// write the results
		LinkPaxVolumesWriter linkPaxVolumesWriter = new LinkPaxVolumesWriter(linkPaxVolumesAnalysis, scenario.getNetwork(), scenario.getConfig().global().getDefaultDelimiter());
		linkPaxVolumesWriter.writeLinkVehicleAndPaxVolumesAllPerDayCsv( "linkPaxVolumesAllPerDay.csv.gz");
		linkPaxVolumesWriter.writeLinkVehicleAndPaxVolumesPerNetworkModePerHourCsv( "linkPaxVolumesPerNetworkModePerHour.csv.gz");
	}
}

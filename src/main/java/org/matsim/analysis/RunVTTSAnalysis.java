package org.matsim.analysis;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class RunVTTSAnalysis {
	public static void main(String[] args) {
		//NOTE: This is not the regular config. I have changed it to work for this VTTS-analysis (removed all interaction activityParams). I will create a generic solution later. (aleks)
		Config ruhrConfig = ConfigUtils.loadConfig("../VTTS/000.output_config_vtts.xml");
		ruhrConfig.plans().setInputFile("../VTTS/000.output_plans.xml.gz");
		ruhrConfig.network().setInputFile(null);;
		ruhrConfig.facilities().setInputFile(null);
		ruhrConfig.transit().setTransitScheduleFile(null);
		ruhrConfig.transit().setVehiclesFile(null);
		ruhrConfig.counts().setInputFile(null);
		ruhrConfig.vehicles().setVehiclesFile(null);
		// TODO Removal of unnecessary acts

		//SnzActivities.addScoringParams(ruhrConfig); Not needed in this simulation setup
		Scenario ruhrScenario = ScenarioUtils.loadScenario(ruhrConfig);

		//
		org.matsim.analysis.VTTSHandler handler = new org.matsim.analysis.VTTSHandler(ruhrScenario, new String[]{"walk"}, new String[]{"interaction"});
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(handler);

		EventsUtils.readEvents(manager, "../VTTS/000.output_events.xml.gz");

		handler.printVTTS("../VTTS/testPrintVTTS");
		handler.printCarVTTS("../VTTS/testPrintCarVTTS");
		handler.printVTTS("../VTTS/testPrintVTTSMode2", TransportMode.bike);
		handler.printAvgVTTSperPerson("../VTTS/testPrintAvgVTTSperPerson");
	}
}

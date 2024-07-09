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
		Config kelheimConfig = ConfigUtils.loadConfig("output/vtts/kelheim-v3.0-config.xml");
		SnzActivities.addScoringParams(kelheimConfig);
		Scenario kelheimScenario = ScenarioUtils.loadScenario(kelheimConfig);

		org.matsim.analysis.VTTSHandler handler = new org.matsim.analysis.VTTSHandler(kelheimScenario, new String[]{}, "interaction");
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(handler);

		EventsUtils.readEvents(manager, "output/vtts/kelheim-v3.0-1pct.output_events.xml.gz");

		handler.printVTTS("output/vtts/testPrintVTTS");
		handler.printCarVTTS("output/vtts/testPrintCarVTTS");
		handler.printVTTS("output/vtts/testPrintVTTSMode2", TransportMode.bike);
		handler.printAvgVTTSperPerson("output/vtts/testPrintAvgVTTSperPerson");
	}
}

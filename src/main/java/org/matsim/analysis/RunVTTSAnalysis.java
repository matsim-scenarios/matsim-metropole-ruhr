package org.matsim.analysis;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import picocli.CommandLine;

public class RunVTTSAnalysis implements MATSimAppCommand {

	@CommandLine.Option(names = "--config", description = "Path to config file", required = true)
	private String configPath;

	@CommandLine.Option(names = "--plans", description = "Path to plans file", required = true)
	private String plansPath;

	@CommandLine.Option(names = "--events", description = "Path to events file", required = true)
	private String eventsPath;

	@CommandLine.Option(names = "--out", description = "Path for the output", required = true)
	private String outPath;

	@CommandLine.Option(names = "--modes", description = "Analyse specified modes distinctly. If you want multiple analysis, write separated by semicolon. (e.g. 'bike;car;...')")
	private String modes;

	@CommandLine.Option(names = "--statistics", description = "Prints VTTS statistic for a specified mode in a specified time-frame. If you want multiple analysis, write separated by semicolon: 'mode1,start1,end1;mode2,start2,end2;...'. Unit is seconds (e.g. bike,0,10000;car,100,8000)")
	private String statistics;

	@CommandLine.Option(names = "--ignoredModes", description = "Modes that should be ignored (e.g. walk;...)")
	private String ignoredModes;

	@CommandLine.Option(names = "--ignoredActs", description = "Substrings of activities that should be ignored (e.g. interaction;...)")
	private String ignoredActs;

	@CommandLine.Option(names = "--snzScoring", description = "If activated, automatically applies scoring parameters to SnzActivities like home_3200, ...", defaultValue = "false")
	private boolean snzScoring;

	@CommandLine.Option(names = "--incomeDependantScoring", description = "If activated, a personal marginal utility of money is computed for every agent", defaultValue = "false")
	private boolean incomeDependantScoring;

	public static void main(String[] args) {
		new CommandLine(new RunVTTSAnalysis()).execute(args);
	}

	@Override
	public Integer call() throws Exception {
		/* Debug:
		--config ../VTTS/000.output_config_vtts.xml
		--plans ../VTTS/000.output_plans.xml.gz
		--events ../VTTS/000.output_events.xml.gz
		--out ../VTTS/
		--modes bike,pt
		--statistics bike,0,10000
		--ignoredModes walk
		--ignoredActs interaction
		--incomeDependantScoring
		 */
		System.out.println(configPath);
		System.out.println(plansPath);
		System.out.println(eventsPath);
		System.out.println(outPath);
		System.out.println(modes);
		System.out.println(statistics);
		System.out.println(snzScoring);
		System.out.println(ignoredModes);
		System.out.println(ignoredActs);
		System.out.println(incomeDependantScoring);

		Config ruhrConfig = ConfigUtils.loadConfig(configPath);
		ruhrConfig.plans().setInputFile(plansPath);
		ruhrConfig.global().setNumberOfThreads(16);
		ruhrConfig.network().setInputFile(null);
		ruhrConfig.facilities().setInputFile(null);
		ruhrConfig.transit().setTransitScheduleFile(null);
		ruhrConfig.transit().setVehiclesFile(null);
		ruhrConfig.counts().setInputFile(null);
		ruhrConfig.vehicles().setVehiclesFile(null);
		ruhrConfig.vspExperimental().setAbleToOverwritePtInteractionParams(true);

		// Sets the typical durations of all activities, that should be ignored, to 0.1. This allows to use configs with interaction-acts-scoring-params.
		// The value itself will never be used by the VTTSHandler.
		for(ScoringConfigGroup.ActivityParams activityParams : ruhrConfig.scoring().getActivityParams().stream().toList()) {
			for(String subIgnoredAct : ignoredActs.split(";")) {
				if(activityParams.getActivityType().contains(subIgnoredAct)) activityParams.setTypicalDuration(0.1);
			}
		}

		if(snzScoring) SnzActivities.addScoringParams(ruhrConfig);

		Scenario ruhrScenario = ScenarioUtils.loadScenario(ruhrConfig);

		org.matsim.analysis.VTTSHandler handler = new org.matsim.analysis.VTTSHandler(ruhrScenario, ignoredModes.split(";"), ignoredActs.split(";"));
		if(incomeDependantScoring) handler.applyIncomeDependantScoring();

		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(handler);
		EventsUtils.readEvents(manager, eventsPath);

		handler.printVTTS(outPath + "VTTS");
		handler.printCarVTTS(outPath + "carVTTS");
		handler.printAvgVTTSperPerson(outPath + "avgVTTSperPerson");
		for(String mode : modes.split(";")){
			handler.printVTTS(outPath + "VTTS_" + mode, mode);
		}
		for(String stat : statistics.split(";")) {
			handler.printVTTSstatistics(
				outPath + "VTTSstatistics_" + stat.split(",")[0],
				stat.split(",")[0],
				new Tuple<>(Double.parseDouble(stat.split(",")[1]), Double.parseDouble(stat.split(",")[2])));
		}

		return 0;
	}
}

package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.population.DownSamplePopulation;
import org.matsim.application.prepare.population.ExtractHomeCoordinates;
import org.matsim.application.prepare.population.FixSubtourModes;
import org.matsim.application.prepare.population.XYToLinks;
import org.matsim.run.RunMetropoleRuhrScenario;
import picocli.CommandLine;

import java.nio.file.Path;

/**
 * Creates and prepares a cutout scenario.
 * This class depends on {@link ScenarioCutOut}, but does additional processing steps.
 */
public class CreateCutOutScenario implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateCutOutScenario.class);

	@CommandLine.Mixin
	private ShpOptions shp;

	@CommandLine.Option(names = "--name", description = "Name of the new scenario", required = true)
	private String name;

	@CommandLine.Option(names = "--population", description = "Path to complete population", required = true)
	private Path population;

	@CommandLine.Option(names = "--network", description = "Path to full network", required = true)
	private Path network;


	public static void main(String[] args) {
		new CreateCutOutScenario().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shape file path is required!");
			return 2;
		}

		String networkPath = String.format("scenarios/input/%s-%s.network.xml.gz", name, RunMetropoleRuhrScenario.VERSION);
		String populationPath = String.format("scenarios/input/%s-%s-25pct.plans.xml.gz", name, RunMetropoleRuhrScenario.VERSION);

		new ScenarioCutOut().execute(
				"--input", population.toString(),
				"--network", network.toString(),
				"--input-crs", "EPSG:25832",
				"--shp", shp.getShapeFile().toString(),
				"--output-network", networkPath,
				"--output-population", populationPath,
				"--use-router"
		);

		new XYToLinks().execute(
				"--network", networkPath,
				"--input", populationPath,
				"--output", populationPath,
				"--car-only"
		);

		new FixSubtourModes().execute(
				"--input", populationPath, "--output", populationPath
		);

		new DownSamplePopulation().execute(populationPath,
				"--sample-size", "0.25",
				"--samples", "0.1", "0.01");


		new ExtractHomeCoordinates().execute(
				populationPath,
				"--csv", String.format("scenarios/input/%s-%s-homes.csv", name, RunMetropoleRuhrScenario.VERSION)
		);

		return 0;
	}
}

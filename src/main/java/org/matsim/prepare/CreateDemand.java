package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.prepare.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.population.io.StreamingPopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.run.MetropoleRuhrScenario;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Create population (input plans) for the scenario.
 */
public class CreateDemand {

	private static final Path rootFolder = Paths.get("../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0");
	private static final Path heightData = rootFolder.resolve("./original-data/2021-05-29_RVR_Grid_10m.tif");

	private static final Path outputFolder = Paths.get("../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.1/input");

	public static void main(String[] args) {

		// If true is given as argument, this class will build the proprietary population
		boolean closedModel = args.length > 0 && args[0].equalsIgnoreCase("True");

		String name = closedModel ? "closed-plans" : "plans";
		String outputPlans = outputFolder.resolve("metropole-ruhr-" + MetropoleRuhrScenario.VERSION + "-25pct." + name + ".xml.gz").toString();

		if (closedModel)
			outputPlans = outputPlans.replace(".plans", ".proprietary-plans");

		String input;
		if (closedModel) {
			System.out.println("Creating plans for proprietary model");
			input = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz";
		} else {
			input = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20230918_OpenData_Ruhr_300m/populaton.xml.gz";
		}

		String[] argsForRemoveRoutesFromPlans = new String[]{
			"--plans=" + input,
			"--keep-selected=true",
			"--output=../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
		};

		new RemoveRoutesFromPlans().execute(argsForRemoveRoutesFromPlans);

		new CloseTrajectories().execute(
			"../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
			"--output=" + outputPlans,
			"--min-duration=0",
			"--act-duration=" + 30 * 60
		);

		new TrajectoryToPlans().execute(
			"--name=prepare",
			"--sample-size=0.25",
			"--population=" + outputPlans,
			"--attributes=../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/personAttributes.xml.gz",
			"--output=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v2.0/input/"
		);

		if (!closedModel) {
			new ResolveGridCoordinates().execute(
				"../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v2.0/input/prepare-25pct.plans.xml.gz",
				"--input-crs=EPSG:25832",
				"--output=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v2.0/input/prepare-25pct.plans.xml.gz",
				"--landuse=../../shared-svn/projects/matsim-germany/landuse/landuse.shp",
				"--grid-resolution", "300"
			);
		}

		String tmp = outputPlans.replace("25pct", "tmp");
		new GenerateShortDistanceTrips().execute(
			"--population=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v2.0/input/prepare-25pct.plans.xml.gz",
			"--input-crs=EPSG:25832",
			"--shp=../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
			"--shp-crs=EPSG:25832",
			"--num-trips=551000",
			"--output=" + tmp
		);

		new XYToLinks().execute(
			"--input=" + tmp,
			"--output=" + tmp,
			"--network=../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0.network_resolutionHigh.xml.gz",
			"--car-only"
		);

		new FixSubtourModes().execute(
			"--input=" + tmp,
			"--output=" + tmp
		);

		//------------------- add elevation to population -------------------------------------------

		// Everything is supposed to be in UTM-32 at this point
		var elevationReader = new ElevationReader(List.of(heightData.toString()), new IdentityTransformation());
		var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		var out = new StreamingPopulationWriter();
		var in = new StreamingPopulationReader(scenario);
		in.addAlgorithm(person -> person.getPlans().stream()
			.flatMap(plan -> TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities).stream())
			.forEach(activity -> {
				var elevation = elevationReader.getElevationAt(activity.getCoord());
				activity.setCoord(new Coord(activity.getCoord().getX(), activity.getCoord().getY(), elevation));
			}));

		in.addAlgorithm(new PreparePersonAttributes());

		// this relies on the fact that algorithms are called in the same order as they are added.
		in.addAlgorithm(out);

		// open the output population
		out.startStreaming(outputPlans);
		// read the population, add elevation to each person and write each person to output population
		in.readFile(tmp);
		// finish the output population after the reader is finished
		out.closeStreaming();

		//----------------------

		new ExtractHomeCoordinates().execute(outputPlans,
			"--output=" + outputPlans,
			"--csv=" + outputPlans.replace(".xml.gz", "-homes.csv")
		);

		new DownSamplePopulation().execute(outputPlans,
			"--sample-size=0.25",
			"--samples", "0.1", "0.03", "0.01", "0.001"
		);

		// TODO: 25pct commercial traffic was not yet created
		for (String s : List.of("10", "1", "3", "0.1")) {
			// 0.1 sample will be named 0pct
			String sampledPlans = outputPlans.replace("25pct", (s.equals("0.1") ? "0" : s) + "pct");
			new MergePopulations().execute(
				sampledPlans,
				outputFolder.resolve(String.format("metropole-ruhr-v2024.1-%spct.plans-commercial.xml.gz", s)).toString(),
				"--output", sampledPlans
			);
		}

		new CheckPopulation().execute(outputPlans,
			"--input-crs=EPSG:25832",
			"--shp=../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
			"--shp-crs=EPSG:25832"
		);

	}

}

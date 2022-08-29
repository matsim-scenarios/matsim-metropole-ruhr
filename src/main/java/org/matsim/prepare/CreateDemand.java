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
import org.matsim.run.RunMetropoleRuhrScenario;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class CreateDemand {

	private static final Path rootFolder = Paths.get("../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0");
	private static final Path heightData = rootFolder.resolve("./original-data/2021-05-29_RVR_Grid_10m.tif");

	private static final Path outputFolder = Paths.get("../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input");
	private static final String OUTPUT = outputFolder.resolve("metropole-ruhr-" + RunMetropoleRuhrScenario.VERSION + "-25pct.plans.xml.gz").toString();

	public static void main(String[] args) {

		String[] argsForRemoveRoutesFromPlans = new String[]{
				"--plans=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz",
				"--keep-selected=true",
				"--output=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
		};

		new RemoveRoutesFromPlans().execute(argsForRemoveRoutesFromPlans);

		new CloseTrajectories().execute(
				"../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
				"--output=" + OUTPUT,
				"--min-duration=0",
				"--act-duration=" + 30 * 60
		);

		new TrajectoryToPlans().execute(
				"--name=prepare",
				"--sample-size=0.25",
				"--population=" + OUTPUT,
				"--attributes=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/personAttributes.xml.gz",
				"--output=../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/"
		);

		String tmp = OUTPUT.replace("25pct", "tmp");
		new GenerateShortDistanceTrips().execute(
				"--population=../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/prepare-25pct.plans.xml.gz",
				"--input-crs=EPSG:25832",
				"--shp=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
				"--shp-crs=EPSG:25832",
				"--num-trips=551000",
				"--output=" + tmp
		);

		new XYToLinks().execute(
				"--input=" + tmp,
				"--output=" + tmp,
				"--network=../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4.network_resolutionHigh.xml.gz",
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
		out.startStreaming(OUTPUT);
		// read the population, add elevation to each person and write each person to output population
		in.readFile(tmp);
		// finish the output population after the reader is finished
		out.closeStreaming();

		//----------------------

		new ExtractHomeCoordinates().execute(OUTPUT,
				"--csv="+ OUTPUT.replace(".xml.gz", "-homes.csv")
		);

		new DownSamplePopulation().execute(OUTPUT,
				"--sample-size=0.25",
				"--samples", "0.1", "0.03", "0.01", "0.001"
		);

		new CheckPopulation().execute(OUTPUT,
				"--input-crs=EPSG:25832",
				"--shp=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
				"--shp-crs=EPSG:25832"
		);


	}

}

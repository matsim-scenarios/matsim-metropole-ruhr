package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.prepare.population.RemoveRoutesFromPlans;
import org.matsim.application.prepare.population.TrajectoryToPlans;

import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.population.io.StreamingPopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import picocli.CommandLine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class CreateDemand {

	private static final Path heightData = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/original-data/2021-05-29_RVR_Grid_10m.tif");
	private static final Path inputFolder = Paths.get("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/");

	public static void main(String[] args) {

		Path rootFolder;

		if (args.length == 0) {
			rootFolder = Paths.get("/Users/ihab/Documents/workspace/");
		} else if (args.length == 1) {
			rootFolder = Paths.get(args[0]);
		} else {
			throw new IllegalArgumentException("Only one argument is allowed.");
		}
		
		String[] argsForRemoveRoutesFromPlans = new String[] {
				"--plans=" + rootFolder.resolve("shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz"),
				"--keep-selected=true",
				"--output=" + rootFolder.resolve("/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz"),
				};
        new CommandLine(new RemoveRoutesFromPlans()).execute(argsForRemoveRoutesFromPlans);

		String[] argsForTrajectoryToPlans = new String[] {
				"--name=metropole-ruhr-v1.0",
				"--sample-size=0.25",
				"--samples=0.01",
				"--population=" + rootFolder.resolve("shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz"),
				"--attributes=" + rootFolder.resolve("shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/personAttributes.xml.gz"),
				"--output=" + rootFolder.resolve("shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/")
				};
        new CommandLine(new TrajectoryToPlans()).execute(argsForTrajectoryToPlans);


        //------------------- add elevation to population -------------------------------------------

        // Everything is supposed to be in UTM-32 at this point
        var elevationReader = new ElevationReader(List.of(rootFolder.resolve(heightData).toString()), new IdentityTransformation());
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var out = new StreamingPopulationWriter();
		var in = new StreamingPopulationReader(scenario);
		in.addAlgorithm(person -> person.getPlans().stream()
				.flatMap(plan -> TripStructureUtils.getActivities(plan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities).stream())
				.forEach(activity -> {
					var elevation = elevationReader.getElevationAt(activity.getCoord());
					activity.setCoord(new Coord(activity.getCoord().getX(), activity.getCoord().getY(), elevation));
				}));
		// this relies on the fact that algorithms are called in the same order as they are added.
		in.addAlgorithm(out);

		// open the output population
		out.startStreaming(rootFolder.resolve(inputFolder).resolve("metropole-ruhr-v1.0-population-with-z.xml.gz").toString());
		// read the population, add elevation to each person and write each person to output population
		in.readFile(rootFolder.resolve(inputFolder).resolve("metropole-ruhr-v1.0-25pct.plans.xml.gz").toString());
		// finish the output population after the reader is finished
		out.closeStreaming();
	}
}

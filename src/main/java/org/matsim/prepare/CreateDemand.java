package org.matsim.prepare;

import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.prepare.population.DownSamplePopulation;
import org.matsim.application.prepare.population.GenerateShortDistanceTrips;
import org.matsim.application.prepare.population.RemoveRoutesFromPlans;
import org.matsim.application.prepare.population.TrajectoryToPlans;

public class CreateDemand {

	private static final String OUTPUT = "../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.1-25pct.plans.xml.gz";

	public static void main(String[] args) {

		String[] argsForRemoveRoutesFromPlans = new String[]{
				"--plans=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz",
				"--keep-selected=true",
				"--output=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
		};
		new RemoveRoutesFromPlans().execute(argsForRemoveRoutesFromPlans);

		new TrajectoryToPlans().execute(
				"--name=prepare",
				"--sample-size=0.25",
				"--population=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
				"--attributes=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/personAttributes.xml.gz",
				"--output=../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/"
		);

		new GenerateShortDistanceTrips().execute(
				"--population=../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/prepare-25pct.plans.xml.gz",
				"--input-crs=EPSG:25832",
				"--shp=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
				"--shp-crs=EPSG:25832",
				"--num-trips=TODO",
				"--output=" + OUTPUT
		);

		new DownSamplePopulation().execute(OUTPUT,
				"--sample-size==0.25",
				"--samples", "0.1", "0.01"
		);

		new CheckPopulation().execute(OUTPUT,
				"--input-crs=EPSG:25832",
				"--shp=../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp",
				"--shp-crs=EPSG:25832"
		);

	}

}

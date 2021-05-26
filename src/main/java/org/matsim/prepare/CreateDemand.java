package org.matsim.prepare;

import org.matsim.application.prepare.population.RemoveRoutesFromPlans;
import org.matsim.application.prepare.population.TrajectoryToPlans;

import picocli.CommandLine;

public class CreateDemand {

	public static void main(String[] args) {
		
		String[] argsForRemoveRoutesFromPlans = new String[] {
				"--plans=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz",
				"--keep-selected=true",
				"--output=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
				};
        new CommandLine(new RemoveRoutesFromPlans()).execute(argsForRemoveRoutesFromPlans);

		String[] argsForTrajectoryToPlans = new String[] {
				"--name=metropole-ruhr-v1.0",
				"--sample-size=0.25",
				"--samples=0.01",
				"--population=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population-without-routes.xml.gz",
				"--attributes=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/personAttributes.xml.gz",
				"--output=/Users/ihab/Documents/workspace/shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/"
				};
        new CommandLine(new TrajectoryToPlans()).execute(argsForTrajectoryToPlans);

	}
	
}

package org.matsim.prepare;

import org.matsim.application.prepare.RemoveRoutesFromPlans;
import org.matsim.application.prepare.TrajectoryToPlans;

import picocli.CommandLine;

public class CreateDemand {

	public static void main(String[] args) {
		
		String[] argsForRemoveRoutesFromPlans = new String[] {
				"--plans=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210309_verband_ruhr/optimizedPopulation.xml.gz",
				"--keep-selected=true",
				"--output=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210309_verband_ruhr/optimizedPopulation-without-routes.xml.gz",
				};
        new CommandLine(new RemoveRoutesFromPlans()).execute(argsForRemoveRoutesFromPlans);

		String[] argsForTrajectoryToPlans = new String[] {
				"--name=metropole-ruhr-v1.0",
				"--sample-size=0.25",
				"--samples=0.01",
				"--population=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210309_verband_ruhr/optimizedPopulation-without-routes.xml.gz",
				"--attributes=/Users/ihab/Documents/workspace/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210309_verband_ruhr/optimizedPersonAttributes.xml.gz",
				"--output=/Users/ihab/Documents/workspace/shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/"
				};
        new CommandLine(new TrajectoryToPlans()).execute(argsForTrajectoryToPlans);

	}
	
}

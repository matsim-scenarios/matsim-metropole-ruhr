package org.matsim.prepare;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.population.CleanPopulation;

@MATSimApplication.Prepare({CleanPopulation.class})

public class DeleteRoutesFromPlans {

    public static void main(String[] args) throws Exception {
        String[] argsForRemoveRoutesFromPlans = new String[]{
                "--plans=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.plans.xml.gz",
                "--output=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.plans-withoutRoutes.xml.gz",
                "--remove-routes=true",
                "--remove-unselected-plans=true",
                "--remove-activity-location=true"
        };
        new CleanPopulation().execute(argsForRemoveRoutesFromPlans);
    }


}

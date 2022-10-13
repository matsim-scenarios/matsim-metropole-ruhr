package org.matsim.prepare;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.population.CleanPopulation;

@MATSimApplication.Prepare({CleanPopulation.class})

public class DeleteRoutesFromPlans {

    public static void main(String[] args) throws Exception {
        String[] argsForRemoveRoutesFromPlans = new String[]{
                "--plans=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.3-3pct.plans-calibrated-selected.xml.gz",
                "--output=../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.3-3pct.plans-calibrated-selected-withoutRoutes.xml.gz",
                "--remove-routes=true"
        };
        new CleanPopulation().execute(argsForRemoveRoutesFromPlans);
    }


}

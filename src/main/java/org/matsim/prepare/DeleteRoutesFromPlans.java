package org.matsim.prepare;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.population.CleanPopulation;

@MATSimApplication.Prepare({CleanPopulation.class})

public class DeleteRoutesFromPlans {

    public static void main(String[] args) throws Exception {
        String[] argsForRemoveRoutesFromPlans = new String[]{
			//			"--input=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans.xml.gz",
//			"--output=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-fixed.xml.gz",
			"--plans=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans.xml.gz",
			"--output=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-no-routes-keep-activity-locations.xml.gz",

//			"--plans=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-fixedSubtours.xml.gz",
//                "--output=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-fixedSubtours-no-routes.xml.gz",
                "--remove-routes=true",
                "--remove-unselected-plans=true",
                "--remove-activity-location=false"
        };
        new CleanPopulation().execute(argsForRemoveRoutesFromPlans);
    }


}

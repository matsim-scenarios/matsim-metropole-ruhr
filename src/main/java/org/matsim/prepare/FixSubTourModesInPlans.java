package org.matsim.prepare;

import org.matsim.application.prepare.population.FixSubtourModes;

public class FixSubTourModesInPlans {

    public static void main(String[] args) throws Exception {
        String[] argsFixSubToursInPlans = new String[]{
			"--input=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-no-routes.xml.gz",
			"--output=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-no-routes-fixedSubtours.xml.gz",

//			"--input=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans.xml.gz",
//			"--output=/Users/gleich/git/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2024/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.plans-fixedSubtours.xml.gz",
//			"--input=/Users/gleich/Projekte/RVR/RVR_Run_2025-02-14/input_metropole-ruhr-v2024.0-without_mobimp/metropole-ruhr-v2024.0-3pct.output_plans_without_routes_and_facilities.xml.gz",
//                "--output=/Users/gleich/Projekte/RVR/RVR_Run_2025-02-14/input_metropole-ruhr-v2024.0-without_mobimp/metropole-ruhr-v2024.0-3pct.output_plans_without_routes_and_facilities-fixedSubtours.xml.gz",
                "--all-plans",
                "--mass-conservation"
        };
        new FixSubtourModes().execute(argsFixSubToursInPlans);
    }


}

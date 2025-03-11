package org.matsim.prepare;
import org.matsim.application.MATSimApplication;
import org.matsim.application.prepare.population.CleanPopulation;

@MATSimApplication.Prepare({CleanPopulation.class})
public class DeleteRoutesFromPlans {

    public static void main(String[] args) throws Exception {
        String[] argsForRemoveRoutesFromPlans = new String[]{
                "--plans=../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0-3pct.plans-commercial.xml.gz",
                "--output=../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2.0/input/no-routes/metropole-ruhr-v2.0-3pct.plans-commercial.xml.gz",
                "--remove-routes=true",
                "--remove-unselected-plans=true",
                "--remove-activity-location=true"
        };
        new CleanPopulation().execute(argsForRemoveRoutesFromPlans);
    }


}

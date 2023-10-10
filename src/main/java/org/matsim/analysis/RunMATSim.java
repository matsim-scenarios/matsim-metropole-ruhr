package org.matsim.analysis;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

public class RunMATSim {

    public static void main (String args[]) {

        Config config = ConfigUtils.createConfig();
        //Config config = ConfigUtils.loadConfig("/Users/gregorr/Documents/work/respos/git/matsim-metropole-ruhr/scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml");
        config.plans().setInputFile("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20230918_OpenData_Ruhr_300m/populaton.xml.gz");
        config.network().setInputFile("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4.network_resolutionHigh-with-pt.xml.gz");
        config.controler().setLastIteration(0);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controler controler = new Controler(scenario);

        controler.run();

    }
}

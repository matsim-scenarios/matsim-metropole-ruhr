package org.matsim.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.prepare.commercial.CreateFreightPlans;

public class RunCommercialTraffic {
    public static void main(String[] args) {

        boolean createPlans = true;
        String sample = "0.5";
        String populationName = "rvrCommercial." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        if (createPlans) {
            new CreateFreightPlans().execute(
                    "--data=../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/",
                    "--network=../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                    "--pathOutput=output/commercial",
                    "--truck-load=13.0",
                    "--working-days=260",
                    "--nameOutputPopulation", populationName,
                    "--sample", sample
            );
        }

        Config config = ConfigUtils.createConfig();

        config.plans().setInputFile("output/commercial/" + populationName);
        config.network().setInputFile(
                "../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz");
        config.controller().setOutputDirectory("output/commercial/Run_" + (int) (Double.parseDouble(sample) * 100) + "pct");
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.global().setCoordinateSystem("EPSG:25832");
        config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
        config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
        config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
        config.qsim().setUsePersonIdForMissingVehicleId(false);
        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);
        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controller = new Controler(scenario);

        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
//        controller.addOverridingModule(new SimWrapperModule());
        controller.run();
    }
}

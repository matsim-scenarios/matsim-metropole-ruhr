package org.matsim.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.prepare.population.MergePopulations;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.prepare.commercial.CreateFreightPlans;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;

public class RunCommercialTraffic {
    public static void main(String[] args) {

        // 1st step - create freight plans from BUW data
        String sample = "0.03";
        String freightPopulationName = "rvrFreight." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String output = "output/completeCommercialTraffic";

        new CreateFreightPlans().execute(
                "--data=../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/",
                "--network=../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                "--pathOutput", output,
                "--truck-load=13.0",
                "--working-days=260",
                "--nameOutputPopulation", freightPopulationName,
                "--sample", sample
        );


        // 2nd step - create small scale commercial traffic
        String configPath = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml";

        String shapeCRS = "EPSG:4326";
        String osmDataLocation = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/";
        String commercialPopulationName = "rvrCommercial." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";

        new GenerateSmallScaleCommercialTrafficDemand().execute(
                configPath,
                "--sample", sample,
                "--jspritIterations", "2",
                "--creationOption", "createNewCarrierFile",
                "--landuseConfiguration", "useOSMBuildingsAndLanduse",
                "--smallScaleCommercialTrafficType", "completeSmallScaleCommercialTraffic",
                "--zoneShapeFileName", "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040_RuhrOnly2.shp",
                "--buildingsShapeFileName", osmDataLocation + "all_building_in_ruhrgebiet.shp",
                "--landuseShapeFileName", osmDataLocation + "landuse_in_ruhrgebiet.shp",
                "--shapeCRS", shapeCRS,
                "--pathOutput", output,
                "--network=../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                "--nameOutputPopulation", commercialPopulationName);

        // 3rd step - Merge freight and commercial populations
        new MergePopulations().execute(
                "--input1", output + "/" + freightPopulationName,
                "--input2", output + "/" + commercialPopulationName,
                "--output", output + "/" + freightPopulationName.replace(".plans.xml.gz", "") + "_merged.plans.xml.gz"
        );

        Config config = ConfigUtils.loadConfig(configPath);

        config.plans().setInputFile("output/commercial/" + freightPopulationName);
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
        controller.run();
    }
}

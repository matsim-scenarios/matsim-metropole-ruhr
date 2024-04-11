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
import org.matsim.prepare.commercial.GenerateFreightDataRuhr;
import org.matsim.prepare.commercial.GenerateFreightPlansRuhr;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;

import java.nio.file.Files;
import java.nio.file.Path;

public class RunCommercialTraffic {


    public static void main(String[] args) {

        // 1st step - create freight data from BUW data
        String sample = "0.03";
        String freightPopulationName = "ruhr_freightPlans_" + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String freightDataName = "ruhr_freightData_100pct.xml.gz";
        String output = "output/completeCommercialTraffic_withReturnJourney";

        if (Files.exists(Path.of(output + "/" + freightDataName))) {
            System.out.println("Freight data already exists. Skipping generation.");
        } else
            new GenerateFreightDataRuhr().execute(
                    "--data=../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/matrix_gesamt.csv",
                    "--pathOutput", output,
                    "--nameOutputDataFile", freightDataName
            );
        // 2nd step - create freight plans from generated data
        if (Files.exists(Path.of(output + "/" + freightPopulationName))) {
            System.out.println("Freight population already exists. Skipping generation.");
        } else
            new GenerateFreightPlansRuhr().execute(
                    "--data", output + "/" + freightDataName,
                    "--network",
                    "../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                    "--nuts", "../public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/NUTS3/NUTS3_2010_DE.shp",
                    "--output", output,
                    "--nameOutputPopulation", freightPopulationName,
                    "--truck-load", "13.0",
                    "--working-days", "260",
                    "--sample", sample
            );

        // 3rd step - create small scale commercial traffic
        String configPath = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml";

        String shapeCRS = "EPSG:25832";
        String osmDataLocation = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/";
        String smallScaleCommercialPopulationName = "rvrCommercial." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String outputSmallScaleCommercial = output + "/smallScaleCommercial";
        new GenerateSmallScaleCommercialTrafficDemand().execute(
                configPath,
                "--pathToInvestigationAreaData", "scenarios/metropole-ruhr-v1.0/input/investigationAreaData.csv",
                "--sample", sample,
                "--jspritIterations", "2",
                "--creationOption", "createNewCarrierFile",
                "--landuseConfiguration", "useOSMBuildingsAndLanduse",
                "--smallScaleCommercialTrafficType", "completeSmallScaleCommercialTraffic",
//                "--zoneShapeFileName", "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040_RuhrOnly2.shp",
                "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                "--zoneShapeFileNameColumn","schluessel",
                "--regionsShapeFileName", osmDataLocation + "regions_25832.shp",
                "--regionsShapeRegionColumn", "GEN",
                "--buildingsShapeFileName", osmDataLocation + "buildings_25832.shp",
                "--shapeFileBuildingTypeColumn", "building",
                "--landuseShapeFileName", osmDataLocation + "landuse_v.1.0_25832.shp",
                "--shapeFileLanduseTypeColumn", "landuse",
                "--shapeCRS", shapeCRS,
                "--pathOutput", outputSmallScaleCommercial,
                "--network=../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                "--nameOutputPopulation", smallScaleCommercialPopulationName);

        // 4th step - Merge freight and commercial populations
        new MergePopulations().execute(
                "--input1", output + "/" + freightPopulationName,
                "--input2", outputSmallScaleCommercial + "/" + smallScaleCommercialPopulationName,
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

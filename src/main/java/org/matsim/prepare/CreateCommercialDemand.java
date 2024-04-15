package org.matsim.prepare;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Population;
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
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;

import java.nio.file.Files;
import java.nio.file.Path;

public class CreateCommercialDemand {
    public static void main(String[] args) {

        // 1st step - create freight data from BUW data
        String sample = "0.03";
        String freightPopulationName = "ruhr_freightPlans_" + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String freightDataName = "ruhr_freightData_100pct.xml.gz";
        String output = "output/completeCommercialTraffic_withReturnJourney2";
        boolean alsoRunCompleteCommercialTraffic = true;

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
//                    "--nuts", "../public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/NUTS3/NUTS3_2010_DE.shp",
                    "--output", output,
                    "--nameOutputPopulation", freightPopulationName,
                    "--truck-load", "13.0",
                    "--working-days", "260",
                    "--max-kilometer-for-return-journey", "200",
                    "--sample", sample
            );

        // 3rd step - create input data for small scale commercial traffic
        String shapeCRS = "EPSG:25832";
        String osmDataLocation = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/";

        String pathCommercialFacilities = output + "/commercialFacilities.xml.gz";
        if (Files.exists(Path.of(pathCommercialFacilities))) {
            System.out.println("Commercial facilities already exists. Skipping generation.");
        } else
            new CreateDataDistributionOfStructureData().execute(
                    "--pathOutput", output,
                    "--landuseConfiguration", "useOSMBuildingsAndLanduse",
                    "--regionsShapeFileName", osmDataLocation + "regions_25832.shp",
                    "--regionsShapeRegionColumn", "GEN",
                    "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                    "--zoneShapeFileNameColumn","schluessel",
                    "--buildingsShapeFileName", osmDataLocation + "buildings_25832.shp",
                    "--shapeFileBuildingTypeColumn", "building",
                    "--landuseShapeFileName", osmDataLocation + "landuse_v.1.0_25832.shp",
                    "--shapeFileLanduseTypeColumn", "landuse",
                    "--shapeCRS", shapeCRS,
                    "--pathToInvestigationAreaData", "scenarios/metropole-ruhr-v1.0/input/investigationAreaData.csv"
            );
        String configPath = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml";

        String smallScaleCommercialPopulationName = "rvrCommercial." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String outputPathSmallScaleCommercial = output + "/smallScaleCommercial/";
//        new GenerateSmallScaleCommercialTrafficDemand().execute(
//                configPath,
//                "--pathToDataDistributionToZones", output + "/dataDistributionPerZone.csv",
//                "--pathToCommercialFacilities", "../../../" + pathCommercialFacilities,
//                "--sample", sample,
//                "--jspritIterations", "1",
//                "--creationOption", "createNewCarrierFile",
////                "--smallScaleCommercialTrafficType", "completeSmallScaleCommercialTraffic",
//                "--smallScaleCommercialTrafficType", "goodsTraffic",
////                "--zoneShapeFileName", "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040_RuhrOnly2.shp",
//                "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
//                "--zoneShapeFileNameColumn","schluessel",
//                "--shapeCRS", shapeCRS,
//                "--pathOutput", outputPathSmallScaleCommercial,
//                "--network=../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
//                "--nameOutputPopulation", smallScaleCommercialPopulationName);
        smallScaleCommercialPopulationName = "rvrCommercial.0pct.plans.xml.gz"; //TODO change back
        Path resolve = Path.of(outputPathSmallScaleCommercial).resolve(smallScaleCommercialPopulationName);

        if (Files.exists(resolve)) {
            System.out.println("Small-scale Commercial demand already exists. Skipping generation.");
        } else
            new GenerateSmallScaleCommercialTrafficDemand().execute(
                configPath,
                "--pathToDataDistributionToZones", output + "/dataDistributionPerZone.csv",
                "--pathToCommercialFacilities", "../../../" + pathCommercialFacilities,
                "--carrierFilePath", "../../../" + outputPathSmallScaleCommercial + "/metropole-ruhr-v1.4-3pct.output_CarrierDemand.xml",
                "--sample", sample,
                "--jspritIterations", "1",
                "--creationOption", "useExistingCarrierFileWithoutSolution",
//                "--smallScaleCommercialTrafficType", "completeSmallScaleCommercialTraffic",
                "--smallScaleCommercialTrafficType", "goodsTraffic",
//                "--zoneShapeFileName", "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040_RuhrOnly2.shp",
                "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                "--zoneShapeFileNameColumn","schluessel",
                "--shapeCRS", shapeCRS,
                "--pathOutput", outputPathSmallScaleCommercial,
                "--network=../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                "--nameOutputPopulation", smallScaleCommercialPopulationName);

        // 4th step - Merge freight and commercial populations
        String pathMergedPopulation = output + "/" + freightPopulationName.replace(".plans.xml.gz", "") + "_merged.plans.xml.gz";
        if (Files.exists(Path.of(pathMergedPopulation))) {
            System.out.println("Merged demand already exists. Skipping generation.");
        } else
            new MergePopulations().execute(
                output + "/" + freightPopulationName,
                outputPathSmallScaleCommercial + "/" + smallScaleCommercialPopulationName,
                "--output", pathMergedPopulation
        );
        if (alsoRunCompleteCommercialTraffic) {
            Config config = ConfigUtils.loadConfig(configPath);

        config.plans().setInputFile("../../../"+ outputMergedPopulation);
        config.network().setInputFile(
                "../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz");
        config.controller().setOutputDirectory("output/commercialRun/Run_" + (int) (Double.parseDouble(sample) * 100) + "pct");
        config.controller().setLastIteration(0);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.transit().setUseTransit(false);
        config.transit().setTransitScheduleFile(null);
        config.transit().setVehiclesFile(null);
        config.global().setCoordinateSystem("EPSG:25832");
        config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
        config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
        config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
        config.qsim().setUsePersonIdForMissingVehicleId(false);
        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        config.getModules().remove("intermodalTripFareCompensators");
        config.getModules().remove("ptExtensions");
        config.getModules().remove("ptIntermodalRoutingModes");
        config.getModules().remove("swissRailRaptor");
        Controler controller = new Controler(scenario);
            config.plans().setInputFile("../../../" + pathMergedPopulation);
            config.network().setInputFile(
                    "../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz");
            config.controller().setOutputDirectory("output/commercial/Run_" + (int) (Double.parseDouble(sample) * 100) + "pct");
            config.controller().setLastIteration(0);
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
            config.transit().setUseTransit(false);
            config.transit().setTransitScheduleFile(null);
            config.transit().setVehiclesFile(null);
            config.global().setCoordinateSystem("EPSG:25832");
            config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
            config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
            config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
            config.qsim().setUsingTravelTimeCheckInTeleportation(true);
            config.qsim().setUsePersonIdForMissingVehicleId(false);
            config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
            config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            config.getModules().remove("intermodalTripFareCompensators");
            config.getModules().remove("ptExtensions");
            config.getModules().remove("ptIntermodalRoutingModes");
            config.getModules().remove("swissRailRaptor");
            Controler controller = new Controler(scenario);

            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));

            controller.run();
        }
    }
}

package org.matsim.prepare;

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
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CreateCommercialDemand {
    public static void main(String[] args) {

        // 1st step - create freight plans from BUW data
        String sample = "0.01";
        String freightPopulationName = "rvrFreight." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";
        String output = "output/completeCommercialTraffic_" + (int) (Double.parseDouble(sample) * 100) + "pct";

        if (Files.notExists(Path.of(output, "/" + freightPopulationName))) {
            new GenerateFreightDataRuhr().execute(
                    "--data=../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/",
                    "--network=../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                    "--pathOutput", output,
                    "--truck-load=13.0",
                    "--working-days=260",
                    "--nameOutputPopulation", freightPopulationName,
                    "--sample", sample
            );
        }


        // 2nd step - create small scale commercial traffic
        String configPath = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml";

        String shapeCRS = "EPSG:25832";
        String osmDataLocation = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/";
        String commercialPopulationName = "rvrCommercial." + (int) (Double.parseDouble(sample) * 100) + "pct.plans.xml.gz";

        //TODO use FNK data instead of OSM landuse data

//        String pathToInvestigationAreaData;
//        new CreateDataDistributionOfStructureData.execute(
//                "--pathOutput", output,
//                "--landuseConfiguration", "useOSMBuildingsAndLanduse",
//                "--regionsShapeFileName", osmDataLocation + "regions_25832.shp",
//                "--regionsShapeRegionColumn", "GEN",
//                "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
//                "--zoneShapeFileNameColumn", "gmdschl",
//                "--buildingsShapeFileName", osmDataLocation + "all_building_in_ruhrgebiet_25832.shp",
//                "--shapeFileBuildingTypeColumn", "type",
//                "--landuseShapeFileName", osmDataLocation + "landuse_v.1.0_25832.shp",
//                "--shapeCRS", shapeCRS,
//                "--pathToInvestigationAreaData", pathToInvestigationAreaData
//        );

        String outputSmallScaleCommercial = output + "/smallScaleCommercialRun";
        Path pathSmallScalePopulation = Path.of(output + "/" + commercialPopulationName);
        if (Files.notExists(pathSmallScalePopulation)) {
            new GenerateSmallScaleCommercialTrafficDemand().execute(
                    configPath,
                    "--sample", sample,
                    "--jspritIterations", "2",
                    "--creationOption", "createNewCarrierFile",
                    "--landuseConfiguration", "useOSMBuildingsAndLanduse",
                    "--smallScaleCommercialTrafficType", "goodsTraffic",
                    "--regionsShapeFileName", osmDataLocation + "regions_25832.shp",
                    "--regionsShapeRegionColumn", "GEN",
                    "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                    "--zoneShapeFileNameColumn", "gmdschl",
                    "--buildingsShapeFileName", osmDataLocation + "all_building_in_ruhrgebiet_25832.shp",
                    "--shapeFileBuildingTypeColumn", "type",
                    "--landuseShapeFileName", osmDataLocation + "landuse_v.1.0_25832.shp",
                    "--shapeCRS", shapeCRS,
                    "--pathOutput", outputSmallScaleCommercial,
                    "--network=../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz",
                    "--nameOutputPopulation", commercialPopulationName);

        try {
            Files.copy(Path.of(outputSmallScaleCommercial + "/" + commercialPopulationName), pathSmallScalePopulation);
            System.out.println("Datei erfolgreich kopiert");
        } catch (IOException e) {
            System.out.println("Fehler beim Kopieren der Datei");
            e.printStackTrace();
        }
    }
        //TODO better method to merge populations

        // 3rd step - Merge freight and commercial populations
        String outputMergedPopulation = output + "/" + freightPopulationName.replace(".plans.xml.gz", "") + "_merged.plans.xml.gz";
        if (Files.notExists(Path.of(outputMergedPopulation)))
            new MergePopulations().execute(
                    output + "/" + freightPopulationName,
                    output + "/" + commercialPopulationName,
                    "--output", outputMergedPopulation
            );

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

        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));

        controller.run();
    }
}

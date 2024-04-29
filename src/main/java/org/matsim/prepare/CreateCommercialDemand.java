package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.population.MergePopulations;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.prepare.commercial.GenerateFreightDataRuhr;
import org.matsim.prepare.commercial.GenerateFreightPlansRuhr;
import org.matsim.prepare.commercial.IntegrationOfExistingCommercialTrafficRuhr;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.IntegrateExistingTrafficToSmallScaleCommercial;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreator;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreatorForOSM_Data;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CreateCommercialDemand implements MATSimAppCommand {

    private static final Logger log = LogManager.getLogger(CreateCommercialDemand.class);


    @CommandLine.Option(names = "--sample", description = "Scaling factor of the small scale commercial traffic (0, 1)", required = true, defaultValue = "0.01")
    private double sample;

    @CommandLine.Option(names = "--pathOutputFolder", description = "Path for the output folder", required = true, defaultValue = "scenarios/metropole-ruhr-v1.0/output/completeCommercialTraffic_1pct")
    private Path output;

    @CommandLine.Option(names = "--osmDataLocation", description = "Path to the OSM data location", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/")
    private String osmDataLocation;

    @CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml")
    private Path configPath;

    @CommandLine.Option(names = "--pathToInvestigationAreaData", description = "Path to the investigation area data", required = true, defaultValue = "scenarios/metropole-ruhr-v1.0/input/investigationAreaData.csv")
    private String pathToInvestigationAreaData;

    @CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "ruhr_network_adjustedModes.xml.gz")
    private String networkPath;

    @CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file", required = true, defaultValue = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.mode-vehicles.xml")
    private String vehicleTypesFilePath;

    @CommandLine.Option(names = "--jspritIterationsForLTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
    private int jspritIterationsForLTL;

    @CommandLine.Option(names = "--jspritIterationsForSmallScaleCommercial", defaultValue = "10", description = "Number of iterations for jsprit for solving the small scale commercial traffic", required = true)
    private int jspritIterationsForSmallScaleCommercial;

    @CommandLine.Option(names = "--freightData", description = "Path to the freight raw data", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/matrix_gesamt.csv")
    private String freightData;

    @CommandLine.Option(names = "--alsoRunCompleteCommercialTraffic", description = "Also run MATSim for the complete commercial traffic")
    private boolean alsoRunCompleteCommercialTraffic;

    @CommandLine.Option(names = "--germanyFreightPlansFile", description = "Path to the Germany plans file", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.25pct.plans.xml.gz")
    private Path germanyPlansFile;

    @CommandLine.Option(names = "--networkForLongDistanceFreight", description = "Path to the network file for long distance freight", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz")
    private Path networkForLongDistanceFreight;

    public static void main(String[] args) {
        System.exit(new CommandLine(new CreateCommercialDemand()).execute(args));
    }

    @Override
    public Integer call() throws Exception {

//        alsoRunCompleteCommercialTraffic = true;

        String shapeCRS = "EPSG:25832";
        log.info("1st step - create freight data from BUW data");

        String freightPopulationName = "ruhr_freightPlans_" + (int) (sample * 100) + "pct.plans.xml.gz";
        String freightDataName = "ruhr_freightData_100pct.xml.gz";

        if (Files.exists(output.resolve(freightDataName))) {
            log.warn("Freight data already exists. Skipping generation.");
        } else {
            new GenerateFreightDataRuhr().execute(
                    "--data", freightData,
                    "--pathOutput", output.toString(),
                    "--nameOutputDataFile", freightDataName
            );
        }
        log.info("2nd step - create freight plans from generated data");
        if (Files.exists(output.resolve(freightPopulationName))) {
            log.warn("Freight population already exists. Skipping generation.");
        } else {
            new GenerateFreightPlansRuhr().execute(
                    "--data", output.resolve(freightDataName).toString(),
                    "--network", "input/" + networkPath,
                    "--output", output.toString(),
                    "--nameOutputPopulation", freightPopulationName,
                    "--truck-load", "13.0",
                    "--working-days", "260",
                    "--max-kilometer-for-return-journey", "200",
                    "--sample", String.valueOf(sample),
                    "--vehicleTypesFilePath", vehicleTypesFilePath,
                    "--jsprit-iterations-for-LTL", String.valueOf(jspritIterationsForLTL)
            );
        }
        log.info("3rd step - create transit long distance freight traffic");
        String longDistanceFreightPopulationName = output.resolve(
                "ruhr_longDistanceFreight." + (int) (sample * 100) + "pct.plans.xml.gz").toString();
        if (Files.exists(Path.of(longDistanceFreightPopulationName))) {
            log.warn("Long distance freight population already exists. Skipping generation.");
        } else {
            new ExtractRelevantFreightTrips().execute(
                    germanyPlansFile.toString(),
                    "--network", networkForLongDistanceFreight.toString(),
                    "--output", longDistanceFreightPopulationName,
                    "--shp", osmDataLocation + "regions_25832.shp",
                    "--input-crs", shapeCRS,
                    "--target-crs", shapeCRS,
                    "--shp-crs", shapeCRS,
                    "--tripType", "TRANSIT"
            );
            Population population = PopulationUtils.readPopulation(longDistanceFreightPopulationName);
            for (Person person : population.getPersons().values()) {
                PopulationUtils.putSubpopulation(person, "longDistanceFreight");
            }
            PopulationUtils.sampleDown(population, sample / 0.25);
            PopulationUtils.writePopulation(population, longDistanceFreightPopulationName);
        }
        log.info("4rd step - create input data for small scale commercial traffic");

        Path pathCommercialFacilities = output.resolve("commercialFacilities.xml.gz");
        LanduseDataConnectionCreator landuseDataConnectionCreator = new LanduseDataConnectionCreatorForOSM_Data(); //here possible to create an implementation for ruhrAGIS data
        if (Files.exists(pathCommercialFacilities)) {
            log.warn("Commercial facilities already exists. Skipping generation.");
        } else {
            new CreateDataDistributionOfStructureData(landuseDataConnectionCreator).execute(
                    "--pathOutput", output.toString(),
                    "--landuseConfiguration", "useOSMBuildingsAndLanduse",
                    "--regionsShapeFileName", osmDataLocation + "regions_25832.shp",
                    "--regionsShapeRegionColumn", "GEN",
                    "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                    "--zoneShapeFileNameColumn", "schluessel",
                    "--buildingsShapeFileName", osmDataLocation + "buildings_25832.shp",
                    "--shapeFileBuildingTypeColumn", "building",
                    "--landuseShapeFileName", osmDataLocation + "landuse_v.1.0_25832.shp",
                    "--shapeFileLanduseTypeColumn", "landuse",
                    "--shapeCRS", shapeCRS,
                    "--pathToInvestigationAreaData", pathToInvestigationAreaData
            );
        }
        log.info("5th step - create small scale commercial traffic");
        String smallScaleCommercialPopulationName = "rvrCommercial." + (int) (sample * 100) + "pct.plans.xml.gz";
        String outputPathSmallScaleCommercial = output.resolve("smallScaleCommercial").toString();
        Path resolve = Path.of(outputPathSmallScaleCommercial).resolve(smallScaleCommercialPopulationName);
        IntegrateExistingTrafficToSmallScaleCommercial integrateExistingTrafficToSmallScaleCommercial = new IntegrationOfExistingCommercialTrafficRuhr(output.resolve(freightPopulationName));

        if (Files.exists(resolve)) {
            log.warn("Small-scale Commercial demand already exists. Skipping generation.");
        } else {
            new GenerateSmallScaleCommercialTrafficDemand(integrateExistingTrafficToSmallScaleCommercial).execute(
                    configPath.toString(),
                    "--pathToDataDistributionToZones", output.resolve("dataDistributionPerZone.csv").toString(),
                    "--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
                    "--sample", String.valueOf(sample),
                    "--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
                    "--creationOption", "createNewCarrierFile",
//                    "--smallScaleCommercialTrafficType", "completeSmallScaleCommercialTraffic",
                    "--smallScaleCommercialTrafficType", "goodsTraffic",
//                "--zoneShapeFileName", "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040_RuhrOnly2.shp",
                    "--zoneShapeFileName", osmDataLocation + "zones_v2.0_25832.shp",
                    "--zoneShapeFileNameColumn", "schluessel",
                    "--shapeCRS", shapeCRS,
                    "--pathOutput", outputPathSmallScaleCommercial,
                    "--network", networkPath,
                    "--nameOutputPopulation", smallScaleCommercialPopulationName,
                    "--includeExistingModels");

            // TODO filter relevant agents for the small scale commercial traffic
        }
        log.info("6th step - Merge freight and commercial populations");
        String pathMergedPopulation = output.resolve(freightPopulationName).toString().replace(".plans.xml.gz", "") + "_merged.plans.xml.gz";
        if (Files.exists(Path.of(pathMergedPopulation))) {
            log.info("Merged demand already exists. Skipping generation.");
        } else {
            new MergePopulations().execute(
                    output.resolve(freightPopulationName).toString(),
                    outputPathSmallScaleCommercial + "/" + smallScaleCommercialPopulationName,
                    longDistanceFreightPopulationName,
                    "--output", pathMergedPopulation
            );
        }

        if (alsoRunCompleteCommercialTraffic) {
            Config config = ConfigUtils.loadConfig(configPath.toString());
            config.plans().setInputFile(configPath.getParent().relativize(Path.of(pathMergedPopulation)).toString());
            config.plans().setActivityDurationInterpretation(PlansConfigGroup.ActivityDurationInterpretation.tryEndTimeThenDuration);
            config.network().setInputFile(networkPath);
            System.out.println(networkPath);
            config.controller().setOutputDirectory(output.resolve("commercialTraffic_Run" + (int) (sample * 100) + "pct").toString());
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
            config.getModules().remove("intermodalTripFareCompensators");
            config.getModules().remove("ptExtensions");
            config.getModules().remove("ptIntermodalRoutingModes");
            config.getModules().remove("swissRailRaptor");

            //prepare the different modes
            ArrayList<String> newModes = new ArrayList<>(List.of("freight", "truck8t", "truck18t", "truck26t", "truck40t"));
            Collection<String> allModes = config.qsim().getMainModes();
            allModes.addAll(newModes);
            config.qsim().setMainModes(allModes);
            Set<String> allNetworkModes = new HashSet<>(config.routing().getNetworkModes());
            allNetworkModes.addAll(newModes);
            config.routing().setNetworkModes(allNetworkModes);
            newModes.forEach(mode -> {
                    ScoringConfigGroup.ModeParams thisModeParams = new ScoringConfigGroup.ModeParams(mode);
                    config.scoring().addModeParams(thisModeParams);
                    });

            Scenario scenario = ScenarioUtils.loadScenario(config);

            Controler controller = new Controler(scenario);

            controller.addOverridingModule(new SimWrapperModule());

            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("pickup").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("delivery").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_return").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60));
            config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_return").setTypicalDuration(30 * 60));

            controller.run();
        }
        return 0;
    }
}

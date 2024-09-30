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
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.prepare.commercial.GenerateFTLFreightPlansRuhr;
import org.matsim.prepare.commercial.GenerateFreightDataRuhr;
import org.matsim.prepare.commercial.GenerateLTLFreightPlansRuhr;
import org.matsim.prepare.commercial.IntegrationOfExistingCommercialTrafficRuhr;
import org.matsim.run.MetropoleRuhrScenario;
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

/**
 * This class is used to create the commercial demand for the Ruhr area. It generates the following parts of freight traffic:
 * <ul>
 *     <li>Full truck load freight plans</li>
 *     <li>Less than truck load freight plans</li>
 *     <li>Long distance Transit freight plans</li>
 *     <li>Small scale commercial traffic</li>
 * </ul>
 * @author Ricardo Ewert
 */
public class CreateCommercialDemand implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateCommercialDemand.class);

	@CommandLine.Option(names = "--sample", description = "Scaling factor of the small scale commercial traffic (0, 1)", required = true, defaultValue = "0.01")
	private double sample;

	@CommandLine.Option(names = "--pathOutputFolder", description = "Path for the output folder", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/output/RVR/commercialTraffic_1pct")
	private Path output;

	@CommandLine.Option(names = "--freightData", description = "Name of the freight population", defaultValue = "ruhr_freightPlans_100pct.plans.xml.gz")
	private Path freightData;

	@CommandLine.Option(names = "--osmDataLocation", description = "Path to the OSM data location", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/")
	private Path osmDataLocation;

	@CommandLine.Option(names = "--vpCellsLocation", description = "Path to the cell of the 'Verkehrsprognose (VP)' ", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040.shp")
	private Path vpCellsLocation;

	@CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0-3pct.config.xml")
	private Path configPath;

	@CommandLine.Option(names = "--pathToInvestigationAreaData", description = "Path to the investigation area data", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/input/investigationAreaData.csv")
	private String pathToInvestigationAreaData;

	@CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "metropole-ruhr-v2.0.network_noBike.xml.gz")
	private String networkPath;

	@CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0.mode-vehicles.xml")
	private String vehicleTypesFilePath;

	@CommandLine.Option(names = "--jspritIterationsForLTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
	private int jspritIterationsForLTL;

	@CommandLine.Option(names = "--jspritIterationsForSmallScaleCommercial", defaultValue = "10", description = "Number of iterations for jsprit for solving the small scale commercial traffic", required = true)
	private int jspritIterationsForSmallScaleCommercial;

	@CommandLine.Option(names = "--smallScaleCommercialTrafficType", description = "Select traffic type. Options: commercialPersonTraffic, goodsTraffic, completeSmallScaleCommercialTraffic (contains both types)", defaultValue = "completeSmallScaleCommercialTraffic")
	private String smallScaleCommercialTrafficType;

	@CommandLine.Option(names = "--freightRawData", description = "Path to the freight raw data", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/matrix_gesamt_V3.csv")
	private String freightRawData;

	@CommandLine.Option(names = "--freightRawDataKEP", description = "Path to the KEP data", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/kep_aufkommen/aufkommen_kep.csv")
	String freightRawDataKEP;

	@CommandLine.Option(names = "--alsoRunCompleteCommercialTraffic", description = "Also run MATSim for the complete commercial traffic")
	private boolean alsoRunCompleteCommercialTraffic;

	@CommandLine.Option(names = "--MATSimIterations", description = "Number of MATSim iterations for the complete commercial traffic", defaultValue = "0")
	private int MATSimIterations;

	@CommandLine.Option(names = "--germanyFreightPlansFile", description = "Path to the Germany plans file", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.100pct.plans.xml.gz")
	private Path germanyPlansFile;

	@CommandLine.Option(names = "--networkForLongDistanceFreight", description = "Path to the network file for long distance freight", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz")
	private Path networkForLongDistanceFreight;

	@CommandLine.Option(names = "--outputPlansPath", description = "Path to the output plans file")
	private String outputPlansPath;

	public static void main(String[] args) {
		System.exit(new CommandLine(new CreateCommercialDemand()).execute(args));
	}

	@Override
	public Integer call() {

		alsoRunCompleteCommercialTraffic = true;

		if (!Files.exists(output)) {
			try {
				Files.createDirectories(output);
			} catch (Exception e) {
				log.error("Could not create output directory", e);
				return 1;
			}
		}

		String shapeCRS = "EPSG:25832";
		log.info("1st step - create freight data from BUW data");

		String LTLFreightPopulationName = "ruhr_LTL_freightPlans_" + (int) (sample * 100) + "pct.plans.xml.gz";
		String FTLFreightPopulationName = LTLFreightPopulationName.replace("LTL", "FTL");

		String freightDataName = "ruhr_freightData_100pct.xml.gz";

		if (Files.exists(output.resolve(freightDataName)) || Files.exists(freightData)) {
			log.warn("Freight data already exists. Skipping generation.");
		} else {
			new GenerateFreightDataRuhr().execute(
				"--data", freightRawData,
				"--KEPdata", freightRawDataKEP,
				"--pathOutput", output.toString(),
				"--nameOutputDataFile", freightDataName,
				"--shpCells", vpCellsLocation.toString()
			);
		}

		log.info("2rd step - create FTL freight plans from generated data");
		if (Files.exists(output.resolve(FTLFreightPopulationName))) {
			log.warn("Freight population already exists. Skipping generation.");
		} else {
			new GenerateFTLFreightPlansRuhr().execute(
				"--data", output.resolve(freightDataName).toString(),
				"--output", output.toString(),
				"--nameOutputPopulation", FTLFreightPopulationName,
				"--truck-load", "13.0",
				"--working-days", "260",
				"--max-kilometer-for-return-journey", "200",
				"--sample", String.valueOf(sample)
			);
		}

		log.info("3rd step - create LTL freight plans from generated data");
		if (Files.exists(output.resolve(LTLFreightPopulationName))) {
			log.warn("Freight population already exists. Skipping generation.");
		} else {
			new GenerateLTLFreightPlansRuhr().execute(
				"--data", output.resolve(freightDataName).toString(),
				"--network", configPath.getParent().resolve(networkPath).toString(),
				"--output", output.toString(),
				"--nameOutputPopulation", LTLFreightPopulationName,
				"--working-days", "260",
				"--sample", String.valueOf(sample),
				"--vehicleTypesFilePath", vehicleTypesFilePath,
				"--jsprit-iterations-for-LTL", String.valueOf(jspritIterationsForLTL)
			);
		}
		log.info("4rd step - create transit long distance freight traffic");
		String longDistanceFreightPopulationName = output.resolve(
			"ruhr_longDistanceFreight." + (int) (sample * 100) + "pct.plans.xml.gz").toString();
		if (Files.exists(Path.of(longDistanceFreightPopulationName))) {
			log.warn("Long distance freight population already exists. Skipping generation.");
		} else {
			new ExtractRelevantFreightTrips().execute(
				germanyPlansFile.toString(),
				"--network", networkForLongDistanceFreight.toString(),
				"--output", longDistanceFreightPopulationName,
				"--shp", osmDataLocation.resolve("regions_25832.shp").toString(),
				"--input-crs", shapeCRS,
				"--target-crs", shapeCRS,
				"--shp-crs", shapeCRS,
				"--cut-on-boundary",
				"--tripType", "TRANSIT"
			);
			Population population = PopulationUtils.readPopulation(longDistanceFreightPopulationName);
			log.info("Set mode to truck40t for long distance freight");
			for (Person person : population.getPersons().values()) {
				PopulationUtils.putSubpopulation(person, "longDistanceFreight");
				person.getSelectedPlan().getPlanElements().forEach(planElement -> {
					if (planElement instanceof Leg leg) {
						leg.setMode("truck40t");
					}
				});
			}
			PopulationUtils.sampleDown(population, sample);
			PopulationUtils.writePopulation(population, longDistanceFreightPopulationName);
		}
		log.info("5rd step - create input data for small scale commercial traffic");

		Path pathCommercialFacilities = output.resolve("commercialFacilities.xml.gz");
		//here possible to create an implementation for ruhrAGIS data
		LanduseDataConnectionCreator landuseDataConnectionCreator = new LanduseDataConnectionCreatorForOSM_Data();
		Path pathDataDistributionFile = output.resolve("dataDistributionPerZone.csv");
		if (Files.exists(pathCommercialFacilities)) {
			log.warn("Commercial facilities for small-scale commercial generation already exists. Skipping generation.");
		} else {
			new CreateDataDistributionOfStructureData(landuseDataConnectionCreator).execute(
				"--outputFacilityFile", pathCommercialFacilities.toString(),
				"--outputDataDistributionFile", pathDataDistributionFile.toString(),
				"--landuseConfiguration", "useOSMBuildingsAndLanduse",
				"--regionsShapeFileName", osmDataLocation.resolve("regions_25832.shp").toString(),
				"--regionsShapeRegionColumn", "GEN",
				"--zoneShapeFileName", osmDataLocation.resolve("zones_v2.0_25832.shp").toString(),
				"--zoneShapeFileNameColumn", "schluessel",
				"--buildingsShapeFileName", osmDataLocation.resolve("buildings_25832.shp").toString(),
				"--shapeFileBuildingTypeColumn", "building",
				"--landuseShapeFileName", osmDataLocation.resolve("landuse_v.1.0_25832.shp").toString(),
				"--shapeFileLanduseTypeColumn", "landuse",
				"--shapeCRS", shapeCRS,
				"--pathToInvestigationAreaData", pathToInvestigationAreaData
			);
		}
		log.info("6th step - create small scale commercial traffic");
		String smallScaleCommercialPopulationName = "ruhrSmallScaleCommercial." + (int) (sample * 100) + "pct.plans.xml.gz";
		String outputPathSmallScaleCommercial = output.resolve("smallScaleCommercial").toString();
		Path resolve = Path.of(outputPathSmallScaleCommercial).resolve(smallScaleCommercialPopulationName);
		IntegrateExistingTrafficToSmallScaleCommercial integrateExistingTrafficToSmallScaleCommercial = new IntegrationOfExistingCommercialTrafficRuhr(
			output.resolve(LTLFreightPopulationName));

		if (Files.exists(resolve)) {
			log.warn("Small-scale Commercial demand already exists. Skipping generation.");
		} else {
			//TODO check: Wo wird das Volumen der existierenden Modelle von den erzeugten Potentialen abgezogen?
			new GenerateSmallScaleCommercialTrafficDemand(integrateExistingTrafficToSmallScaleCommercial, null).execute(
				configPath.toString(),
				"--pathToDataDistributionToZones", pathDataDistributionFile.toString(),
				"--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
				"--sample", String.valueOf(sample),
				"--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
				"--creationOption", "createNewCarrierFile",
				"--smallScaleCommercialTrafficType", smallScaleCommercialTrafficType,
				"--zoneShapeFileName", osmDataLocation.resolve("zones_v2.0_25832.shp").toString(),
				"--zoneShapeFileNameColumn", "schluessel",
				"--shapeCRS", shapeCRS,
				"--pathOutput", outputPathSmallScaleCommercial,
				"--network", networkPath,
				"--nameOutputPopulation", smallScaleCommercialPopulationName,
				"--numberOfPlanVariantsPerAgent", "5",
				"--includeExistingModels");

			// TODO filter relevant agents for the small scale commercial traffic
		}
		log.info("7th step - Merge freight and commercial populations");
		String pathMergedPopulation;
		if (outputPlansPath != null) {
			pathMergedPopulation = outputPlansPath;
		} else {
			pathMergedPopulation = output.resolve(LTLFreightPopulationName).toString().replace("_LTL", "").replace(".plans.xml.gz",
				"") + "_merged.plans.xml.gz";
		}
		if (Files.exists(Path.of(pathMergedPopulation))) {
			log.info("Merged demand already exists. Skipping generation.");
		} else {
			new MergePopulations().execute(
				output.resolve(LTLFreightPopulationName).toString(),
				output.resolve(FTLFreightPopulationName).toString(),
				outputPathSmallScaleCommercial + "/" + smallScaleCommercialPopulationName,
				longDistanceFreightPopulationName,
				"--output", pathMergedPopulation
			);
		}

		if (alsoRunCompleteCommercialTraffic) {
			//TODO perhaps check if this can be moved to RunMetropoleRuhrScenario
			Config config = ConfigUtils.loadConfig(configPath.toString());
			config.plans().setInputFile(configPath.getParent().relativize(Path.of(pathMergedPopulation)).toString());
			config.plans().setActivityDurationInterpretation(PlansConfigGroup.ActivityDurationInterpretation.tryEndTimeThenDuration);
			config.network().setInputFile(networkPath);
			config.controller().setOutputDirectory(output.resolve("commercialTraffic_Run" + (int) (sample * 100) + "pct").toString());
			config.controller().setLastIteration(MATSimIterations);
			config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
			config.transit().setUseTransit(false);
			config.transit().setTransitScheduleFile(null);
			config.transit().setVehiclesFile(null);
			config.global().setCoordinateSystem("EPSG:25832");
			config.counts().setInputFile(null);
			config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
			config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
			config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
			config.qsim().setUsingTravelTimeCheckInTeleportation(true);
			config.qsim().setUsePersonIdForMissingVehicleId(false);
			//to get no traffic jam for the 1 iteration
			config.qsim().setFlowCapFactor(sample * 4);
			config.qsim().setStorageCapFactor(sample * 4);
			config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
			config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);
			config.getModules().remove("intermodalTripFareCompensators");
			config.getModules().remove("ptExtensions");
			config.getModules().remove("ptIntermodalRoutingModes");
			config.getModules().remove("swissRailRaptor");
			config.controller().setRunId("commercialTraffic_Run" + (int) (sample * 100) + "pct");
			MetropoleRuhrScenario.prepareCommercialTrafficConfig(config);

			Scenario scenario = ScenarioUtils.loadScenario(config);

			Controler controller = new Controler(scenario);

			controller.addOverridingModule(new SimWrapperModule());


			controller.run();
		}
		return 0;
	}
}

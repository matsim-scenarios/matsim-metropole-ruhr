package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;
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
import org.matsim.run.MetropoleRuhrScenario;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreator;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreatorForOSM_Data;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class is used to create the basic commercial demand for the Ruhr area. It generates the following parts of freight traffic:
 * <ul>
 *     <li>Long distance Transit freight traffic</li>
 *     <li>Small scale commercial traffic</li>
 * </ul>
 * @author Ricardo Ewert
 */
public class CreateCommercialDemand_Basic implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateCommercialDemand_Basic.class);

	@CommandLine.Option(names = "--sample", description = "Scaling factor of the small scale commercial traffic (0, 1)", required = true, defaultValue = "0.1")
	private double sample;

	@CommandLine.Option(names = "--generatedInputDataPath", description = "Path to the generated input data", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/output/rvr/generatedInputData")
	private Path generatedInputDataPath;

	@CommandLine.Option(names = "--pathOutputFolder", description = "Path for the output folder", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/output/rvr/testing/commercial_0.1pct")
	private Path output;

	@CommandLine.Option(names = "--osmDataLocation", description = "Path to the OSM data location", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/")
	private Path osmDataLocation;

	@CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-3pct.config.xml")
	private Path configPath;

	@CommandLine.Option(names = "--pathToInvestigationAreaData", description = "Path to the investigation area data", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/input/investigationAreaData.csv")
	private String pathToInvestigationAreaData;

	@CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "metropole-ruhr-v2024.0.network_resolutionHigh.xml.gz")
	private String networkPath;

	@CommandLine.Option(names = "--jspritIterationsForSmallScaleCommercial", defaultValue = "10", description = "Number of iterations for jsprit for solving the small scale commercial traffic", required = true)
	private int jspritIterationsForSmallScaleCommercial;

	@CommandLine.Option(names = "--smallScaleCommercialGenerationOption", description = "Select generation option. Options: useExistingCarrierFileWithSolution, createNewCarrierFile, useExistingCarrierFileWithoutSolution", defaultValue = "createNewCarrierFile")
	private String smallScaleCommercialGenerationOption;

	@CommandLine.Option(names = "--nameOfExistingCarriersSmallScaleCommercial", description = "Path to the existing carriers file")
	private String nameOfExistingCarriersSmallScaleCommercial;

	@CommandLine.Option(names = "--additionalTravelBufferPerIterationInMinutes", description = "Additional buffer for the travel time", defaultValue = "30")
	private int additionalTravelBufferPerIterationInMinutes;

	@CommandLine.Option(names = "--alsoRunCompleteCommercialTraffic", description = "Also run MATSim for the complete commercial traffic")
	private boolean alsoRunCompleteCommercialTraffic;

	@CommandLine.Option(names = "--MATSimIterations", description = "Number of MATSim iterations for the complete commercial traffic", defaultValue = "0")
	private int MATSimIterations;

	@CommandLine.Option(names = "--germanyFreightPlansFile", description = "Path to the Germany plans file", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.100pct.plans.xml.gz")
	private Path germanyPlansFile;

	@CommandLine.Option(names = "--networkForLongDistanceFreight", description = "Path to the network file for long distance freight", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz")
	private Path networkForLongDistanceFreight;

	@CommandLine.Option(names = "--cutFreightTransitAtBoundary", description = "Cut freight transit at boundary")
	private boolean cutFreightTransitAtBoundary;

	@CommandLine.Option(names = "--outputPlansPath", description = "Path to the output plans file")
	private String outputPlansPath;

	@CommandLine.Option(names = "--resistanceFactorForKWM_person", defaultValue = "0.2", description = "ResistanceFactor for the trip distribution")
	private double resistanceFactorForKWM_person;

	@CommandLine.Option(names = "--resistanceFactorForKWM_freight", defaultValue = "0.1", description = "ResistanceFactor for the trip distribution")
	private double resistanceFactorForKWM_freight;

	public static void main(String[] args) {
		System.exit(new CommandLine(new CreateCommercialDemand_Basic()).execute(args));
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
		if (!Files.exists(generatedInputDataPath)) {
			try {
				Files.createDirectories(generatedInputDataPath);
			} catch (Exception e) {
				log.error("Could not create output directory", e);
				return 1;
			}
		}

		String shapeCRS = "EPSG:25832";

		log.info("1rd step - create transit long distance freight traffic");
		String longDistanceFreightPopulationName = output.resolve(
			"ruhr_longDistanceFreight." + (int) (sample * 100) + "pct.plans.xml.gz").toString();
		if (Files.exists(Path.of(longDistanceFreightPopulationName))) {
			log.warn("Long distance freight population already exists. Skipping generation.");
		} else {
			List<String> argumentsForFreightTransitTraffic = new ArrayList<>();
			argumentsForFreightTransitTraffic.add(germanyPlansFile.toString());
			argumentsForFreightTransitTraffic.add("--network");
			argumentsForFreightTransitTraffic.add(networkForLongDistanceFreight.toString());
			argumentsForFreightTransitTraffic.add("--output");
			argumentsForFreightTransitTraffic.add(longDistanceFreightPopulationName);
			argumentsForFreightTransitTraffic.add("--shp");
			argumentsForFreightTransitTraffic.add(osmDataLocation.resolve("regions_25832.shp").toString());
			argumentsForFreightTransitTraffic.add("--input-crs");
			argumentsForFreightTransitTraffic.add(shapeCRS);
			argumentsForFreightTransitTraffic.add("--target-crs");
			argumentsForFreightTransitTraffic.add(shapeCRS);
			argumentsForFreightTransitTraffic.add("--shp-crs");
			argumentsForFreightTransitTraffic.add(shapeCRS);
			argumentsForFreightTransitTraffic.add("--geographicalTripType");
			argumentsForFreightTransitTraffic.add("ALL");
			argumentsForFreightTransitTraffic.add("--legMode");
			argumentsForFreightTransitTraffic.add("truck40t");
			if (cutFreightTransitAtBoundary) {
				argumentsForFreightTransitTraffic.add("--cut-on-boundary");
			}

			new ExtractRelevantFreightTrips().execute(argumentsForFreightTransitTraffic.toArray(new String[0]));

			Population population = PopulationUtils.readPopulation(longDistanceFreightPopulationName);
			log.info("Set mode to truck40t for long distance freight");
			for (Person person : population.getPersons().values()) {
				PopulationUtils.putSubpopulation(person, "longDistanceFreight");
			}
			PopulationUtils.sampleDown(population, sample);
			PopulationUtils.writePopulation(population, longDistanceFreightPopulationName);
		}
		log.info("2rd step - create input data for small scale commercial traffic");

		Path pathCommercialFacilities = generatedInputDataPath.resolve("commercialFacilities.xml.gz");
		//here possible to create an implementation for ruhrAGIS data
		LanduseDataConnectionCreator landuseDataConnectionCreator = new LanduseDataConnectionCreatorForOSM_Data();
		Path pathDataDistributionFile = generatedInputDataPath.resolve("dataDistributionPerZone.csv");
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
		log.info("3th step - create small scale commercial person traffic");
		String smallScaleCommercialPersonPopulationName = "ruhrSmallScaleCommercialPersons." + (int) (sample * 100) + "pct.plans.xml.gz";
		String outputPathSmallScaleCommercial_person = output.resolve("smallScaleCommercialPerson").toString();
		Path resolve = Path.of(outputPathSmallScaleCommercial_person).resolve(smallScaleCommercialPersonPopulationName);

		if (Files.exists(resolve)) {
			log.warn("Small-scale Commercial demand already exists. Skipping generation.");
		} else {
			String[] args = {configPath.toString(),
				"--pathToDataDistributionToZones", pathDataDistributionFile.toString(),
				"--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
				"--sample", String.valueOf(sample),
				"--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
				"--creationOption", smallScaleCommercialGenerationOption,
				"--smallScaleCommercialTrafficType", "commercialPersonTraffic",
				"--zoneShapeFileName", osmDataLocation.resolve("zones_v2.0_25832.shp").toString(),
				"--zoneShapeFileNameColumn", "schluessel",
				"--shapeCRS", shapeCRS,
				"--pathOutput", outputPathSmallScaleCommercial_person,
				"--network", networkPath,
				"--nameOutputPopulation", smallScaleCommercialPersonPopulationName,
				"--numberOfPlanVariantsPerAgent", "5",
				"--additionalTravelBufferPerIterationInMinutes", String.valueOf(additionalTravelBufferPerIterationInMinutes),
				"--resistanceFactor", String.valueOf(resistanceFactorForKWM_person)};
			if (smallScaleCommercialGenerationOption.equals("useExistingCarrierFileWithoutSolution")) {
				args = Arrays.copyOf(args, args.length + 2);
				args[args.length - 2] = "--carrierFilePath";
				args[args.length - 1] = configPath.getParent().relativize(
					Path.of(outputPathSmallScaleCommercial_person).resolve(nameOfExistingCarriersSmallScaleCommercial)).toString();
			}
			// TODO filter relevant agents for the small scale commercial traffic
			new GenerateSmallScaleCommercialTrafficDemand(null, null, null, null).execute(
				args);
		}

		log.info("4th step - create small scale commercial Freight traffic");
		String smallScaleCommercialFreightPopulationName = "ruhrSmallScaleCommercialFreight." + (int) (sample * 100) + "pct.plans.xml.gz";
		String outputPathSmallScaleCommercialFreight = output.resolve("smallScaleCommercialFreight").toString();
		Path resolveKWM_freight = Path.of(outputPathSmallScaleCommercialFreight).resolve(smallScaleCommercialPersonPopulationName);

		if (Files.exists(resolveKWM_freight)) {
			log.warn("Small-scale Commercial demand already exists. Skipping generation.");
		} else {
			String[] args = {configPath.toString(),
				"--pathToDataDistributionToZones", pathDataDistributionFile.toString(),
				"--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
				"--sample", String.valueOf(sample),
				"--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
				"--creationOption", smallScaleCommercialGenerationOption,
				"--smallScaleCommercialTrafficType", "goodsTraffic",
				"--zoneShapeFileName", osmDataLocation.resolve("zones_v2.0_25832.shp").toString(),
				"--zoneShapeFileNameColumn", "schluessel",
				"--shapeCRS", shapeCRS,
				"--pathOutput", outputPathSmallScaleCommercialFreight,
				"--network", networkPath,
				"--nameOutputPopulation", smallScaleCommercialFreightPopulationName,
				"--numberOfPlanVariantsPerAgent", "5",
				"--additionalTravelBufferPerIterationInMinutes", String.valueOf(additionalTravelBufferPerIterationInMinutes),
				"--resistanceFactor", String.valueOf(resistanceFactorForKWM_freight)};
			if (smallScaleCommercialGenerationOption.equals("useExistingCarrierFileWithoutSolution")) {
				args = Arrays.copyOf(args, args.length + 2);
				args[args.length - 2] = "--carrierFilePath";
				args[args.length - 1] = configPath.getParent().relativize(
					Path.of(outputPathSmallScaleCommercialFreight).resolve(nameOfExistingCarriersSmallScaleCommercial)).toString();
			}
			// TODO filter relevant agents for the small scale commercial traffic
			new GenerateSmallScaleCommercialTrafficDemand(null, null, null, null).execute(
				args);
		}

		log.info("5th step - Merge freight and commercial populations");
		String pathMergedPopulation;
		if (outputPlansPath != null) {
			pathMergedPopulation = outputPlansPath;
		} else {
			pathMergedPopulation = output.resolve(longDistanceFreightPopulationName).toString().replace("longDistanceFreight", "commercial_basic").replace(".plans.xml.gz",
				"") + "_merged.plans.xml.gz";
		}
		if (Files.exists(Path.of(pathMergedPopulation))) {
			log.info("Merged demand already exists. Skipping generation.");
		} else {
			new MergePopulations().execute(
				outputPathSmallScaleCommercial_person + "/" + smallScaleCommercialPersonPopulationName,
				outputPathSmallScaleCommercialFreight + "/" + smallScaleCommercialFreightPopulationName,
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
			SimWrapperConfigGroup simWrapperConfigGroup = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
			simWrapperConfigGroup.setSampleSize(sample);
			Scenario scenario = ScenarioUtils.loadScenario(config);

			Controler controller = new Controler(scenario);

			controller.addOverridingModule(new SimWrapperModule());


			controller.run();
		}
		return 0;
	}
}

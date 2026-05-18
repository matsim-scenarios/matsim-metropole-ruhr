package org.matsim.prepare;

import com.google.common.collect.Sets;
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
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.VehicleTypeBasedScoringFunctionFactory;
import org.matsim.prepare.commercial.CommercialVehicleSelectorRuhr;
import org.matsim.run.MetropoleRuhrScenario;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.simwrapper.dashboard.CommercialTrafficDashboard;
import org.matsim.simwrapper.dashboard.OverviewDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.VehicleTypeSelection;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.CreateDataDistributionOfStructureData;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreator;
import org.matsim.smallScaleCommercialTrafficGeneration.prepare.LanduseDataConnectionCreatorForOSM_Data;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * This class is used to create the basic commercial demand for the Ruhr area. It generates the following parts:
 * <ul>
 *     <li>Long distance freight traffic</li>
 *     <li>Small scale commercial traffic</li>
 * </ul>
 * @author Ricardo Ewert
 */
public class CreateCommercialDemand_Basic implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateCommercialDemand_Basic.class);

	private enum RunPart {
		all,
		longDistanceFreight,
		smallScaleInputData,
		smallScaleCommercial,
		smallScaleCommercialPerson,
		smallScaleCommercialGoods,
		smallScaleCommercialMerge,
		merge,
		matsim
	}

	@CommandLine.Option(names = "--runPart", description = "Part of the workflow to run: ${COMPLETION-CANDIDATES}", defaultValue = "matsim")
	private RunPart runPart;

	@CommandLine.Option(names = "--sample", description = "Scaling factor of the small scale commercial traffic (0, 1)", required = true, defaultValue = "0.001")
	private double sample;

	@CommandLine.Option(names = "--generatedInputDataPath", description = "Path to the generated input data", required = true, defaultValue = "output/studyWMRuhr_CV_basic/generatedInputData")
	private Path generatedInputDataPath;

	@CommandLine.Option(names = "--pathOutputFolder", description = "Path for the output folder", required = true, defaultValue = "output/studyWMRuhr_CV_basic/commercial_0.1pct")
	private Path output;

	@CommandLine.Option(names = "--osmDataLocation", description = "Path to the OSM data location", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/")
	private Path osmDataLocation;

	@CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.2/input/metropole-ruhr-v2024.2-10pct.config.xml")
	private Path configPath;

	@CommandLine.Option(names = "--pathToInvestigationAreaData", description = "Path to the investigation area data", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.2/input/investigationAreaData.csv")
	private String pathToInvestigationAreaData;

	@CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "metropole-ruhr-v2024.2.network_noBikeAndPt.xml.gz")
	private String networkPath;

	@CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.2/input/metropole-ruhr-v2024.2.mode-vehicles_WV_base2025_CV.xml")
	private String vehicleTypesFilePath;

	@CommandLine.Option(names = "--jspritIterationsForSmallScaleCommercial", defaultValue = "10", description = "Number of iterations for jsprit for solving the small scale commercial traffic", required = true)
	private int jspritIterationsForSmallScaleCommercial;

	@CommandLine.Option(names = "--smallScaleCommercialTrafficType", description = "Select traffic type. Options: commercialPersonTraffic, goodsTraffic, completeSmallScaleCommercialTraffic (contains both types)", defaultValue = "completeSmallScaleCommercialTraffic")
	private String smallScaleCommercialTrafficType;

	@CommandLine.Option(names = "--smallScaleCommercialGenerationOption", description = "Select generation option. Options: useExistingCarrierFileWithSolution, createNewCarrierFile, useExistingCarrierFileWithoutSolution", defaultValue = "createNewCarrierFile")
	private String smallScaleCommercialGenerationOption;

	@CommandLine.Option(names = "--nameOfExistingCarriersSmallScaleCommercial", description = "Path to the existing carriers file")
	private String nameOfExistingCarriersSmallScaleCommercial;

	@CommandLine.Option(names = "--additionalTravelBufferPerIterationInMinutes", description = "Additional buffer for the travel time", defaultValue = "60")
	private int additionalTravelBufferPerIterationInMinutes;

	@CommandLine.Option(names = "--factorForTravelBufferCalculation", description = "The factor describing how many vehicles should be created in relation to the number of created services. If maxNumberOfLoopsForVRPSolving > 0 more vehicles are added in the replanning process.", defaultValue = "1.2")
	private double factorForTravelBufferCalculation;

	@CommandLine.Option(names = "--alsoRunCompleteCommercialTraffic", description = "Also run MATSim for the complete commercial traffic")
	private boolean alsoRunCompleteCommercialTraffic;

	@CommandLine.Option(names = "--MATSimIterations", description = "Number of MATSim iterations for the complete commercial traffic", defaultValue = "0")
	private int MATSimIterations;

	@CommandLine.Option(names = "--MATSimIterationsKWM", description = "Number of MATSim iterations for the small-scale commercial traffic", defaultValue = "0")
	private int MATSimIterationsKWM;

	@CommandLine.Option(names = "--germanyFreightPlansFile", description = "Path to the Germany plans file", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.100pct.plans.xml.gz")
	private Path germanyPlansFile;

	@CommandLine.Option(names = "--networkForLongDistanceFreight", description = "Path to the network file for long distance freight", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz")
	private Path networkForLongDistanceFreight;

	@CommandLine.Option(names = "--cutFreightTransitAtBoundary", description = "Cut freight transit at boundary")
	private boolean cutFreightTransitAtBoundary;

	@CommandLine.Option(names = "--outputPlansPath", description = "Path to the output plans file")
	private String outputPlansPath;

	@CommandLine.Option(names = "--resistanceFactorForKWM_goodsTraffic", defaultValue = "0.2", description = "ResistanceFactor of the goodsTraffic for the trip distribution in the small scale commercial model.")
	private double resistanceFactorForKWM_goodsTraffic;

	@CommandLine.Option(names = "--resistanceFactorForKWM_commercialPersonTraffic", defaultValue = "0.1", description = "ResistanceFactor of the commercialPersonTraffic for the trip distribution in the small scale commercial model.")
	private double resistanceFactorForKWM_commercialPersonTraffic;

	@CommandLine.Option(names = "--networkChangeEventsFile", description = "Path to the network change events file. If no file is set, no networkChangeEvents are used.")
	private Path networkChangeEventsFile;

	@CommandLine.Option(names = "--useRangeConstraintForJspritTourPlanning", description = "Option to use range constraint for jsprit tour planning. If this is selected, the range is restricted based on consumption information in the vehicle types file.")
	private boolean useRangeConstraintForJspritTourPlanning;

	public static void main(String[] args) {
		System.exit(new CommandLine(new CreateCommercialDemand_Basic()).execute(args));
	}

	@Override
	public Integer call() {

		if (runPart == RunPart.all) {
			alsoRunCompleteCommercialTraffic = true;
		}

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

		String longDistanceFreightPopulationName = output.resolve(
			"ruhr_longDistanceFreight." + (int) (sample * 100) + "pct.plans.xml.gz").toString();
		if (runPart == RunPart.all || runPart == RunPart.longDistanceFreight) {
			log.info("1st step - create long distance freight traffic");
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
			if (runPart == RunPart.longDistanceFreight) {
				return 0;
			}
		}

		Path pathCommercialFacilities = generatedInputDataPath.resolve("commercialFacilities.xml.gz");
		LanduseDataConnectionCreator landuseDataConnectionCreator = new LanduseDataConnectionCreatorForOSM_Data();
		Path pathDataDistributionFile = generatedInputDataPath.resolve("dataDistributionPerZone.csv");
		if (runPart == RunPart.all || runPart == RunPart.smallScaleInputData) {
			log.info("2nd step - create input data for small scale commercial traffic");
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
			if (runPart == RunPart.smallScaleInputData) {
				return 0;
			}
		}

		String smallScaleCommercialPopulationName = "ruhrSmallScaleCommercial." + (int) (sample * 100) + "pct.plans.xml.gz";
		String smallScaleCommercialPersonPopulationName = smallScaleCommercialPopulationName.replace(".plans.xml.gz", "_commercialPersonTraffic.plans.xml.gz");
		String smallScaleCommercialGoodsPopulationName = smallScaleCommercialPopulationName.replace(".plans.xml.gz", "_goodsTraffic.plans.xml.gz");
		String outputPathSmallScaleCommercial = output.resolve("smallScaleCommercial").toString();
		String outputPathSmallScaleCommercialPerson = output.resolve("smallScaleCommercial").resolve("commercialPersonTraffic").toString();
		String outputPathSmallScaleCommercialGoods = output.resolve("smallScaleCommercial").resolve("goodsTraffic").toString();
		Path mergedSmallScaleCommercialPopulationPath = Path.of(outputPathSmallScaleCommercial).resolve(smallScaleCommercialPopulationName);
		VehicleTypeSelection vehicleTypeSelection = new CommercialVehicleSelectorRuhr();

		if (runPart == RunPart.all || runPart == RunPart.smallScaleCommercial || runPart == RunPart.smallScaleCommercialPerson || runPart == RunPart.smallScaleCommercialGoods) {
			log.info("3rd step - create small scale commercial traffic");
			String selectedSmallScaleCommercialTrafficType = smallScaleCommercialTrafficType;
			String selectedOutputPathSmallScaleCommercial = outputPathSmallScaleCommercial;
			String selectedSmallScaleCommercialPopulationName = smallScaleCommercialPopulationName;

			if (runPart == RunPart.smallScaleCommercialPerson) {
				selectedSmallScaleCommercialTrafficType = "commercialPersonTraffic";
				selectedOutputPathSmallScaleCommercial = outputPathSmallScaleCommercialPerson;
				selectedSmallScaleCommercialPopulationName = smallScaleCommercialPersonPopulationName;
			} else if (runPart == RunPart.smallScaleCommercialGoods) {
				selectedSmallScaleCommercialTrafficType = "goodsTraffic";
				selectedOutputPathSmallScaleCommercial = outputPathSmallScaleCommercialGoods;
				selectedSmallScaleCommercialPopulationName = smallScaleCommercialGoodsPopulationName;
			}

			Path selectedSmallScaleCommercialPopulationPath = Path.of(selectedOutputPathSmallScaleCommercial).resolve(selectedSmallScaleCommercialPopulationName);
			if (Files.exists(selectedSmallScaleCommercialPopulationPath)) {
				log.warn("Small-scale Commercial demand already exists. Skipping generation.");
			} else {
				List<String> args = new ArrayList<>(List.of(configPath.toString(),
					"--pathToDataDistributionToZones", pathDataDistributionFile.toString(),
					"--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
					"--sample", String.valueOf(sample),
					"--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
					"--creationOption", smallScaleCommercialGenerationOption,
					"--smallScaleCommercialTrafficType", selectedSmallScaleCommercialTrafficType,
					"--zoneShapeFileName", osmDataLocation.resolve("zones_v2.0_25832.shp").toString(),
					"--zoneShapeFileNameColumn", "schluessel",
					"--shapeCRS", shapeCRS,
					"--pathOutput", selectedOutputPathSmallScaleCommercial,
					"--network", networkPath,
					"--nameOutputPopulation", selectedSmallScaleCommercialPopulationName,
					"--numberOfPlanVariantsPerAgent", "5",
					"--additionalTravelBufferPerIterationInMinutes", String.valueOf(additionalTravelBufferPerIterationInMinutes),
					"--factorForTravelBufferCalculation", String.valueOf(factorForTravelBufferCalculation),
					"--maxNumberOfLoopsForVRPSolving", "100",
					"--resistanceFactor_commercialPersonTraffic", String.valueOf(resistanceFactorForKWM_commercialPersonTraffic),
					"--resistanceFactor_goodsTraffic", String.valueOf(resistanceFactorForKWM_goodsTraffic)));
				if (MATSimIterationsKWM >= 0) {
					args.add("--MATSimIterationsAfterDemandGeneration");
					args.add(String.valueOf(MATSimIterationsKWM));
				}
				if (smallScaleCommercialGenerationOption.equals("useExistingCarrierFileWithoutSolution") || smallScaleCommercialGenerationOption.equals("useExistingCarrierFileWithSolution")) {
					args.add("--carrierFilePath");
					args.add(configPath.getParent().relativize(
						Path.of(selectedOutputPathSmallScaleCommercial).resolve(nameOfExistingCarriersSmallScaleCommercial)).toString());
				}
				List<String> configArgs = new ArrayList<>(List.of("--config:vehicles.vehiclesFile", configPath.getParent().relativize(Path.of(vehicleTypesFilePath)).toString()));
				configArgs.add("--config:transit.useTransit");
				configArgs.add("false");
				configArgs.add("--config:routing.networkModes");
				configArgs.add("truck8t,truck40t,truck18t,car,truck26t");
				if (networkChangeEventsFile != null) {
					configArgs.add("--config:network.inputChangeEventsFile");
					configArgs.add(configPath.getParent().relativize(networkChangeEventsFile).toString());
					configArgs.add("--config:network.timeVariantNetwork");
					configArgs.add("true");
				}
				if (useRangeConstraintForJspritTourPlanning) {
					configArgs.add("--useRangeConstraintForTourPlanning");
				}
				new GenerateSmallScaleCommercialTrafficDemand(configArgs.toArray(new String[0]), null, null,
					null, vehicleTypeSelection, null).execute(args.toArray(new String[0]));
			}
			if (runPart == RunPart.smallScaleCommercial || runPart == RunPart.smallScaleCommercialPerson || runPart == RunPart.smallScaleCommercialGoods) {
				return 0;
			}
		}

		if (runPart == RunPart.smallScaleCommercialMerge) {
			log.info("3b step - merge small scale commercial traffic segments");
			if (Files.exists(mergedSmallScaleCommercialPopulationPath)) {
				log.warn("Small-scale Commercial demand already exists. Skipping generation.");
			} else {
				new MergePopulations().execute(
					Path.of(outputPathSmallScaleCommercialPerson).resolve(smallScaleCommercialPersonPopulationName).toString(),
					Path.of(outputPathSmallScaleCommercialGoods).resolve(smallScaleCommercialGoodsPopulationName).toString(),
					"--output", mergedSmallScaleCommercialPopulationPath.toString()
				);
			}
			return 0;
		}

		String pathMergedPopulation;
		if (outputPlansPath != null) {
			pathMergedPopulation = outputPlansPath;
		} else {
			pathMergedPopulation = output.resolve("ruhr_commercial_basic_" + (int) (sample * 100) + "pct.plans.xml.gz").toString();
		}
		if (runPart == RunPart.all || runPart == RunPart.merge) {
			log.info("4th step - merge freight and commercial populations");
			if (Files.exists(Path.of(pathMergedPopulation))) {
				log.info("Merged demand already exists. Skipping generation.");
			} else {
				new MergePopulations().execute(
					mergedSmallScaleCommercialPopulationPath.toString(),
					longDistanceFreightPopulationName,
					"--output", pathMergedPopulation
				);
			}
			if (runPart == RunPart.merge) {
				return 0;
			}
		}

		if (alsoRunCompleteCommercialTraffic || runPart == RunPart.matsim) {
			Config config = ConfigUtils.loadConfig(configPath.toString());
			config.plans().setInputFile(configPath.getParent().relativize(Path.of(pathMergedPopulation)).toString());
			config.network().setInputFile(networkPath);
			if (networkChangeEventsFile != null) {
				config.network().setChangeEventsInputFile(configPath.getParent().relativize(networkChangeEventsFile).toString());
				config.network().setTimeVariantNetwork(true);
				log.info("Using network change events for complete MATSim run from file: {}", networkChangeEventsFile.toString());
			}
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
			config.qsim().setFlowCapFactor(sample);
			config.qsim().setStorageCapFactor(sample);
			config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
			config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);
			config.getModules().remove("intermodalTripFareCompensators");
			config.getModules().remove("ptExtensions");
			config.getModules().remove("ptIntermodalRoutingModes");
			config.getModules().remove("swissRailRaptor");
			config.controller().setRunId("commercialTraffic_Run" + (int) (sample * 100) + "pct");
			config.controller().setCompressionType(ControllerConfigGroup.CompressionType.gzip);
			SimWrapper sw = SimWrapper.create(config);
			sw.getConfigGroup().defaultParams().setShp(null);
			sw.getConfigGroup().setDefaultDashboards(SimWrapperConfigGroup.DefaultDashboardsMode.disabled);
			sw.getConfigGroup().setSampleSize(sample);
			sw.addDashboard(new OverviewDashboard(Set.copyOf(config.qsim().getMainModes())));
			String subpopSetterForDashboards = "commercialPersonTraffic=commercialPersonTraffic,commercialPersonTraffic_service;smallScaleGoodsTraffic=goodsTraffic;longDistanceFreight=longDistanceFreight";
			sw.addDashboard(new TripDashboard().setGroupsOfSubpopulationsForCommercialAnalysis(subpopSetterForDashboards).setAnalysisArgs("--shp-filter", "none"));
			sw.addDashboard(new CommercialTrafficDashboard(config.global().getCoordinateSystem()).setGroupsOfSubpopulationsForCommercialAnalysis(subpopSetterForDashboards));
			config.vehicles().setVehiclesFile(configPath.getParent().relativize(Path.of(vehicleTypesFilePath)).toString());
			config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
			config.scoring().setExplainScores(true);

			Set<String> modes = Set.of("car", "truck8t", "truck18t", "truck26t", "truck40t");
			Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
			config.qsim().setMainModes(Sets.union(qsimModes, modes));
			config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
			config.routing().setNetworkModes(modes);

			Scenario scenario = ScenarioUtils.loadScenario(config);

			MetropoleRuhrScenario.prepareCommercialTrafficReplanningAndScoringParams(scenario);

			Controller controller = ControllerUtils.createController(scenario);

			controller.addOverridingModule(new SimWrapperModule(sw));
			controller.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					bind(ScoringFunctionFactory.class).to(VehicleTypeBasedScoringFunctionFactory.class);
				}
			});
			controller.run();
		}
		return 0;
	}
}

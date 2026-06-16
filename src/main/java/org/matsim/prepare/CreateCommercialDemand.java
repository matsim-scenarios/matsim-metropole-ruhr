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
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.VehicleTypeBasedScoringFunctionFactory;
import org.matsim.prepare.commercial.*;
import org.matsim.run.MetropoleRuhrScenario;
import org.matsim.freight.carriers.splitter.CarrierSplitter;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.simwrapper.dashboard.CarrierDashboard;
import org.matsim.simwrapper.dashboard.CommercialTrafficDashboard;
import org.matsim.simwrapper.dashboard.OverviewDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.IntegrateExistingTrafficToSmallScaleCommercial;
import org.matsim.smallScaleCommercialTrafficGeneration.RangeAwareUnhandledServicesSolution;
import org.matsim.smallScaleCommercialTrafficGeneration.VehicleTypeSelection;
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

	private enum RunPart {
		all,
		freightData,
		ftl,
		ltl,
		ltlRest,
		ltlWaste,
		ltlParcel,
		ltlRestInit,
		ltlWasteInit,
		ltlParcelInit,
		ltlRestMerge,
		ltlWasteMerge,
		ltlParcelMerge,
		ltlMerge,
		longDistanceFreight,
		smallScaleInputData,
		smallScaleCommercialPersonInit,
		smallScaleCommercialGoodsInit,
		smallScaleCommercial,
		smallScaleCommercialPerson,
		smallScaleCommercialGoods,
		smallScaleCommercialPersonCarrierMerge,
		smallScaleCommercialGoodsCarrierMerge,
		smallScaleCommercialMerge,
		merge,
		matsim
	}

	@CommandLine.Option(names = "--runPart", description = "Part of the workflow to run: ${COMPLETION-CANDIDATES}", defaultValue = "all")
	private RunPart runPart;

	@CommandLine.Option(names = "--sample", description = "Scaling factor of the small scale commercial traffic (0, 1)", required = true, defaultValue = "0.001")
	private double sample;

	@CommandLine.Option(names = "--generatedInputDataPath", description = "Path to the generated input data", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/output/rvr/generatedInputData")
	private Path generatedInputDataPath;

	@CommandLine.Option(names = "--pathOutputFolder", description = "Path for the output folder", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/output/rvr/testing/commercial_0.1pct")
	private Path output;

	@CommandLine.Option(names = "--freightData", description = "Name of the freight population", defaultValue = "ruhr_freightPlans_100pct.plans.xml.gz")
	private Path freightData;

	@CommandLine.Option(names = "--osmDataLocation", description = "Path to the OSM data location", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/osm/")
	private Path osmDataLocation;

	@CommandLine.Option(names = "--vpCellsLocation", description = "Path to the cell of the 'Verkehrsprognose (VP)' ", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040.shp")
	private Path vpCellsLocation;

	@CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.2/input/metropole-ruhr-v2024.2-10pct.config.xml")
	private Path configPath;

	@CommandLine.Option(names = "--pathToInvestigationAreaData", description = "Path to the investigation area data", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.2/input/investigationAreaData.csv")
	private String pathToInvestigationAreaData;

	@CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "metropole-ruhr-v2024.0.network_resolutionHigh.xml.gz")
	private String networkPath;

	@CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0.mode-vehicles.xml")
	private String vehicleTypesFilePath;

	@CommandLine.Option(names = "--jspritIterationsForLTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
	private int jspritIterationsForLTL;

	@CommandLine.Option(names = "--jspritIterationsForSmallScaleCommercial", defaultValue = "10", description = "Number of iterations for jsprit for solving the small scale commercial traffic", required = true)
	private int jspritIterationsForSmallScaleCommercial;

	@CommandLine.Option(names = "--smallScaleCommercialTrafficType", description = "Select traffic type. Options: commercialPersonTraffic, goodsTraffic, completeSmallScaleCommercialTraffic (contains both types)", defaultValue = "completeSmallScaleCommercialTraffic")
	private String smallScaleCommercialTrafficType;

	@CommandLine.Option(names = "--smallScaleCommercialGenerationOption", description = "Select generation option. Options: useExistingCarrierFileWithSolution, createNewCarrierFile, useExistingCarrierFileWithoutSolution", defaultValue = "createNewCarrierFile")
	private String smallScaleCommercialGenerationOption;

	@CommandLine.Option(names = "--nameOfExistingCarriersSmallScaleCommercial", description = "Path to the existing carriers file")
	private String nameOfExistingCarriersSmallScaleCommercial;

	@CommandLine.Option(names = "--additionalTravelBufferPerIterationInMinutes", description = "Additional buffer for the travel time", defaultValue = "120")
	private int additionalTravelBufferPerIterationInMinutes;

	@CommandLine.Option(names = "--factorForTravelBufferCalculation", description = "The factor describing how many vehicles should be created in relation to the number of created services (for small-scale-commercial). If maxNumberOfLoopsForVRPSolving > 0 more vehicles are added in the replanning process.", defaultValue = "1.1")
	private double factorForTravelBufferCalculation;

	@CommandLine.Option(names = "--maxNumberOfLoopsForVRPSolving", defaultValue = "5", description = "Maximum number of loops for VRP solving of the small acle commercial modell. If > 0, the VRP solving is repeated with additional vehicles added based on the factorForTravelBufferCalculation until either all carriers are solved or the maximum number of loops is reached.")
	private int maxNumberOfLoopsForVRPSolving;

	@CommandLine.Option(names = "--freightRawData", description = "Path to the freight raw data", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/matrix_gesamt_V3.csv")
	private String freightRawData;

	@CommandLine.Option(names = "--freightRawDataKEP", description = "Path to the KEP data", required = true, defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/kep_aufkommen/aufkommen_kep.csv")
	String freightRawDataKEP;

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

	@CommandLine.Option(names = "--distanceConstraintUsableRange", defaultValue = "100", description = "Usable vehicle range in percent during LTL and small scale commercial tour planning. Must be in (0, 100].")
	private double distanceConstraintUsableRange;

	@CommandLine.Option(names = "--smallScaleCommercialLongRangeVehicleRangeMultiplier", defaultValue = "2", description = "Range multiplier for high-cost small-scale commercial fallback vehicles that are added when unhandled services are outside the current electric vehicle range.")
	private double smallScaleCommercialLongRangeVehicleRangeMultiplier;

	@CommandLine.Option(names = "--smallScaleCommercialLongRangeVehicleFixedCostMultiplier", defaultValue = "10", description = "Fixed cost multiplier for high-cost small-scale commercial fallback vehicles.")
	private double smallScaleCommercialLongRangeVehicleFixedCostMultiplier;

	@CommandLine.Option(names = "--ltlCarrierPartCount", defaultValue = "1", description = "Number of independent carrier parts for Waste/Parcel LTL tour planning.")
	private int ltlCarrierPartCount;

	@CommandLine.Option(names = "--ltlCarrierPartIndex", defaultValue = "0", description = "Zero-based index of the independent Waste/Parcel LTL carrier part to solve.")
	private int ltlCarrierPartIndex;

	@CommandLine.Option(names = "--maxJobsPerCarrier", defaultValue = "0", description = "Maximum number of jobs per LTL carrier after splitting. Values <= 0 disable carrier splitting.")
	private int maxJobsPerCarrier;

	@CommandLine.Option(names = "--carrierSplittingStrategy", defaultValue = "GREEDY", description = "Carrier splitting strategy for LTL carrier splitting: ${COMPLETION-CANDIDATES}.")
	private CarrierSplitter.ClusteringStrategy carrierSplittingStrategy;

	@CommandLine.Option(names = "--smallScaleCommercialCarrierPartCount", defaultValue = "1", description = "Number of independent carrier parts for small scale commercial tour planning.")
	private int smallScaleCommercialCarrierPartCount;

	@CommandLine.Option(names = "--smallScaleCommercialCarrierPartIndex", defaultValue = "0", description = "Zero-based index of the independent small scale commercial carrier part to solve.")
	private int smallScaleCommercialCarrierPartIndex;

	public static void main(String[] args) {
		System.exit(new CommandLine(new CreateCommercialDemand()).execute(args));
	}

	@Override
	public Integer call() {

		if (runPart == RunPart.all) {
			alsoRunCompleteCommercialTraffic = true;
		}
		validateLtlCarrierPartOptions();
		validateSmallScaleCommercialCarrierPartOptions();
		validateDistanceConstraintUsableRange();
		if (distanceConstraintUsableRange < 100. && !useRangeConstraintForJspritTourPlanning) {
			log.warn("--distanceConstraintUsableRange is set to {}, but --useRangeConstraintForJspritTourPlanning is disabled.",
				distanceConstraintUsableRange);
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

		String LTLFreightPopulationName = "ruhr_LTL_freightPlans_" + (int) (sample * 100) + "pct.plans.xml.gz";
		String FTLFreightPopulationName = LTLFreightPopulationName.replace("LTL", "FTL");
		String LTLFreightPopulationNameRest = LTLFreightPopulationName.replace(".plans.xml.gz", "_REST.plans.xml.gz");
		String LTLFreightPopulationNameWaste = LTLFreightPopulationName.replace(".plans.xml.gz", "_WASTE.plans.xml.gz");
		String LTLFreightPopulationNameParcel = LTLFreightPopulationName.replace(".plans.xml.gz", "_PARCEL.plans.xml.gz");

		String freightDataName = "ruhr_freightData_100pct.xml.gz";

		if (runPart == RunPart.all || runPart == RunPart.freightData) {
			log.info("1st step - create freight data from BUW data");
			if (Files.exists(generatedInputDataPath.resolve(freightDataName)) || Files.exists(freightData)) {
				log.warn("Freight data already exists. Skipping generation.");
			} else {
				new GenerateFreightDataRuhr().execute(
					"--data", freightRawData,
					"--KEPdata", freightRawDataKEP,
					"--pathOutput", generatedInputDataPath.toString(),
					"--nameOutputDataFile", freightDataName,
					"--shpCells", vpCellsLocation.toString()
				);
			}
			if (runPart == RunPart.freightData) {
				return 0;
			}
		}

		if (runPart == RunPart.all || runPart == RunPart.ftl) {
			log.info("2rd step - create FTL freight plans from generated data");
			if (Files.exists(output.resolve(FTLFreightPopulationName))) {
				log.warn("Freight population already exists. Skipping generation.");
			} else {
				new GenerateFTLFreightPlansRuhr().execute(
					"--data", generatedInputDataPath.resolve(freightDataName).toString(),
					"--output", output.toString(),
					"--nameOutputPopulation", FTLFreightPopulationName,
					"--truck-load", "13.0",
					"--working-days", "260",
					"--max-kilometer-for-return-journey", "200",
					"--sample", String.valueOf(sample)
				);
			}
			if (runPart == RunPart.ftl) {
				return 0;
			}
		}

		if (runPart == RunPart.ltlRestInit || runPart == RunPart.ltlWasteInit || runPart == RunPart.ltlParcelInit) {
			String selectedLTLGoodsType = getLtlGoodsTypeForInitOrMerge(runPart);
			log.info("3rd step init - create shared unsolved LTL {} carriers", selectedLTLGoodsType);
			List<String> argumentsForLTL = createArgumentsForLTL(freightDataName,
				getLtlPopulationNameForGoodsType(selectedLTLGoodsType, LTLFreightPopulationNameRest, LTLFreightPopulationNameWaste, LTLFreightPopulationNameParcel),
				selectedLTLGoodsType);
			argumentsForLTL.add("--createLtlCarrierFileOnly");
			new GenerateLTLFreightPlansRuhr().execute(argumentsForLTL.toArray(new String[0]));
			return 0;
		}

		if (runPart == RunPart.all || runPart == RunPart.ltl || runPart == RunPart.ltlRest || runPart == RunPart.ltlWaste || runPart == RunPart.ltlParcel) {
			log.info("3rd step - create LTL freight plans from generated data");
			String nameOutputPopulation = LTLFreightPopulationName;
			String selectedLTLGoodsType = null;

			if (runPart == RunPart.ltlRest) {
				nameOutputPopulation = LTLFreightPopulationNameRest;
				selectedLTLGoodsType = "REST";
			} else if (runPart == RunPart.ltlWaste) {
				nameOutputPopulation = LTLFreightPopulationNameWaste;
				selectedLTLGoodsType = "WASTE";
			} else if (runPart == RunPart.ltlParcel) {
				nameOutputPopulation = LTLFreightPopulationNameParcel;
				selectedLTLGoodsType = "PARCEL";
			}
			List<String> argumentsForLTL = createArgumentsForLTL(freightDataName, nameOutputPopulation, selectedLTLGoodsType);
			if (ltlCarrierPartCount > 1) {
				argumentsForLTL.add("--ltlCarrierPartCount");
				argumentsForLTL.add(String.valueOf(ltlCarrierPartCount));
				argumentsForLTL.add("--ltlCarrierPartIndex");
				argumentsForLTL.add(String.valueOf(ltlCarrierPartIndex));
			}

			if (Files.exists(output.resolve(nameOutputPopulation))) {
				log.warn("Freight population already exists. Skipping generation.");
			} else {
				new GenerateLTLFreightPlansRuhr().execute(argumentsForLTL.toArray(new String[0]));
			}
			if (runPart == RunPart.ltl || runPart == RunPart.ltlRest || runPart == RunPart.ltlWaste || runPart == RunPart.ltlParcel) {
				return 0;
			}
		}

		if (runPart == RunPart.ltlRestMerge || runPart == RunPart.ltlWasteMerge || runPart == RunPart.ltlParcelMerge) {
			String ltlGoodsType = getLtlGoodsTypeForInitOrMerge(runPart);
			log.info("3a step - merge LTL {} carrier parts", ltlGoodsType);
			String outputPopulation = getLtlPopulationNameForGoodsType(ltlGoodsType, LTLFreightPopulationNameRest, LTLFreightPopulationNameWaste, LTLFreightPopulationNameParcel);
			List<String> mergeCarrierArguments = new ArrayList<>(List.of(
				"--carrierParts", generatedInputDataPath.resolve("carriersLTL_parts").toString(),
				"--carrierOutput", generatedInputDataPath.resolve("carriersLTL").toString(),
				"--network", configPath.getParent().resolve(networkPath).toString(),
				"--vehicleTypesFilePath", vehicleTypesFilePath,
				"--output", output.toString(),
				"--nameOutputPopulation", outputPopulation,
				"--LTL-goods-type", ltlGoodsType,
				"--ltlCarrierPartCount", String.valueOf(ltlCarrierPartCount),
				"--sample", String.valueOf(sample)
			));
			new MergeLTLCarrierPartsRuhr().execute(mergeCarrierArguments.toArray(new String[0]));
			return 0;
		}

		if (runPart == RunPart.ltlMerge) {
			log.info("3b step - merge LTL freight plan parts");
			if (Files.exists(output.resolve(LTLFreightPopulationName))) {
				log.warn("Freight population already exists. Skipping generation.");
			} else {
				new MergePopulations().execute(
					output.resolve(LTLFreightPopulationNameRest).toString(),
					output.resolve(LTLFreightPopulationNameWaste).toString(),
					output.resolve(LTLFreightPopulationNameParcel).toString(),
					"--output", output.resolve(LTLFreightPopulationName).toString()
				);
			}
			return 0;
		}
		String longDistanceFreightPopulationName = output.resolve(
			"ruhr_longDistanceFreight." + (int) (sample * 100) + "pct.plans.xml.gz").toString();
		if (runPart == RunPart.all || runPart == RunPart.longDistanceFreight) {
			log.info("4rd step - create transit long distance freight traffic");
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
				argumentsForFreightTransitTraffic.add("TRANSIT");
				argumentsForFreightTransitTraffic.add("--legMode");
				argumentsForFreightTransitTraffic.add("truck40t");
				argumentsForFreightTransitTraffic.add("--cut-on-boundary");

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
		//here possible to create an implementation for ruhrAGIS data
		LanduseDataConnectionCreator landuseDataConnectionCreator = new LanduseDataConnectionCreatorForOSM_Data();
		Path pathDataDistributionFile = generatedInputDataPath.resolve("dataDistributionPerZone.csv");
		if (runPart == RunPart.all || runPart == RunPart.smallScaleInputData) {
			log.info("5rd step - create input data for small scale commercial traffic");
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
		Path resolve = Path.of(outputPathSmallScaleCommercial).resolve(smallScaleCommercialPopulationName);
		VehicleTypeSelection vehicleTypeSelection = new CommercialVehicleSelectorRuhr();
		Path ltlPopulationPathForSmallScaleGoods = output.resolve(LTLFreightPopulationName);

		if (runPart == RunPart.smallScaleCommercialPersonInit || runPart == RunPart.smallScaleCommercialGoodsInit) {
			String selectedSmallScaleCommercialTrafficType = runPart == RunPart.smallScaleCommercialPersonInit ? "commercialPersonTraffic" : "goodsTraffic";
			String selectedOutputPathSmallScaleCommercial = runPart == RunPart.smallScaleCommercialPersonInit ? outputPathSmallScaleCommercialPerson : outputPathSmallScaleCommercialGoods;
			String selectedSmallScaleCommercialPopulationName = runPart == RunPart.smallScaleCommercialPersonInit ? smallScaleCommercialPersonPopulationName : smallScaleCommercialGoodsPopulationName;
			log.info("6th step init - create shared unsolved small scale commercial {} carriers", selectedSmallScaleCommercialTrafficType);
			List<String> args = createArgumentsForSmallScaleCommercial(pathDataDistributionFile, pathCommercialFacilities, shapeCRS,
				selectedSmallScaleCommercialTrafficType, selectedOutputPathSmallScaleCommercial, selectedSmallScaleCommercialPopulationName,
				smallScaleCommercialGenerationOption, null);
			args.add("--createSmallScaleCommercialCarrierFileOnly");
			addCreateNewCarrierSpecificArguments(args, selectedSmallScaleCommercialTrafficType);
			createSmallScaleCommercialTrafficDemand(vehicleTypeSelection, selectedSmallScaleCommercialTrafficType,
				ltlPopulationPathForSmallScaleGoods).execute(args.toArray(new String[0]));
			return 0;
		}

		if (runPart == RunPart.all || runPart == RunPart.smallScaleCommercial || runPart == RunPart.smallScaleCommercialPerson || runPart == RunPart.smallScaleCommercialGoods) {
			log.info("6th step - create small scale commercial traffic");
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
				String selectedGenerationOption = smallScaleCommercialGenerationOption;
				String selectedCarrierFile = null;
				if (smallScaleCommercialCarrierPartCount > 1) {
					log.info("Solving small scale commercial carrier part {}/{} for {}.",
						smallScaleCommercialCarrierPartIndex + 1, smallScaleCommercialCarrierPartCount, selectedSmallScaleCommercialTrafficType);
				}
				List<String> args = createArgumentsForSmallScaleCommercial(pathDataDistributionFile, pathCommercialFacilities, shapeCRS,
					selectedSmallScaleCommercialTrafficType, selectedOutputPathSmallScaleCommercial, selectedSmallScaleCommercialPopulationName,
					selectedGenerationOption, selectedCarrierFile);
				if (smallScaleCommercialCarrierPartCount > 1) {
					addSmallScaleCommercialCarrierPartArguments(args);
				}
				if (smallScaleCommercialCarrierPartCount == 1 && selectedGenerationOption.equals("createNewCarrierFile")) {
					addCreateNewCarrierSpecificArguments(args, selectedSmallScaleCommercialTrafficType);
				}
				createSmallScaleCommercialTrafficDemand(vehicleTypeSelection, selectedSmallScaleCommercialTrafficType,
					ltlPopulationPathForSmallScaleGoods).execute(args.toArray(new String[0]));

				// TODO filter relevant agents for the small scale commercial traffic
			}
			if (runPart == RunPart.smallScaleCommercial || runPart == RunPart.smallScaleCommercialPerson || runPart == RunPart.smallScaleCommercialGoods) {
				return 0;
			}
		}

		if (runPart == RunPart.smallScaleCommercialPersonCarrierMerge || runPart == RunPart.smallScaleCommercialGoodsCarrierMerge) {
			String selectedSmallScaleCommercialTrafficType = runPart == RunPart.smallScaleCommercialPersonCarrierMerge ? "commercialPersonTraffic" : "goodsTraffic";
			String selectedOutputPathSmallScaleCommercial = runPart == RunPart.smallScaleCommercialPersonCarrierMerge ? outputPathSmallScaleCommercialPerson : outputPathSmallScaleCommercialGoods;
			String selectedSmallScaleCommercialPopulationName = runPart == RunPart.smallScaleCommercialPersonCarrierMerge ? smallScaleCommercialPersonPopulationName : smallScaleCommercialGoodsPopulationName;
			log.info("6a step - merge small scale commercial {} carrier parts", selectedSmallScaleCommercialTrafficType);
			List<String> args = createArgumentsForSmallScaleCommercial(pathDataDistributionFile, pathCommercialFacilities, shapeCRS,
				selectedSmallScaleCommercialTrafficType, selectedOutputPathSmallScaleCommercial, selectedSmallScaleCommercialPopulationName,
				smallScaleCommercialGenerationOption, null);
			args.add("--mergeSmallScaleCommercialCarrierParts");
			args.add("--smallScaleCommercialCarrierPartCount");
			args.add(String.valueOf(smallScaleCommercialCarrierPartCount));
			createSmallScaleCommercialTrafficDemand(vehicleTypeSelection, selectedSmallScaleCommercialTrafficType,
				ltlPopulationPathForSmallScaleGoods).execute(args.toArray(new String[0]));
			return 0;
		}

		if (runPart == RunPart.smallScaleCommercialMerge) {
			log.info("6b step - merge small scale commercial traffic segments");
			if (Files.exists(resolve)) {
				log.warn("Small-scale Commercial demand already exists. Skipping generation.");
			} else {
				new MergePopulations().execute(
					Path.of(outputPathSmallScaleCommercialPerson).resolve(smallScaleCommercialPersonPopulationName).toString(),
					Path.of(outputPathSmallScaleCommercialGoods).resolve(smallScaleCommercialGoodsPopulationName).toString(),
					"--output", resolve.toString()
				);
			}
			return 0;
		}
		String pathMergedPopulation;
		if (outputPlansPath != null) {
			pathMergedPopulation = outputPlansPath;
		} else {
			pathMergedPopulation = output.resolve(LTLFreightPopulationName).toString().replace("_LTL", "").replace(".plans.xml.gz",
				"") + "_merged.plans.xml.gz";
		}
		if (runPart == RunPart.all || runPart == RunPart.merge) {
			log.info("7th step - Merge freight and commercial populations");
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
			if (runPart == RunPart.merge) {
				return 0;
			}
		}

		if (alsoRunCompleteCommercialTraffic || runPart == RunPart.matsim) {
			//TODO perhaps check if this can be moved to RunMetropoleRuhrScenario
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

			SimWrapper sw = SimWrapper.create(config);
			sw.getConfigGroup().defaultParams().setShp(null);
			sw.getConfigGroup().setDefaultDashboards(SimWrapperConfigGroup.DefaultDashboardsMode.disabled);
			sw.getConfigGroup().setSampleSize(sample);
			sw.addDashboard(new OverviewDashboard(Set.copyOf(config.qsim().getMainModes())));
			sw.addDashboard(new CarrierDashboard("(*.)?output_carriers_solvedVRP.xml.gz"));
			String subpopSetterForDashboards = "commercialPersonTraffic=commercialPersonTraffic,commercialPersonTraffic_service;smallScaleGoodsTraffic=goodsTraffic;LTL=LTL_trip;FTL=FTL_trip,FTL_kv_trip;longDistanceFreight=longDistanceFreight";
			sw.addDashboard(new TripDashboard().setGroupsOfSubpopulationsForCommercialAnalysis(subpopSetterForDashboards).setAnalysisArgs("--shp-filter", "none"));
//			sw.addDashboard(new CommercialTrafficDashboard(config.global().getCoordinateSystem()).setGroupsOfSubpopulationsForCommercialAnalysis(subpopSetterForDashboards));
			sw.addDashboard(Dashboard.customize(
				new CommercialTrafficDashboard(config.global().getCoordinateSystem(), "commercialTourDurations_ref.csv",
					"commercialTourDistances_ref.csv", "commercialActivityDurations_ref.csv").setGroupsOfSubpopulationsForCommercialAnalysis(
					subpopSetterForDashboards)));
			config.vehicles().setVehiclesFile(configPath.getParent().relativize(Path.of(vehicleTypesFilePath)).toString());
			config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
			config.scoring().setExplainScores(true);

			Set<String> modes = Set.of("car","truck8t", "truck18t", "truck26t", "truck40t");
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

	/**
	 * Validates the options for running or merging independent LTL carrier parts.
	 */
	private void validateLtlCarrierPartOptions() {
		if (ltlCarrierPartCount < 1) {
			throw new IllegalArgumentException("--ltlCarrierPartCount must be at least 1.");
		}
		if (ltlCarrierPartIndex < 0 || ltlCarrierPartIndex >= ltlCarrierPartCount) {
			throw new IllegalArgumentException("--ltlCarrierPartIndex must be between 0 and --ltlCarrierPartCount - 1.");
		}
		if ((runPart == RunPart.ltlRestMerge || runPart == RunPart.ltlWasteMerge || runPart == RunPart.ltlParcelMerge) && ltlCarrierPartCount == 1) {
			throw new IllegalArgumentException("--ltlCarrierPartCount must be greater than 1 when merging LTL carrier parts.");
		}
		if (maxJobsPerCarrier < 0) {
			throw new IllegalArgumentException("--maxJobsPerCarrier must be greater than or equal to 0.");
		}
	}

	private void validateSmallScaleCommercialCarrierPartOptions() {
		if (smallScaleCommercialCarrierPartCount < 1) {
			throw new IllegalArgumentException("--smallScaleCommercialCarrierPartCount must be at least 1.");
		}
		if (smallScaleCommercialCarrierPartIndex < 0 || smallScaleCommercialCarrierPartIndex >= smallScaleCommercialCarrierPartCount) {
			throw new IllegalArgumentException("--smallScaleCommercialCarrierPartIndex must be between 0 and --smallScaleCommercialCarrierPartCount - 1.");
		}
		if ((runPart == RunPart.smallScaleCommercialPersonCarrierMerge || runPart == RunPart.smallScaleCommercialGoodsCarrierMerge)
			&& smallScaleCommercialCarrierPartCount == 1) {
			throw new IllegalArgumentException("--smallScaleCommercialCarrierPartCount must be greater than 1 when merging small scale commercial carrier parts.");
		}
		if (smallScaleCommercialCarrierPartCount > 1
			&& (runPart == RunPart.all || runPart == RunPart.smallScaleCommercial || runPart == RunPart.smallScaleCommercialMerge)) {
			throw new IllegalArgumentException("Small scale commercial carrier parts must be run separately for commercialPersonTraffic and goodsTraffic.");
		}
	}

	private GenerateSmallScaleCommercialTrafficDemand createSmallScaleCommercialTrafficDemand(
		VehicleTypeSelection vehicleTypeSelection, String selectedSmallScaleCommercialTrafficType,
		Path ltlPopulationPathForSmallScaleGoods) {
		RangeAwareUnhandledServicesSolution unhandledServicesSolution = createRangeAwareUnhandledServicesSolution();
		GenerateSmallScaleCommercialTrafficDemand generator = new GenerateSmallScaleCommercialTrafficDemand(
			createConfigArgumentsForSmallScaleCommercial().toArray(new String[0]),
			createIntegrationForSmallScaleCommercial(selectedSmallScaleCommercialTrafficType, ltlPopulationPathForSmallScaleGoods),
			null, null, vehicleTypeSelection, unhandledServicesSolution);
		if (unhandledServicesSolution != null) {
			unhandledServicesSolution.setGenerator(generator);
		}
		return generator;
	}

	private RangeAwareUnhandledServicesSolution createRangeAwareUnhandledServicesSolution() {
		if (!useRangeConstraintForJspritTourPlanning) {
			return null;
		}
		return new RangeAwareUnhandledServicesSolution(distanceConstraintUsableRange,
			smallScaleCommercialLongRangeVehicleRangeMultiplier,
			smallScaleCommercialLongRangeVehicleFixedCostMultiplier);
	}

	private List<String> createArgumentsForLTL(String freightDataName, String nameOutputPopulation, String selectedLTLGoodsType) {
		List<String> argumentsForLTL = new ArrayList<>(List.of(
			"--data", generatedInputDataPath.resolve(freightDataName).toString(),
			"--network", configPath.getParent().resolve(networkPath).toString(),
			"--output", output.toString(),
			"--nameOutputPopulation", nameOutputPopulation,
			"--working-days", "260",
			"--sample", String.valueOf(sample),
			"--vehicleTypesFilePath", vehicleTypesFilePath,
			"--jsprit-iterations-for-LTL", String.valueOf(jspritIterationsForLTL),
			"--distanceConstraintUsableRange", String.valueOf(distanceConstraintUsableRange),
			"--maxJobsPerCarrier", String.valueOf(maxJobsPerCarrier),
			"--carrierSplittingStrategy", carrierSplittingStrategy.toString()
		));
		if (selectedLTLGoodsType != null) {
			argumentsForLTL.add("--LTL-goods-type");
			argumentsForLTL.add(selectedLTLGoodsType);
		}
		if (networkChangeEventsFile != null) {
			argumentsForLTL.add("--networkChangeEvents");
			argumentsForLTL.add(networkChangeEventsFile.toString());
		}
		if (useRangeConstraintForJspritTourPlanning) {
			argumentsForLTL.add("--useRangeConstraintForLTL");
		}
		return argumentsForLTL;
	}

	private List<String> createArgumentsForSmallScaleCommercial(Path pathDataDistributionFile, Path pathCommercialFacilities, String shapeCRS,
	                                                            String selectedSmallScaleCommercialTrafficType, String selectedOutputPathSmallScaleCommercial,
	                                                            String selectedSmallScaleCommercialPopulationName, String selectedGenerationOption,
	                                                            String selectedCarrierFile) {
		List<String> args = new ArrayList<>(List.of(configPath.toString(),
			"--pathToDataDistributionToZones", pathDataDistributionFile.toString(),
			"--pathToCommercialFacilities", configPath.getParent().relativize(pathCommercialFacilities).toString(),
			"--sample", String.valueOf(sample),
			"--jspritIterations", String.valueOf(jspritIterationsForSmallScaleCommercial),
			"--creationOption", selectedGenerationOption,
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
			"--maxNumberOfLoopsForVRPSolving", selectedGenerationOption.equals("useExistingCarrierFileWithSolution") ? "0" : "100",
			"--resistanceFactor_commercialPersonTraffic", String.valueOf(resistanceFactorForKWM_commercialPersonTraffic),
			"--resistanceFactor_goodsTraffic", String.valueOf(resistanceFactorForKWM_goodsTraffic)));
		if (shouldRunKwmMatsimAfterDemandGeneration(selectedGenerationOption)) {
			args.add("--MATSimIterationsAfterDemandGeneration");
			args.add(String.valueOf(MATSimIterationsKWM));
		}
		if (selectedGenerationOption.equals("useExistingCarrierFileWithoutSolution") || selectedGenerationOption.equals("useExistingCarrierFileWithSolution")) {
			args.add("--carrierFilePath");
			Path carrierFilePath = selectedCarrierFile == null
				? Path.of(selectedOutputPathSmallScaleCommercial).resolve(nameOfExistingCarriersSmallScaleCommercial)
				: Path.of(selectedCarrierFile);
			args.add(configPath.getParent().relativize(carrierFilePath).toString());
		}
		if (useRangeConstraintForJspritTourPlanning) {
			args.add("--useRangeConstraintForTourPlanning");
			if (distanceConstraintUsableRange < 100.) {
				args.add("--distanceConstraintUsableRange");
				args.add(String.valueOf(distanceConstraintUsableRange));
			}
		}
		return args;
	}

	private boolean shouldRunKwmMatsimAfterDemandGeneration(String selectedGenerationOption) {
		boolean isSmallScaleCommercialCarrierMergeRun = runPart == RunPart.smallScaleCommercialPersonCarrierMerge
			|| runPart == RunPart.smallScaleCommercialGoodsCarrierMerge;

		return MATSimIterationsKWM >= 0
			&& (isSmallScaleCommercialCarrierMergeRun
			|| (smallScaleCommercialCarrierPartCount == 1 && !selectedGenerationOption.equals("useExistingCarrierFileWithSolution")));
	}

	private void addCreateNewCarrierSpecificArguments(List<String> args, String selectedSmallScaleCommercialTrafficType) {
		if (selectedSmallScaleCommercialTrafficType.equals("goodsTraffic")
			|| selectedSmallScaleCommercialTrafficType.equals("completeSmallScaleCommercialTraffic")) {
			args.add("--includeExistingModels");
		}
	}

	private void addSmallScaleCommercialCarrierPartArguments(List<String> args) {
		args.add("--smallScaleCommercialCarrierPartCount");
		args.add(String.valueOf(smallScaleCommercialCarrierPartCount));
		args.add("--smallScaleCommercialCarrierPartIndex");
		args.add(String.valueOf(smallScaleCommercialCarrierPartIndex));
	}

	private List<String> createConfigArgumentsForSmallScaleCommercial() {
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
		return configArgs;
	}

	private void validateDistanceConstraintUsableRange() {
		if (!Double.isFinite(distanceConstraintUsableRange) || distanceConstraintUsableRange <= 0. || distanceConstraintUsableRange > 100.) {
			throw new IllegalArgumentException("--distanceConstraintUsableRange must be in the range (0, 100].");
		}
		if (!Double.isFinite(smallScaleCommercialLongRangeVehicleRangeMultiplier)
			|| smallScaleCommercialLongRangeVehicleRangeMultiplier <= 1.) {
			throw new IllegalArgumentException("--smallScaleCommercialLongRangeVehicleRangeMultiplier must be greater than 1.");
		}
		if (!Double.isFinite(smallScaleCommercialLongRangeVehicleFixedCostMultiplier)
			|| smallScaleCommercialLongRangeVehicleFixedCostMultiplier < 1.) {
			throw new IllegalArgumentException("--smallScaleCommercialLongRangeVehicleFixedCostMultiplier must be greater than or equal to 1.");
		}
	}

	private static IntegrateExistingTrafficToSmallScaleCommercial createIntegrationForSmallScaleCommercial(String selectedSmallScaleCommercialTrafficType,
	                                                                                                       Path ltlPopulationPathForSmallScaleGoods) {
		if (selectedSmallScaleCommercialTrafficType.equals("commercialPersonTraffic")) {
			return null;
		}
		return new IntegrationOfExistingCommercialTrafficRuhr(ltlPopulationPathForSmallScaleGoods);
	}

	private static String getLtlGoodsTypeForInitOrMerge(RunPart runPart) {
		return switch (runPart) {
			case ltlRestInit, ltlRestMerge -> "REST";
			case ltlWasteInit, ltlWasteMerge -> "WASTE";
			case ltlParcelInit, ltlParcelMerge -> "PARCEL";
			default -> throw new IllegalArgumentException("Unsupported LTL init or merge run part: " + runPart);
		};
	}

	private static String getLtlPopulationNameForGoodsType(String ltlGoodsType, String restPopulationName, String wastePopulationName, String parcelPopulationName) {
		return switch (ltlGoodsType) {
			case "REST" -> restPopulationName;
			case "WASTE" -> wastePopulationName;
			case "PARCEL" -> parcelPopulationName;
			default -> throw new IllegalArgumentException("Unsupported LTL goods type: " + ltlGoodsType);
		};
	}

}

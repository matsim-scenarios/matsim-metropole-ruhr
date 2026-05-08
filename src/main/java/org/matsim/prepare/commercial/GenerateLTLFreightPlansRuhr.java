package org.matsim.prepare.commercial;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.analysis.CarriersAnalysis;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class GenerateLTLFreightPlansRuhr implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(GenerateLTLFreightPlansRuhr.class);

	private enum LTL_GoodsType {
		REST, WASTE, PARCEL
	}

	@CommandLine.Option(names = "--data", description = "Path to generated freight data",
		defaultValue = "scenarios/metropole-ruhr-v2.0/input/commercialTraffic/ruhr_freightData_100pct.xml.gz")
	private String dataPath;

	@CommandLine.Option(names = "--network", description = "Path to desired network file",
		defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0_network.xml.gz")
	private String networkPath;

	@CommandLine.Option(names = "--networkChangeEvents", description = "Path to network change events file. If provided, the network change events will be considered in the tour planning.")
	private Path networkChangeEventsPath;

	@CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file",
		defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0.mode-vehicles.xml")
	private String vehicleTypesFilePath;

	@CommandLine.Option(names = "--output", description = "Output folder path", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/output/rvr_freightPlans/")
	private Path output;

	@CommandLine.Option(names = "--nameOutputPopulation", description = "Name of the output population file")
	private String nameOutputPopulation;

	@CommandLine.Option(names = "--working-days", defaultValue = "260", description = "Number of working days per year")
	private int workingDays;

	@CommandLine.Option(names = "--sample", defaultValue = "0.01", description = "Scaling factor of the freight traffic (0, 1)")
	private double sample;

	@CommandLine.Option(names = "--jsprit-iterations-for-LTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
	private int jspritIterationsForLTL;

	@CommandLine.Option(names = "--LTL-goods-type", description = "Option to select a single LTL goods type: REST, WASTE, PARCEL. If this is selected only this type will run.")
	private LTL_GoodsType selected_LTL_GoodsType;

	@CommandLine.Option(names = "--useRangeConstraintForLTL", description = "Option to use range constraint for LTL tours. If this is selected, the range is restricted based on consumption information in the vehicle types file.")
	private boolean useRangeConstraintForLTL;

	@CommandLine.Option(names = "--ltlCarrierPartCount", defaultValue = "1", description = "Number of independent carrier parts for LTL tour planning. Use with --ltlCarrierPartIndex.")
	private int ltlCarrierPartCount;

	@CommandLine.Option(names = "--ltlCarrierPartIndex", defaultValue = "0", description = "Zero-based index of the independent carrier part to solve.")
	private int ltlCarrierPartIndex;

	@Override
	public Integer call() throws Exception {
		validateLtlCarrierPartOptions();

		log.info("preparing freight agent generator for FTL trips...");
		LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL = new LTLFreightAgentGeneratorRuhr(workingDays, sample, null, null, null, null);

		log.info("Freight agent generator for FTL trips successfully created!");

		log.info("Reading freight data...");
		Population inputFreightDemandData = PopulationUtils.readPopulation(dataPath);
		log.info("Freight data successfully loaded. There are {} trip relations", inputFreightDemandData.getPersons().size());

		log.info("Start generating population...");
		Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		createPLansForLTLTrips(inputFreightDemandData, freightAgentGeneratorLTL, outputPopulation, jspritIterationsForLTL);

		if (isSolvingOnlyCarrierPart()) {
			log.info("Solved LTL carrier part {}/{}. Population and carrier analysis will be created by the merge step.",
				ltlCarrierPartIndex + 1, ltlCarrierPartCount);
			return 0;
		}

		if (!Files.exists(output)) {
			Files.createDirectory(output);
		}

		String sampleName = getSampleNameOfOutputFolder(sample);
		String outputPlansPath;
		if (nameOutputPopulation == null)
			outputPlansPath = output.resolve("freight." + sampleName + "pct.plansLTL.xml.gz").toString();
		else
			outputPlansPath = output.resolve(nameOutputPopulation).toString();
		PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
		populationWriter.write(outputPlansPath);

		log.info("Freight plans successfully generated!");
		boolean writeTsv = false;
		if (writeTsv) {
			log.info("Writing down tsv file for visualization and analysis...");
			// Write down tsv file for visualization and analysis
			String freightTripTsvPath = output.toString() + "/freight_trips_" + sampleName + "pct_data.tsv";
			CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(freightTripTsvPath), CSVFormat.TDF);
			tsvWriter.printRecord("trip_id", "fromCell", "toCell", "from_x", "from_y", "to_x", "to_y", "goodsType", "tripType");
			for (Person person : outputPopulation.getPersons().values()) {
				List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
				Activity act0 = (Activity) planElements.get(0);
				Activity act1 = (Activity) planElements.get(2);
				Coord fromCoord = act0.getCoord();
				Coord toCoord = act1.getCoord();
				String fromCell = CommercialTrafficUtils.getOriginCell(person);
				String toCell = CommercialTrafficUtils.getDestinationCell(person);
				String tripType = CommercialTrafficUtils.getTransportType(person);

				int goodsType = CommercialTrafficUtils.getGoodsType(person);
				tsvWriter.printRecord(person.getId().toString(), fromCell, toCell, fromCoord.getX(), fromCoord.getY(), toCoord.getX(), toCoord.getY(),
					goodsType, tripType);
			}
			tsvWriter.close();
			log.info("Tsv file successfully written to {}", freightTripTsvPath);
		}
		return 0;
	}

	/**
	 * Creates plans for LTL trips. Therefore, multiple carriers are created to solve the resulted vehicle routing problem.
	 */
	private void createPLansForLTLTrips(Population inputFreightDemandData, LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL,
										Population outputPopulation,
										int jspritIterationsForLTL) throws ExecutionException, InterruptedException, IOException {

		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(networkPath);
		config.global().setCoordinateSystem("EPSG:25832");
		FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
		freightCarriersConfigGroup.setCarriersVehicleTypesFile(vehicleTypesFilePath);
		if (useRangeConstraintForLTL) {
			freightCarriersConfigGroup.setUseDistanceConstraintForTourPlanning(FreightCarriersConfigGroup.UseDistanceConstraintForTourPlanning.basedOnEnergyConsumption);
			log.info("Using range constraint for LTL tours based on consumption information in the vehicle types file.");
		}
		if (networkChangeEventsPath != null) {
			config.network().setChangeEventsInputFile(networkChangeEventsPath.toString());
			config.network().setTimeVariantNetwork(true);
			freightCarriersConfigGroup.setTravelTimeSliceWidth(4 * 1800); // this was a result of a testing with a trade off between computation time and result changes compared to 1800s
			log.info("Network change events file provided. Network change events will be considered in the tour planning.");
		}
		Path outputFolderCarriers = Path.of(dataPath).getParent().resolve("carriersLTL");
		if (!Files.exists(outputFolderCarriers)) {
			Files.createDirectory(outputFolderCarriers);
		}
		Path outputFolderCarrierParts = outputFolderCarriers.resolveSibling("carriersLTL_parts");
		if (ltlCarrierPartCount > 1 && !Files.exists(outputFolderCarrierParts)) {
			Files.createDirectory(outputFolderCarrierParts);
		}
		Path carrierVRPFileLTL_Rest = outputFolderCarriers.resolve("output_LTL_Rest_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Rest_Solution = outputFolderCarriers.resolve("output_LTL_Rest_carriersWithSolution.xml.gz");
		Path carrierVRPFileLTL_Waste = outputFolderCarriers.resolve("output_LTL_Waste_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Waste_Solution = outputFolderCarriers.resolve("output_LTL_Waste_carriersWithSolution.xml.gz");
		Path carrierVRPFileLTL_Parcel = outputFolderCarriers.resolve("output_LTL_Parcel_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Rest_Parcel = outputFolderCarriers.resolve("output_LTL_Parcel_carriersWithSolution.xml.gz");

		Path carrierFile_noSolution;
		Path carrierFile_withSolution;

		for (LTL_GoodsType LTLGoodsType : LTL_GoodsType.values()) {
			carrierFile_noSolution = switch (LTLGoodsType) {
				case REST -> carrierVRPFileLTL_Rest;
				case WASTE -> carrierVRPFileLTL_Waste;
				case PARCEL -> carrierVRPFileLTL_Parcel;
			};
			carrierFile_withSolution = switch (LTLGoodsType) {
				case REST -> carrierVRPFile_Rest_Solution;
				case WASTE -> carrierVRPFile_Waste_Solution;
				case PARCEL -> carrierVRPFile_Rest_Parcel;
			};
			carrierFile_noSolution = getCarrierFileForCurrentPart(carrierFile_noSolution, outputFolderCarrierParts);
			carrierFile_withSolution = getCarrierFileForCurrentPart(carrierFile_withSolution, outputFolderCarrierParts);

			if (selected_LTL_GoodsType != null && selected_LTL_GoodsType != LTLGoodsType) {
				log.info("Skipping LTL goods type {} as the selected LTL goods type is {}", LTLGoodsType, selected_LTL_GoodsType);
				continue;
			}

			Scenario scenario;
			Path carrierAnalysisOutputPath = getCarrierAnalysisOutputPath(outputFolderCarriers, LTLGoodsType);

			if (Files.exists(carrierFile_withSolution)) {
				log.warn("Using existing carrier VRP file with solution: {}", carrierFile_withSolution);
				freightCarriersConfigGroup.setCarriersFile(carrierFile_withSolution.toString());
				scenario = ScenarioUtils.loadScenario(config);
				CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
			} else {
				if (Files.exists(carrierFile_noSolution)) {
					log.warn("Using existing carrier VRP file without solution: {}", carrierFile_noSolution);
					freightCarriersConfigGroup.setCarriersFile(carrierFile_noSolution.toString());

					scenario = ScenarioUtils.loadScenario(config);
					CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

				} else {
					scenario = ScenarioUtils.loadScenario(config);

					log.info("Read carrier vehicle types");
					CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
					new CarrierVehicleTypeReader(carrierVehicleTypes).readURL(
						IOUtils.extendUrl(scenario.getConfig().getContext(), freightCarriersConfigGroup.getCarriersVehicleTypesFile()));
					switch (LTLGoodsType) {
						case REST -> freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL,
							Integer.MIN_VALUE);
						case WASTE -> freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL, 140);
						case PARCEL -> freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL, 150);
					};
					filterCarriersForSelectedPart(scenario);
					CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), carrierFile_noSolution.toString());
				}
				filterRelevantVehicleTypesForTourPlanning(scenario);
				scenario.getConfig().controller().setOutputDirectory(carrierAnalysisOutputPath.toString());
				CarriersUtils.runJsprit(scenario);

				CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), carrierFile_withSolution.toString());
			}
			if (!isSolvingOnlyCarrierPart()) {
				CarriersAnalysis freightAnalysis = new CarriersAnalysis(scenario,
					carrierAnalysisOutputPath.resolve("analysis").resolve("freight").toString());
				freightAnalysis.runCarrierAnalysis(CarriersAnalysis.CarrierAnalysisType.carriersStatsAndDetailedTourAnalysisBasedOnCarrierPlans);
				LTLFreightAgentGeneratorRuhr.createPlansBasedOnCarrierPlans(scenario, outputPopulation);
			}
		}
	}

	/**
	 * Checks whether this run solves only one carrier part instead of a complete LTL goods type.
	 *
	 * @return {@code true} if this run is a carrier part run
	 */
	private boolean isSolvingOnlyCarrierPart() {
		return ltlCarrierPartCount > 1;
	}

	/**
	 * Validates the carrier part options before creating or loading LTL carriers.
	 */
	private void validateLtlCarrierPartOptions() {
		if (ltlCarrierPartCount < 1) {
			throw new IllegalArgumentException("--ltlCarrierPartCount must be at least 1.");
		}
		if (ltlCarrierPartIndex < 0 || ltlCarrierPartIndex >= ltlCarrierPartCount) {
			throw new IllegalArgumentException("--ltlCarrierPartIndex must be between 0 and --ltlCarrierPartCount - 1.");
		}
	}

	/**
	 * Resolves the carrier file path for the current run.
	 * Complete runs use the final carrier folder, while carrier part runs use the temporary part folder.
	 *
	 * @param carrierFile final carrier file path
	 * @param outputFolderCarrierParts folder for temporary carrier part files
	 * @return carrier file path for the current complete or part run
	 */
	private Path getCarrierFileForCurrentPart(Path carrierFile, Path outputFolderCarrierParts) {
		if (ltlCarrierPartCount == 1) {
			return carrierFile;
		}
		return outputFolderCarrierParts.resolve(addPartSuffix(carrierFile.getFileName().toString()));
	}

	/**
	 * Resolves the carrier analysis output path for the selected goods type.
	 * Complete runs write directly to the goods type analysis folder; part runs use a part subfolder.
	 *
	 * @param outputFolderCarriers folder containing LTL carrier output
	 * @param goodsType selected LTL goods type
	 * @return analysis output path for the current run
	 */
	private Path getCarrierAnalysisOutputPath(Path outputFolderCarriers, LTL_GoodsType goodsType) {
		Path carrierAnalysisOutputPath = outputFolderCarriers.resolve("Carriers_Analysis_" + goodsType);
		if (ltlCarrierPartCount == 1) {
			return carrierAnalysisOutputPath;
		}
		return carrierAnalysisOutputPath.resolve(partSuffix());
	}

	/**
	 * Adds the current part suffix to a file name.
	 *
	 * @param fileName file name to update
	 * @return file name with current part suffix
	 */
	private String addPartSuffix(String fileName) {
		String suffix = "_" + partSuffix();
		if (fileName.endsWith(".xml.gz")) {
			return fileName.replace(".xml.gz", suffix + ".xml.gz");
		}
		return fileName + suffix;
	}

	/**
	 * Builds the deterministic suffix for the current carrier part.
	 *
	 * @return suffix describing current part index and total part count
	 */
	private String partSuffix() {
		return "part-" + String.format("%03d", ltlCarrierPartIndex + 1) + "-of-" + String.format("%03d", ltlCarrierPartCount);
	}

	/**
	 * Filters the loaded carriers to the carrier subset assigned to the current part.
	 * The assignment is deterministic based on sorted carrier ids and modulo partitioning.
	 *
	 * @param scenario scenario containing all carriers before filtering
	 */
	private void filterCarriersForSelectedPart(Scenario scenario) {
		if (ltlCarrierPartCount == 1) {
			return;
		}
		Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);
		List<Id<Carrier>> sortedCarrierIds = carriers.getCarriers().keySet().stream()
			.sorted(Comparator.comparing(Id::toString))
			.toList();
		Set<Id<Carrier>> selectedCarrierIds = IntStream.range(0, sortedCarrierIds.size())
			.filter(carrierIndex -> carrierIndex % ltlCarrierPartCount == ltlCarrierPartIndex)
			.mapToObj(sortedCarrierIds::get)
			.collect(Collectors.toSet());
		carriers.getCarriers().keySet().removeIf(carrierId -> !selectedCarrierIds.contains(carrierId));
		log.info("Selected LTL carrier part {}/{} with {} carriers.", ltlCarrierPartIndex + 1, ltlCarrierPartCount,
			carriers.getCarriers().size());
	}

	/**
	 * Remove vehicle types which are not used by the carriers
	 *
	 * @param scenario the scenario
	 */
	private static void filterRelevantVehicleTypesForTourPlanning(Scenario scenario) {
		//
		Map<Id<VehicleType>, VehicleType> readVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes();
		List<Id<VehicleType>> usedCarrierVehicleTypes = CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
			.flatMap(carrier -> carrier.getCarrierCapabilities().getCarrierVehicles().values().stream())
			.map(vehicle -> vehicle.getType().getId())
			.distinct()
			.toList();

		readVehicleTypes.keySet().removeIf(vehicleType -> !usedCarrierVehicleTypes.contains(vehicleType));
	}

	static void main(String[] args) {
		new GenerateLTLFreightPlansRuhr().execute(args);
	}

	private static String getSampleNameOfOutputFolder(double sample) {
		String sampleName;
		if ((sample * 100) % 1 == 0)
			sampleName = String.valueOf((int) (sample * 100));
		else
			sampleName = String.valueOf((sample * 100));
		return sampleName;
	}

}

package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.analysis.CarriersAnalysis;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

public class MergeLTLCarrierPartsRuhr implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(MergeLTLCarrierPartsRuhr.class);

	private enum LTL_GoodsType {
		WASTE, PARCEL
	}

	@CommandLine.Option(names = "--carrierParts", description = "Folder containing the solved LTL carrier part files.", required = true)
	private Path carrierParts;

	@CommandLine.Option(names = "--carrierOutput", description = "Folder for the merged LTL carrier files.", required = true)
	private Path carrierOutput;

	@CommandLine.Option(names = "--network", description = "Path to the network file.", required = true)
	private String networkPath;

	@CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to carrier vehicle types file.", required = true)
	private String vehicleTypesFilePath;

	@CommandLine.Option(names = "--output", description = "Output folder for the merged population.", required = true)
	private Path output;

	@CommandLine.Option(names = "--nameOutputPopulation", description = "Name of the output population file.", required = true)
	private String nameOutputPopulation;

	@CommandLine.Option(names = "--LTL-goods-type", description = "LTL goods type to merge: WASTE or PARCEL.", required = true)
	private LTL_GoodsType selectedLTLGoodsType;

	@CommandLine.Option(names = "--ltlCarrierPartCount", defaultValue = "1", description = "Number of independent carrier parts to merge.")
	private int ltlCarrierPartCount;

	@CommandLine.Option(names = "--networkChangeEvents", description = "Path to network change events file. If provided, the network change events will be configured for analysis.")
	private Path networkChangeEventsPath;

	@Override
	public Integer call() throws Exception {
		validateOptions();

		if (!Files.exists(carrierOutput)) {
			Files.createDirectories(carrierOutput);
		}
		if (!Files.exists(output)) {
			Files.createDirectories(output);
		}

		String goodsTypeName = getCarrierGoodsTypeName(selectedLTLGoodsType);
		Path mergedCarrierFileNoSolution = mergeCarrierFiles(goodsTypeName, false);
		Path mergedCarrierFileWithSolution = mergeCarrierFiles(goodsTypeName, true);

		Scenario scenario = loadScenarioWithMergedCarriers(mergedCarrierFileWithSolution);
		Path carrierAnalysisOutputPath = carrierOutput.resolve("Carriers_Analysis_" + selectedLTLGoodsType);
		CarriersAnalysis freightAnalysis = new CarriersAnalysis(scenario,
			carrierAnalysisOutputPath.resolve("analysis").resolve("freight").toString());
		freightAnalysis.runCarrierAnalysis(CarriersAnalysis.CarrierAnalysisType.carriersStatsAndDetailedTourAnalysisBasedOnCarrierPlans);

		Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		LTLFreightAgentGeneratorRuhr.createPlansBasedOnCarrierPlans(scenario, outputPopulation);
		new PopulationWriter(outputPopulation).write(output.resolve(nameOutputPopulation).toString());

		log.info("Merged LTL {} carrier parts into {} and {} and wrote population {}.",
			selectedLTLGoodsType, mergedCarrierFileNoSolution, mergedCarrierFileWithSolution, output.resolve(nameOutputPopulation));
		return 0;
	}

	/**
	 * Validates the options for merging carrier parts.
	 */
	private void validateOptions() {
		if (ltlCarrierPartCount < 2) {
			throw new IllegalArgumentException("--ltlCarrierPartCount must be at least 2 when merging LTL carrier parts.");
		}
	}

	/**
	 * Merges all carrier part files for one goods type and solution state into one carrier file.
	 *
	 * @param goodsTypeName goods type name used in the carrier file name
	 * @param withSolution whether the solved or unsolved carrier files should be merged
	 * @return path to the merged carrier file
	 */
	private Path mergeCarrierFiles(String goodsTypeName, boolean withSolution) {
		String carrierFileName = "output_LTL_" + goodsTypeName + "_carriers" + (withSolution ? "WithSolution" : "NoSolution") + ".xml.gz";
		Path outputCarrierFile = carrierOutput.resolve(carrierFileName);
		if (Files.exists(outputCarrierFile)) {
			log.warn("Merged LTL carrier file already exists. Skipping generation: {}", outputCarrierFile);
			return outputCarrierFile;
		}

		Scenario mergedScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Carriers mergedCarriers = CarriersUtils.addOrGetCarriers(mergedScenario);
		for (int partIndex = 0; partIndex < ltlCarrierPartCount; partIndex++) {
			Path partCarrierFile = carrierParts.resolve(addPartSuffix(carrierFileName, partIndex, ltlCarrierPartCount));
			if (!Files.exists(partCarrierFile)) {
				throw new IllegalArgumentException("Missing LTL carrier part file: " + partCarrierFile);
			}
			Scenario partScenario = loadScenarioWithCarrierFile(partCarrierFile);
			for (Carrier carrier : CarriersUtils.getCarriers(partScenario).getCarriers().values()) {
				if (mergedCarriers.getCarriers().containsKey(carrier.getId())) {
					throw new IllegalArgumentException("Duplicate carrier id while merging LTL carrier parts: " + carrier.getId());
				}
				mergedCarriers.addCarrier(carrier);
			}
		}
		CarriersUtils.writeCarriers(mergedCarriers, outputCarrierFile.toString());
		return outputCarrierFile;
	}

	/**
	 * Loads a scenario containing the merged carriers and the network needed for analysis and plan creation.
	 *
	 * @param carrierFile path to the merged carrier file
	 * @return scenario with carriers loaded according to the freight config
	 */
	private Scenario loadScenarioWithMergedCarriers(Path carrierFile) {
		Config config = createConfigWithCarrierFile(carrierFile);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
		return scenario;
	}

	/**
	 * Loads one carrier part file into a scenario.
	 *
	 * @param carrierFile path to the carrier part file
	 * @return scenario with the carrier part loaded
	 */
	private Scenario loadScenarioWithCarrierFile(Path carrierFile) {
		Config config = createConfigWithCarrierFile(carrierFile);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
		return scenario;
	}

	/**
	 * Creates the config needed to load a carrier file with the network, vehicle types and optional network change events.
	 *
	 * @param carrierFile carrier file to configure
	 * @return config for loading a scenario with this carrier file
	 */
	private Config createConfigWithCarrierFile(Path carrierFile) {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(networkPath);
		config.global().setCoordinateSystem("EPSG:25832");
		if (networkChangeEventsPath != null) {
			config.network().setChangeEventsInputFile(networkChangeEventsPath.toString());
			config.network().setTimeVariantNetwork(true);
		}
		FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
		freightCarriersConfigGroup.setCarriersFile(carrierFile.toString());
		freightCarriersConfigGroup.setCarriersVehicleTypesFile(vehicleTypesFilePath);
		return config;
	}

	/**
	 * Converts the command line goods type enum to the mixed-case token used by the carrier file names.
	 *
	 * @param goodsType selected LTL goods type
	 * @return goods type token used in the carrier file name
	 */
	private static String getCarrierGoodsTypeName(LTL_GoodsType goodsType) {
		return switch (goodsType) {
			case WASTE -> "Waste";
			case PARCEL -> "Parcel";
		};
	}

	/**
	 * Adds the deterministic part suffix to a carrier file name.
	 *
	 * @param fileName carrier file name
	 * @param partIndex zero-based part index
	 * @param partCount total number of parts
	 * @return file name with part suffix
	 */
	private static String addPartSuffix(String fileName, int partIndex, int partCount) {
		String suffix = "_part-" + String.format("%03d", partIndex + 1) + "-of-" + String.format("%03d", partCount);
		if (fileName.endsWith(".xml.gz")) {
			return fileName.replace(".xml.gz", suffix + ".xml.gz");
		}
		return fileName + suffix;
	}

	static void main(String[] args) {
		new MergeLTLCarrierPartsRuhr().execute(args);
	}
}

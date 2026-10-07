package org.matsim.analysis;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.CostInformation;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;

public class AnalyzeRuhrCommercialRuns implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(AnalyzeRuhrCommercialRuns.class);

//	private enum ModelComponentsBasic {
//		commercialPersonTraffic, longDistanceFreight, goodsTraffic
//	}
//
//	private enum ModelComponents {
//		commercialPersonTraffic, longDistanceFreight, smallScaleGoodsTraffic, remainingLTL, CEP, Waste, FTL
//	}
//
//	private enum ModelType {
//		basic, advanced
//	}
//
	private enum ScenarioType {
		CV, EV, Mixed
	}

	private static Set<Integer> scenarioYears = new LinkedHashSet<>(List.of(2024, 2030, 2050));

	@CommandLine.Parameters(description = "Path to run output directory for which analysis should be performed.", defaultValue = "output/studyWV_Ruhr/studyWV_Ruhr_CV_Basic_2024/commercial_1pct/commercialTraffic_Run1pct")
	private Path inputPath;

	@CommandLine.Parameters(description = "Path of the output directory of the analysis files relativ to the input path", defaultValue = "analysis/paperAnalysis")
	private Path output;

	@CommandLine.Option(names = "--sampleSize", required = true, defaultValue = "0.01")
	private double sampleSize;
//
//	@CommandLine.Option(names = "--modelType", required = true, defaultValue = "basic")
//	private ModelType modelType;

	@CommandLine.Option(names = "--scenarioType", description = "Select a type of the scenario. CV: Commercial Vehicle, EV: Electric Vehicle, Mixed: Commercial Vehicle and Electric Vehicle", required = true, defaultValue = "CV")
	private ScenarioType scenarioType;

	@CommandLine.Option(names = "--vehicleTypesFolder", defaultValue = "../runs-svn/rvr-ruhrgebiet/2026_StudyDecarbonizationCommercialTraffic/input/")
	private Path vehicleTypesFolder;

	@CommandLine.Option(names = "--scenarioYear", required = true, defaultValue = "2024")
	private int scenarioYear;

	@CommandLine.Mixin
	private ShpOptions shpInvestigationArea;

	static void main(String[] args) {
		System.exit(new CommandLine(new AnalyzeRuhrCommercialRuns()).execute(args));
	}

	@Override
	public Integer call() throws IOException {
		log.info("Analyzing Ruhr commercial runs in {}", inputPath);
		validateScenarioYear(scenarioYear);

		String pathVehicleTypes2024 = globFile(vehicleTypesFolder, "*mode-vehicles_WV_base2024_" + scenarioType + "*").toString();
		String pathVehicleTypes2030 = globFile(vehicleTypesFolder, "*mode-vehicles_WV_base2030_" + scenarioType + "*").toString();
		String pathVehicleTypes2050 = globFile(vehicleTypesFolder, "*mode-vehicles_WV_base2050_" + scenarioType + "*").toString();

		log.info("Using scenario results from year {}", scenarioYear);
		log.info("Recalculating scores for 2024 based on output of this run: {} and vehicleTypes: {}", inputPath, pathVehicleTypes2024);
		log.info("Recalculating scores for 2030 based on output of this run: {} and vehicleTypes: {}", inputPath, pathVehicleTypes2030);
		log.info("Recalculating scores for 2050 based on output of this run: {} and vehicleTypes: {}", inputPath, pathVehicleTypes2050);

		CarrierVehicleTypes vehicleTypes2024 = new CarrierVehicleTypes();
		CarrierVehicleTypes vehicleTypes2030 = new CarrierVehicleTypes();
		CarrierVehicleTypes vehicleTypes2050 = new CarrierVehicleTypes();

		new CarrierVehicleTypeReader(vehicleTypes2024).readFile(pathVehicleTypes2024);
		new CarrierVehicleTypeReader(vehicleTypes2030).readFile(pathVehicleTypes2030);
		new CarrierVehicleTypeReader(vehicleTypes2050).readFile(pathVehicleTypes2050);

		HashMap<Integer, CarrierVehicleTypes> typesByYear = new HashMap<>();
		typesByYear.put(2024, vehicleTypes2024);
		typesByYear.put(2030, vehicleTypes2030);
		typesByYear.put(2050, vehicleTypes2050);

		final String eventsFile = globFile(inputPath, "*output_events*").toString();
		Config config = ConfigUtils.createConfig();
		config.vehicles().setVehiclesFile(globFile(inputPath, "*output_vehicles*").toString());
		config.network().setInputFile(globFile(inputPath, "*output_network*").toString());

		config.global().setCoordinateSystem(null);
		config.plans().setInputFile(globFile(inputPath, "*output_plans*").toString());
//		config.eventsManager().setNumberOfThreads(null);
//		config.eventsManager().setEstimatedNumberOfEvents(null);
//		config.global().setNumberOfThreads(4);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		EventsManager eventsManager = EventsUtils.createEventsManager();

		// link events handler
		CommercialTrafficAnalysisEventHandlerRuhr ruhrCommercialEventHandler = new CommercialTrafficAnalysisEventHandlerRuhr(scenario, shpInvestigationArea);
		eventsManager.addHandler(ruhrCommercialEventHandler);

		eventsManager.initProcessing();

		log.info("-------------------------------------------------");
		log.info("Done reading the events file");
		log.info("Finish processing...");
		eventsManager.finishProcessing();
		new MatsimEventsReader(eventsManager).readFile(eventsFile);
		log.info("Closing events file...");

		Files.createDirectories(inputPath.resolve(output));

		createAnalysisPerVehicle(ruhrCommercialEventHandler, typesByYear, scenario);
//		analyzeSmallScaleCommercialTraffic(tourCharacteristics);

		return 0;
	}

	/**
	 * @param scenarioYear
	 */
	private void validateScenarioYear(int scenarioYear) {
		if (!scenarioYears.contains(scenarioYear)) {
			log.error("Invalid scenario year. Please choose from 2024, 2030, or 2050.");
			throw new IllegalArgumentException("Invalid scenario year");
		}
	}

	private void createAnalysisPerVehicle(CommercialTrafficAnalysisEventHandlerRuhr ruhrCommercialEventHandler,
	                                      HashMap<Integer, CarrierVehicleTypes> typesByYear, Scenario scenario) {
		HashMap<Id<Person>, Double> tourDurations = ruhrCommercialEventHandler.getTourDurationPerPerson();
		HashMap<Id<Person>, Object2DoubleOpenHashMap<String>> detailedDurationsPerPerson = ruhrCommercialEventHandler.getDurationsPerVehicle();
		HashMap<Id<Person>, String> persons = ruhrCommercialEventHandler.getAnalyzedPersons();
		Object2IntOpenHashMap<String> jobsPerComponent = ruhrCommercialEventHandler.getJobsPerComponent();
		Map<String, List<Id<Person>>> personsByComponent = new HashMap<>();
		Object2DoubleOpenHashMap<String> travelDistancePerComponent = ruhrCommercialEventHandler.getTravelDistancesPerComponent();
		HashMap<String, Object2DoubleOpenHashMap<Id<Person>>> travelDistances = ruhrCommercialEventHandler.getTravelDistancesPerVehicle();

		for (Map.Entry<Id<Person>, String> entry : persons.entrySet()) {
			personsByComponent
				.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
				.add(entry.getKey());
		}

		String sampleName = BigDecimal.valueOf(sampleSize * 100).setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "pct";

		int sumAgents = 0;
		int sumAgents100pct = 0;
		long totalStops = 0;
		long totalStops100pct = 0;
		long totalTraveledDistanceKM = 0;
		long totalTraveledDistanceKM100pct = 0;
		double totalTraveledDistanceKMUnrounded = 0;
		long totalTourDurationsH = 0;
		double totalTourDurationsSeconds = 0;
		long totalTravelTimeH = 0;
		long totalTravelTimeH100pct = 0;
		double totalTravelTimeSeconds = 0;
		long totalActivityTimeH = 0;
		double totalActivityTimeSeconds = 0;
		long totalTrips = 0;
		long totalTrips100pct = 0;
		BigDecimal totalExperiencedScores = BigDecimal.ZERO;
		BigDecimal totalExperiencedScores100pct = BigDecimal.ZERO;

		try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(inputPath.resolve(output).resolve("generalModelData.csv")),
			CSVFormat.DEFAULT)) {
			try (CSVPrinter printer100pct = new CSVPrinter(Files.newBufferedWriter(inputPath.resolve(output).resolve("generalModelData100pct.csv")),
				CSVFormat.DEFAULT)) {

				printer.printRecord("RunSample", "component", "agents", "stops", "averageStopsPerTour", "traveledDistanceKM", "averageTourTravelDistanceKM",
					"tourDurationsH", "averageTourDurationH", "travelTimeH", "averageTravelTimeH", "activityTimeH", "averageActivityTimeH", "numberOfTrips", "lastExecutedScores"+scenarioYear, "recalculateScores"+scenarioYear);

				printer100pct.printRecord("RunSample", "component", "agents100pct", "stops100pct", "traveledDistanceKM100pct", "travelTimeH100pct",	"numberOfTrips100pct", "lastExecutedScores100pct");

				for (String component : personsByComponent.keySet()) {

					printer.print(sampleName);
					printer100pct.print(sampleName);

					printer.print(component);
					printer100pct.print(component);

					// agents
					int numberOfAgents = personsByComponent.get(component).size();
					printer.print(numberOfAgents);
					int agents100pct = (int) Math.round(numberOfAgents / sampleSize);
					printer100pct.print(agents100pct);
					sumAgents += numberOfAgents;
					sumAgents100pct += agents100pct;

					// stops/jobs; no start or end job; only the jobs in between
					int sumStops = jobsPerComponent.getInt(component);
					printer.print(sumStops);
					long stops100pct = Math.round(sumStops / sampleSize);
					printer100pct.print(stops100pct);
					totalStops += sumStops;
					totalStops100pct += stops100pct;

					// average stops per Tour
					printer.print(BigDecimal.valueOf((double) sumStops / numberOfAgents).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

					// traveled distance in km
					double travelDistanceOfComponentKM = travelDistancePerComponent.getDouble(component) / 1000;
					long traveledDistanceKM = Math.round(travelDistanceOfComponentKM);
					long traveledDistanceKM100pct = Math.round(travelDistanceOfComponentKM / sampleSize);
					printer.print(traveledDistanceKM);
					printer100pct.print(traveledDistanceKM100pct);
					totalTraveledDistanceKM += traveledDistanceKM;
					totalTraveledDistanceKM100pct += traveledDistanceKM100pct;
					totalTraveledDistanceKMUnrounded += travelDistanceOfComponentKM;

					// average tour travel distance in km
					printer.print(BigDecimal.valueOf(travelDistanceOfComponentKM / numberOfAgents).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

					double sumTourDurationsPerComponent = 0;
					double sumTravelTimesPerComponent = 0;
					double sumActivityTimesPerComponent = 0;
					for (Id<Person> personId : personsByComponent.get(component)) {
						sumTourDurationsPerComponent += tourDurations.get(personId);
						sumActivityTimesPerComponent += detailedDurationsPerPerson.get(personId).getDouble("activityDurations");
						sumTravelTimesPerComponent += detailedDurationsPerPerson.get(personId).getDouble("travelDurations");
					}

					// tour duration in hours
					long tourDurationH = Math.round(sumTourDurationsPerComponent / 3600);
					printer.print(tourDurationH);
					totalTourDurationsH += tourDurationH;
					totalTourDurationsSeconds += sumTourDurationsPerComponent;

					// average tour duration in hours
					printer.print(BigDecimal.valueOf(sumTourDurationsPerComponent / numberOfAgents / 3600).setScale(2,
						RoundingMode.HALF_EVEN).stripTrailingZeros());

					// travel time in hours
					long travelTimeH = Math.round(sumTravelTimesPerComponent / 3600);
					long travelTimeH100pct = Math.round(sumTravelTimesPerComponent / sampleSize / 3600);
					printer.print(travelTimeH);
					printer100pct.print(travelTimeH100pct);
					totalTravelTimeH += travelTimeH;
					totalTravelTimeH100pct += travelTimeH100pct;
					totalTravelTimeSeconds += sumTravelTimesPerComponent;

					// average travel time in hours
					printer.print(BigDecimal.valueOf(sumTravelTimesPerComponent / numberOfAgents / 3600).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

					// activity time in hours
					long activityTimeH = Math.round(sumActivityTimesPerComponent / 3600);
					printer.print(activityTimeH);
					totalActivityTimeH += activityTimeH;
					totalActivityTimeSeconds += sumActivityTimesPerComponent;

					// average activity time in hours
					printer.print(BigDecimal.valueOf(sumActivityTimesPerComponent / numberOfAgents / 3600).setScale(2,	RoundingMode.HALF_EVEN).stripTrailingZeros());

					// number of trips
					int numberOfTrips = sumStops + numberOfAgents;
					printer.print(numberOfTrips);
					long trips100pct = Math.round(numberOfTrips / sampleSize);
					printer100pct.print(trips100pct);
					totalTrips += numberOfTrips;
					totalTrips100pct += trips100pct;

					// last executed scores
					HashMap<Id<Person>, Double> experiencedScoresForRelevantAgents = getExperiencedScoresForRelevantAgents(personsByComponent.get(component));
					double sumExperiencedScoresForRelevantAgents = travelDistances.values().stream()
						.flatMap(map -> map.values().stream())
						.mapToDouble(Double::doubleValue)
						.sum();
					BigDecimal experiencedScores = BigDecimal.valueOf(sumExperiencedScoresForRelevantAgents).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros();
					BigDecimal experiencedScores100pct = BigDecimal.valueOf(sumExperiencedScoresForRelevantAgents / sampleSize).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros();
					printer.print(experiencedScores);
					printer100pct.print(experiencedScores100pct);
					totalExperiencedScores = totalExperiencedScores.add(experiencedScores);
					totalExperiencedScores100pct = totalExperiencedScores100pct.add(experiencedScores100pct);

					// recalculate scenario scores
					Object2DoubleOpenHashMap<Integer> recalculatedScores = recalculateScores(personsByComponent.get(component), travelDistances, detailedDurationsPerPerson, typesByYear, experiencedScoresForRelevantAgents);

					for (Integer year : scenarioYears) {
						printer.print(BigDecimal.valueOf(recalculatedScores.getDouble(year)).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());
						printer100pct.print(BigDecimal.valueOf(recalculatedScores.getDouble(year) / sampleSize).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());
					}
					printer.println();
					printer100pct.println();
				}

				// print sums
				printer.print(sampleName);
				printer100pct.print(sampleName);

				printer.print("Sum");
				printer100pct.print("Sum");

				// agents
				printer.print(sumAgents);
				printer100pct.print(sumAgents100pct);
				int averageDenominator = Math.max(sumAgents, 1);

				// stops/jobs; no start or end job; only the jobs in between
				printer.print(totalStops);
				printer100pct.print(totalStops100pct);

				// average stops per Tour
				printer.print(BigDecimal.valueOf((double) totalStops / averageDenominator).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

				// traveled distance in km
				printer.print(totalTraveledDistanceKM);
				printer100pct.print(totalTraveledDistanceKM100pct);

				// average tour travel distance in km
				printer.print(BigDecimal.valueOf(totalTraveledDistanceKMUnrounded / averageDenominator).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

				// tour duration in hours
				printer.print(totalTourDurationsH);

				// average tour duration in hours
				printer.print(BigDecimal.valueOf(totalTourDurationsSeconds / averageDenominator / 3600).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

				// travel time in hours
				printer.print(totalTravelTimeH);
				printer100pct.print(totalTravelTimeH100pct);

				// average travel time in hours
				printer.print(BigDecimal.valueOf(totalTravelTimeSeconds / averageDenominator / 3600).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

				// activity time in hours
				printer.print(totalActivityTimeH);

				// average activity time in hours
				printer.print(BigDecimal.valueOf(totalActivityTimeSeconds / averageDenominator / 3600).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros());

				// number of trips
				printer.print(totalTrips);
				printer100pct.print(totalTrips100pct);

				// last executed scores
				printer.print(totalExperiencedScores.stripTrailingZeros());
				printer100pct.print(totalExperiencedScores100pct.stripTrailingZeros());
				printer.println();
				printer100pct.println();


				printer100pct.flush();
			} catch (IOException e) {
				log.error("Could not create output file for 100pct", e);
			}
			printer.flush();
		} catch (IOException e) {
			log.error("Could not create output file for sample", e);
		}
	}

	private Object2DoubleOpenHashMap<Integer> recalculateScores(List<Id<Person>> ids, HashMap<String, Object2DoubleOpenHashMap<Id<Person>>> travelDistances, HashMap<Id<Person>, Object2DoubleOpenHashMap<String>> detailedDurationsPerPerson,
	                                                            HashMap<Integer, CarrierVehicleTypes> typesByYear, HashMap<Id<Person>, Double> experiencedScoresForRelevantAgents) {

		Object2DoubleOpenHashMap<Integer> sumCosts = new Object2DoubleOpenHashMap<>();

		for (Id<Person> personId : ids) {

			Id<VehicleType> vehicleType = Id.createVehicleTypeId(travelDistances.entrySet().stream()
				.filter(entry -> entry.getValue().containsKey(personId))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null));

			for (Integer year : scenarioYears) {
				CostInformation costInformation = typesByYear.get(year).getVehicleTypes().get(vehicleType).getCostInformation();
				double vehicleFixCosts = costInformation.getFixedCosts();
				double timeCostsTravel = costInformation.getCostsPerSecond() * detailedDurationsPerPerson.get(personId).getDouble("travelDurations");
				double timeCostsActivity = costInformation.getCostsPerSecond() * detailedDurationsPerPerson.get(personId).getDouble("activityDurations");
				double distanceCosts = costInformation.getCostsPerMeter() * travelDistances.get(vehicleType.toString()).getDouble(personId);

				double costs = vehicleFixCosts + timeCostsTravel + timeCostsActivity + distanceCosts;
				sumCosts.mergeDouble(year, costs, Double::sum);

				if (year == scenarioYear)
					log.info("Agent: {}, recalculated Score: {}, experiencedScore: {} ", personId, costs, experiencedScoresForRelevantAgents.get(personId));
			}
		}
		return sumCosts;
	}

	private HashMap<Id<Person>, Double> getExperiencedScoresForRelevantAgents(List<Id<Person>> ids) {
		Path experiencedScoresTxtFile = globFile(inputPath, "*experienced_plans_scores*");
		HashMap<Id<Person>, Double> experiencedScores = new HashMap<>();
		double sumExperiencedScores = 0;
		try (
			BufferedReader reader = IOUtils.getBufferedReader(experiencedScoresTxtFile.toString());
			CSVParser parser = CSVFormat.DEFAULT.builder()
				.setDelimiter("\t")
				.setSkipHeaderRecord(false)
				.get()
				.parse(reader)
		) {
			for (CSVRecord record : parser) {
				Id<Person> personId = Id.createPersonId(record.get(0));
				if (ids.contains(personId)) {
					double sumExperiencedScore = Double.parseDouble(record.get(record.size() - 1));
					experiencedScores.put(personId, sumExperiencedScore);
					sumExperiencedScores += sumExperiencedScore;
//					log.info("Experienced score for {}: {}", personId, record.get(record.size() - 1));
				}

			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return experiencedScores;
	}

//	private List<Id<Person>> identifyRelevantAgentsForAnalysis() {
//		List<Id<Person>> relevantAgents = new ArrayList<>();
//		getSmallScaleCommercialTrafficAgents(relevantAgents);
//		return relevantAgents;
//	}
//
//	/// because the commercial analysis of the runs alr
//	/// @param relevantAgents
//		private void getSmallScaleCommercialTrafficAgents(List<Id<Person>> relevantAgents) {
//		try (
//			BufferedReader reader = Files.newBufferedReader(
//				inputPath.resolve("analysis").resolve("commercialTraffic").resolve("tourAnalysis_durations.csv"));
//			CSVParser parser = CSVFormat.DEFAULT.builder()
//				.setHeader()
//				.setSkipHeaderRecord(true)
//				.get()
//				.parse(reader)
//		) {
//			for (CSVRecord record : parser) {
//				String group = record.get("groupOfSubpopulation");
//				ModelComponents component = ModelComponents.valueOf(group);
//				if (component == ModelComponents.smallScaleGoodsTraffic || component == ModelComponents.commercialPersonTraffic) {
//					relevantAgents.add(Id.createPersonId(record.get("personId")));
//				}
//			}
//		} catch (IOException e) {
//			throw new RuntimeException(e);
//		}
//	}
//
//
//
//
//
//
//	private void analyzeSmallScaleCommercialTraffic(HashMap<ModelComponents, HashMap<String, TourCharacteristics>> tourCharacteristics) {
//
//		getSmallScaleCommercialTrafficAgents(tourCharacteristics);
//		analyzeTourDistanceFile(tourCharacteristics);
//		analyzeTourStopsFile(tourCharacteristics);
//		analyzeTravelDistance(tourCharacteristics);
//	}
//
//	private void analyzeTravelDistance(HashMap<ModelComponents, HashMap<String, TourCharacteristics>> tourCharacteristics) {
//
//		Path plansCSV = globFile( inputPath,  "*trips.csv*");
//
//		try (
//			BufferedReader reader = Files.newBufferedReader(plansCSV);
//			CSVParser parser = CSVFormat.DEFAULT.builder()
//				.setHeader()
//				.setSkipHeaderRecord(true)
//				.get()
//				.parse(reader)
//		) {
//			for (CSVRecord record : parser) {
//
////					TourCharacteristics thisTourCharacteristics = tourCharacteristics.get(component).get(record.get("personId"));
////					thisTourCharacteristics.setNumberOfStops(Integer.parseInt(record.get("jobsPerTour")));
//
//			}
//		} catch (IOException e) {
//			throw new RuntimeException(e);
//		}
//	}
//
//	private void analyzeTourStopsFile(HashMap<ModelComponents, HashMap<String, TourCharacteristics>> tourCharacteristics) {
//		try (
//			BufferedReader reader = Files.newBufferedReader(
//				inputPath.resolve("analysis").resolve("commercialTraffic").resolve("tourAnalysis_jobsPerTour.csv"));
//			CSVParser parser = CSVFormat.DEFAULT.builder()
//				.setHeader()
//				.setSkipHeaderRecord(true)
//				.get()
//				.parse(reader)
//		) {
//			for (CSVRecord record : parser) {
//				String group = record.get("groupOfSubpopulation");
//				ModelComponents component = ModelComponents.valueOf(group);
//				if (!tourCharacteristics.containsKey(component)) {
//					throw new IllegalArgumentException("Invalid group: " + group);
//				}
//				if (component == ModelComponents.smallScaleGoodsTraffic || component == ModelComponents.commercialPersonTraffic) {
//					TourCharacteristics thisTourCharacteristics = tourCharacteristics.get(component).get(record.get("personId"));
//					thisTourCharacteristics.setNumberOfStops(Integer.parseInt(record.get("jobsPerTour")));
//				}
//			}
//		} catch (IOException e) {
//			throw new RuntimeException(e);
//		}
//	}
//
//	private void analyzeTourDistanceFile(HashMap<ModelComponents, HashMap<String, TourCharacteristics>> tourCharacteristics) {
//		try (
//			BufferedReader reader = Files.newBufferedReader(
//				inputPath.resolve("analysis").resolve("commercialTraffic").resolve("tourAnalysis_distances.csv"));
//			CSVParser parser = CSVFormat.DEFAULT.builder()
//				.setHeader()
//				.setSkipHeaderRecord(true)
//				.get()
//				.parse(reader)
//		) {
//			for (CSVRecord record : parser) {
//				String group = record.get("groupOfSubpopulation");
//				ModelComponents component = ModelComponents.valueOf(group);
//				if (!tourCharacteristics.containsKey(component)) {
//					throw new IllegalArgumentException("Invalid group: " + group);
//				}
//				if (component == ModelComponents.smallScaleGoodsTraffic || component == ModelComponents.commercialPersonTraffic) {
//					TourCharacteristics thisTourCharacteristics = tourCharacteristics.get(component).get(record.get("personId"));
//					thisTourCharacteristics.setDistance_km(Double.parseDouble(record.get("distanceInKm")));
//				}
//			}
//		} catch (IOException e) {
//			throw new RuntimeException(e);
//		}
//	}
//
//
//
//	private void getSmallScaleCommercialTrafficAgents(HashMap<ModelComponents, HashMap<String, TourCharacteristics>> tourCharacteristics) {
//		try (
//			BufferedReader reader = Files.newBufferedReader(
//				inputPath.resolve("analysis").resolve("commercialTraffic").resolve("tourAnalysis_durations.csv"));
//			CSVParser parser = CSVFormat.DEFAULT.builder()
//				.setHeader()
//				.setSkipHeaderRecord(true)
//				.get()
//				.parse(reader)
//		) {
//			for (CSVRecord record : parser) {
//				String group = record.get("groupOfSubpopulation");
//				ModelComponents component = ModelComponents.valueOf(group);
//				if (!tourCharacteristics.containsKey(component)) {
//					throw new IllegalArgumentException("Invalid group: " + group);
//				}
//				if (component == ModelComponents.smallScaleGoodsTraffic || component == ModelComponents.commercialPersonTraffic) {
//					TourCharacteristics thisTourCharacteristics = new TourCharacteristics();
//					thisTourCharacteristics.setDuration_s(Double.parseDouble(record.get("tourDurationInSeconds")));
//					thisTourCharacteristics.setVehicleType(record.get("vehicleType"));
//					tourCharacteristics.get(component).put(record.get("personId"), thisTourCharacteristics);
//				}
//			}
//		} catch (IOException e) {
//			throw new RuntimeException(e);
//		}
//	}
//
//
////	private record TourCharacteristics(double travelTime, double distance, double duration, String vehicleType, int numberOfTrips, int numberOfStops,
////	                                   boolean isRecharged, double score) {
////    }
//
//	private record TourCharacteristicsLongDistance(double travelTimeInSimulation, double distanceInSimulation, double durationInSimulation,
//	                                               double travelTimeWithBoundary, double distanceWithBoundary, double durationWithBoundary,
//	                                               double scoreInSimulation, double scoreWithBoundary, String vehicleType) {
//	}
//
//	private static class TourCharacteristics {
//
//		private double travelTime;
//		private double distance;
//		private double duration_s;
//		private String vehicleType;
//		private int numberOfTrips;
//		private int numberOfStops;
//		private boolean recharged;
//		private double score;
//
//		public TourCharacteristics() {
//		}
//
//		public TourCharacteristics(
//			double travelTime,
//			double distance,
//			double duration_s,
//			String vehicleType,
//			int numberOfTrips,
//			int numberOfStops,
//			boolean recharged,
//			double score) {
//			this.travelTime = travelTime;
//			this.distance = distance;
//			this.duration_s = duration_s;
//			this.vehicleType = vehicleType;
//			this.numberOfTrips = numberOfTrips;
//			this.numberOfStops = numberOfStops;
//			this.recharged = recharged;
//			this.score = score;
//		}
//
//		public double getTravelTime() {
//			return travelTime;
//		}
//
//		public void setTravelTime(double travelTime) {
//			this.travelTime = travelTime;
//		}
//
//		public double getDistance() {
//			return distance;
//		}
//
//		public void setDistance_km(double distance) {
//			this.distance = distance;
//		}
//
//		public double getDuration_s() {
//			return duration_s;
//		}
//
//		public void setDuration_s(double duration_s) {
//			this.duration_s = duration_s;
//		}
//
//		public String getVehicleType() {
//			return vehicleType;
//		}
//
//		public void setVehicleType(String vehicleType) {
//			this.vehicleType = vehicleType;
//		}
//
//		public int getNumberOfTrips() {
//			return numberOfTrips;
//		}
//
//		public void setNumberOfTrips(int numberOfTrips) {
//			this.numberOfTrips = numberOfTrips;
//		}
//
//		public int getNumberOfStops() {
//			return numberOfStops;
//		}
//
//		public void setNumberOfStops(int numberOfStops) {
//			this.numberOfStops = numberOfStops;
//		}
//
//		public boolean isRecharged() {
//			return recharged;
//		}
//
//		public void setRecharged(boolean recharged) {
//			this.recharged = recharged;
//		}
//
//		public double getScore() {
//			return score;
//		}
//
//		public void setScore(double score) {
//			this.score = score;
//		}
//	}
}


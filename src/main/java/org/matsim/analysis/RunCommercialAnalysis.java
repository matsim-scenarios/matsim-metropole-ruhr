package org.matsim.analysis;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.analysis.eventHandler.LinkVolumeCommercialEventHandler;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.application.ApplicationUtils.globFile;

/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
public class RunCommercialAnalysis implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(RunCommercialAnalysis.class);

	@CommandLine.Option(names = "--simulationOutputDirectory", required = true, description = "The directory where the simulation output is stored.", defaultValue = "scenarios/metropole-ruhr-v2.0/output/rvr/commercial_100pct/commercialTraffic_Run100pct")
	@CommandLine.Option(names = "--simulationOutputDirectory", required = true, description = "The directory where the simulation output is stored.", defaultValue = "scenarios/metropole-ruhr-v2024.0/output/016_10pct")
	private static Path runDirectory;

	@CommandLine.Option(names = "--runId", description = "The run id of the simulation.", defaultValue = "commercialTraffic_Run100pct")
	private static String runId;

	@CommandLine.Option(names = "--analysisOutputDirectory", description = "The directory where the analysis output will be stored.", defaultValue = "/analysis/traffic")
	private static String analysisOutputDirectory;

	@CommandLine.Option(names = "--zoneShapeFile", description = "The shape file of the zones of the VP2030.", defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040.shp")
	private static Path zoneShapeFile;

	@CommandLine.Option(names = "--shapeFileRuhrArea", description = "The shape file of the ruhr area.", defaultValue = "scenarios/metropole-ruhr-v2024.0/input/area/area.shp")
	private static Path shapeFileRuhrArea;

	@CommandLine.Option(names = "--sampleSize", description = "The sample size of the simulation.", defaultValue = "1.0")
	private static double sampleSize;

	public RunCommercialAnalysis(Path runDirectory, String runId, String analysisOutputDirectory, Path zoneShapeFile, double sampleSize, Path shapeFileRuhrArea) {
		RunCommercialAnalysis.runDirectory = runDirectory;
		RunCommercialAnalysis.runId = runId;
		if (analysisOutputDirectory != null && !analysisOutputDirectory.endsWith("/")) analysisOutputDirectory = analysisOutputDirectory + "/";
		RunCommercialAnalysis.analysisOutputDirectory = runDirectory + analysisOutputDirectory;
		RunCommercialAnalysis.zoneShapeFile = zoneShapeFile;
		RunCommercialAnalysis.sampleSize = sampleSize;
		RunCommercialAnalysis.shapeFileRuhrArea = shapeFileRuhrArea;
	}

	public static void main(String[] args) {
		System.exit(new CommandLine(new RunCommercialAnalysis(runDirectory, runId, analysisOutputDirectory, zoneShapeFile, sampleSize, shapeFileRuhrArea)).execute(args));
	}

	public Integer call() throws Exception {
		log.info("++++++++++++++++++ Start Analysis for RVR Freight simulations ++++++++++++++++++++++++++++");
		String shpZonesCRS = "EPSG:31467";
		ShpOptions shpZones = new ShpOptions(zoneShapeFile, shpZonesCRS, StandardCharsets.UTF_8);

		String shpRuhrAreaCRS = "EPSG:25832";
		ShpOptions shpRuhrArea = new ShpOptions(shapeFileRuhrArea, shpRuhrAreaCRS, StandardCharsets.UTF_8);

		final String eventsFile = globFile(runDirectory, runId, "output_events");
		final String personFile = globFile(runDirectory, runId, "output_persons");

		analysisOutputDirectory = runDirectory + analysisOutputDirectory;
		if (!analysisOutputDirectory.endsWith("/"))
			analysisOutputDirectory = analysisOutputDirectory + "/";
		File dir = new File(analysisOutputDirectory);
		if (!dir.exists()) {
			dir.mkdir();
		}

		// for SimWrapper
		final String linkDemandOutputFile = analysisOutputDirectory + runId + ".link_volume.csv";
		log.info("Writing volume per link to: {}", linkDemandOutputFile);

		final String travelDistancesPerModeOutputFile = analysisOutputDirectory + runId + ".travelDistancesShares.csv";
		log.info("Writing travel distances per mode to: {}", travelDistancesPerModeOutputFile);

		final String OD_zones_resultsOutputFile = analysisOutputDirectory + runId + ".OD_inZones.csv";
		log.info("Writing OD-Zones results to: {}", OD_zones_resultsOutputFile);

		final String travelDistancesPerVehicleOutputFile = analysisOutputDirectory + runId + ".travelDistances_perVehicle.csv";
		log.info("Writing travel distances per vehicle to: {}", travelDistancesPerVehicleOutputFile);

		final String relationsOutputFile = analysisOutputDirectory + runId + ".relations.csv";
		log.info("Writing relations to: {}", relationsOutputFile);

		final String tourDurationsOutputFile = analysisOutputDirectory + runId + ".tour_durations.csv";
		log.info("Writing tour durations to: {}", tourDurationsOutputFile);

		final String generalTravelDataOutputFile = analysisOutputDirectory + runId + ".generalTravelData.csv";
		log.info("Writing general travel data to: {}", generalTravelDataOutputFile);

		Config config = ConfigUtils.createConfig();
		config.vehicles().setVehiclesFile(String.valueOf(globFile(runDirectory, runId, "output_vehicles")));
		config.network().setInputFile(String.valueOf(globFile(runDirectory, runId, "network")));
//		config.facilities().setInputFile(String.valueOf(globFile(runDirectory, runId, "facilities")));

		config.global().setCoordinateSystem("EPSG:25832");
		log.info("Using coordinate system '{}'", config.global().getCoordinateSystem());
		config.plans().setInputFile(String.valueOf(globFile(runDirectory, runId, "plans.xml")));
		config.eventsManager().setNumberOfThreads(null);
		config.eventsManager().setEstimatedNumberOfEvents(null);
		config.global().setNumberOfThreads(4);

		Scenario scenario = ScenarioUtils.loadScenario(config);

		EventsManager eventsManager = EventsUtils.createEventsManager();

		// link events handler
		LinkVolumeCommercialEventHandler linkDemandEventHandler = new LinkVolumeCommercialEventHandler(scenario, personFile, sampleSize, shpZones, shpRuhrArea);
		eventsManager.addHandler(linkDemandEventHandler);

		eventsManager.initProcessing();

		log.info("-------------------------------------------------");
		log.info("Done reading the events file");
		log.info("Finish processing...");
		eventsManager.finishProcessing();
		new MatsimEventsReader(eventsManager).readFile(eventsFile);
		log.info("Closing events file...");

		createLinkVolumeAnalysis(scenario, linkDemandOutputFile, linkDemandEventHandler);
		createTravelDistancesShares(travelDistancesPerModeOutputFile, linkDemandEventHandler);
		createRelationsAnalysis(relationsOutputFile, linkDemandEventHandler);
		createGeneralTravelDataAnalysis(generalTravelDataOutputFile, linkDemandEventHandler, scenario);
		createAnalysisPerVehicle(travelDistancesPerVehicleOutputFile, linkDemandEventHandler);
		createTourDurationPerVehicle(tourDurationsOutputFile, linkDemandEventHandler, scenario);
//		createShpForDashboards(scenario, dirShape);

		log.info("Done");
		log.info("All output written to {}", analysisOutputDirectory);
		log.info("-------------------------------------------------");
		return 0;
	}

	private void createTourDurationPerVehicle(String tourDurationsOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler,
											  Scenario scenario) {

		HashMap<Id<Vehicle>, Double> tourDurations = linkDemandEventHandler.getTourDurationPerPerson();
		HashMap<Id<Vehicle>, Id<Person>> vehicleToPersonId = linkDemandEventHandler.getVehicleIdToPersonId();

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(tourDurationsOutputFile));
			bw.write("personId;");
			bw.write("vehicleId;");
			bw.write("subpopulation;");
			bw.write("tourDurationInSeconds;");
			bw.newLine();

			for (Id<Vehicle> vehicleId : tourDurations.keySet()) {
				Id<Person> personId = vehicleToPersonId.get(vehicleId);
				bw.write(personId + ";");
				bw.write(vehicleId + ";");
				bw.write(scenario.getPopulation().getPersons().get(personId).getAttributes().getAttribute("subpopulation") + ";");
				bw.write(tourDurations.get(vehicleId) + ";");
				bw.newLine();
			}

			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}

	}

	private void createGeneralTravelDataAnalysis(String generalTravelDataOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler, Scenario scenario) {

		HashMap<Id<Vehicle>, String> subpopulationPerVehicle = linkDemandEventHandler.getVehicleSubpopulation();
		Map<String, List<Id<Vehicle>>> vehiclesPerSubpopulation = subpopulationPerVehicle.entrySet().stream()
			.collect(Collectors.groupingBy(
				Map.Entry::getValue,
				Collectors.mapping(Map.Entry::getKey, Collectors.toList())
			));
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_internal = linkDemandEventHandler.getDistancesPerTrip_perPerson_internal();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_incoming = linkDemandEventHandler.getDistancesPerTrip_perPerson_incoming();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_outgoing = linkDemandEventHandler.getDistancesPerTrip_perPerson_outgoing();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_transit = linkDemandEventHandler.getDistancesPerTrip_perPerson_transit();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_all = linkDemandEventHandler.getDistancesPerTrip_perPerson_all();

		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_internal_inRuhrArea = linkDemandEventHandler.getDistancesPerTrip_perPerson_internal_inRuhrArea();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_incoming_inRuhrArea = linkDemandEventHandler.getDistancesPerTrip_perPerson_incoming_inRuhrArea();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_outgoing_inRuhrArea = linkDemandEventHandler.getDistancesPerTrip_perPerson_outgoing_inRuhrArea();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_transit_inRuhrArea = linkDemandEventHandler.getDistancesPerTrip_perPerson_transit_inRuhrArea();
		HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_all_inRuhrArea = linkDemandEventHandler.getDistancesPerTrip_perPerson_all_inRuhrArea();

		try {
			// _Intern: internal trips (start and end inside the area)
			// _Incoming: incoming trips (start outside the area and end inside the area)
			// _Outgoing: incoming trips (start inside the area and end outside the area)
			// _Transit: transit trips (start and end inside the area)
			// _all: all trips
			BufferedWriter bw = new BufferedWriter(new FileWriter(generalTravelDataOutputFile));
			bw.write("subpopulation;");
			bw.write("numberOfAgents;");

			bw.write("numberOfTrips_Intern;");
			bw.write("numberOfTrips_Incoming;");
			bw.write("numberOfTrips_Outgoing;");
			bw.write("numberOfTrips_Transit;");
			bw.write("numberOfTrips_all;");

			bw.write("traveledDistance_Intern;");
			bw.write("traveledDistance_Incoming;");
			bw.write("traveledDistance_Outgoing;");
			bw.write("traveledDistance_Transit;");
			bw.write("traveledDistance_all;");

			bw.write("traveledDistanceInRVR_area_Intern;");
			bw.write("traveledDistanceInRVR_area_Incoming;");
			bw.write("traveledDistanceInRVR_area_Outgoing;");
			bw.write("traveledDistanceInRVR_area_Transit;");
			bw.write("traveledDistanceInRVR_area_all;");

//			bw.write("averageTripsPerAgent_Intern;");
//			bw.write("averageTripsPerAgent_Incoming;");
//			bw.write("averageTripsPerAgent_Outgoing;");
//			bw.write("averageTripsPerAgent_Transit;");
			bw.write("averageTripsPerAgent_all;");

			bw.write("averageDistancePerTrip_Intern;");
			bw.write("averageDistancePerTrip_Incoming;");
			bw.write("averageDistancePerTrip_Outgoing;");
			bw.write("averageDistancePerTrip_Transit;");
			bw.write("averageDistancePerTrip_all;");
			bw.newLine();

			for (String subpopulation : vehiclesPerSubpopulation.keySet()){
				bw.write(subpopulation + ";");
				int numberOfAgentsInSubpopulation = vehiclesPerSubpopulation.get(subpopulation).size();
				bw.write(numberOfAgentsInSubpopulation + ";");

				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_internal_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_internal, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_incoming_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_incoming, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_outgoing_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_outgoing, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_transit_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_transit, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_all_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_all, subpopulation, scenario);

				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_internal_inRuhrArea_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_internal_inRuhrArea, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_incoming_inRuhrArea_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_incoming_inRuhrArea, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_outgoing_inRuhrArea_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_outgoing_inRuhrArea, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_transit_inRuhrArea_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_transit_inRuhrArea, subpopulation, scenario);
				HashMap<Id<Person>, List<Double>> distancesPerTrip_perPerson_all_inRuhrArea_perSubpopulation = filterBySubpopulation(distancesPerTrip_perPerson_all_inRuhrArea, subpopulation, scenario);

				int numberOfTrips_internal = distancesPerTrip_perPerson_internal_perSubpopulation.values().stream().mapToInt(List::size).sum();
				int numberOfTrips_incoming = distancesPerTrip_perPerson_incoming_perSubpopulation.values().stream().mapToInt(List::size).sum();
				int numberOfTrips_outgoing = distancesPerTrip_perPerson_outgoing_perSubpopulation.values().stream().mapToInt(List::size).sum();
				int numberOfTrips_transit = distancesPerTrip_perPerson_transit_perSubpopulation.values().stream().mapToInt(List::size).sum();
				int numberOfTrips_all = distancesPerTrip_perPerson_all_perSubpopulation.values().stream().mapToInt(List::size).sum();

				bw.write(numberOfTrips_internal + ";");
				bw.write(numberOfTrips_incoming + ";");
				bw.write(numberOfTrips_outgoing + ";");
				bw.write(numberOfTrips_transit + ";");
				bw.write(numberOfTrips_all + ";");

				double traveledDistance_internal = distancesPerTrip_perPerson_internal_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum();
				double traveledDistance_incoming = distancesPerTrip_perPerson_incoming_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum();
				double traveledDistance_outgoing = distancesPerTrip_perPerson_outgoing_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum();
				double traveledDistance_transit = distancesPerTrip_perPerson_transit_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum();
				double traveledDistance_all = distancesPerTrip_perPerson_all_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum();

				bw.write(traveledDistance_internal + ";");
				bw.write(traveledDistance_incoming + ";");
				bw.write(traveledDistance_outgoing + ";");
				bw.write(traveledDistance_transit + ";");
				bw.write(traveledDistance_all + ";");

				bw.write(distancesPerTrip_perPerson_internal_inRuhrArea_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum() + ";");
				bw.write(distancesPerTrip_perPerson_incoming_inRuhrArea_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum() + ";");
				bw.write(distancesPerTrip_perPerson_outgoing_inRuhrArea_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum() + ";");
				bw.write(distancesPerTrip_perPerson_transit_inRuhrArea_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum() + ";");
				bw.write(distancesPerTrip_perPerson_all_inRuhrArea_perSubpopulation.values().stream().flatMapToDouble(list -> list.stream().mapToDouble(Double::doubleValue)).sum() + ";");

				bw.write(numberOfTrips_all == 0 ? "0" : (double) numberOfTrips_all / numberOfAgentsInSubpopulation + ";");

				bw.write(numberOfTrips_internal == 0 ? "0;" : traveledDistance_internal / numberOfTrips_internal + ";");
				bw.write(numberOfTrips_incoming == 0 ? "0;" : traveledDistance_incoming / numberOfTrips_incoming + ";");
				bw.write(numberOfTrips_outgoing == 0 ? "0;" : traveledDistance_outgoing / numberOfTrips_outgoing + ";");
				bw.write(numberOfTrips_transit == 0 ? "0;" : traveledDistance_transit / numberOfTrips_transit + ";");
				bw.write(numberOfTrips_all == 0 ? "0" : String.valueOf(traveledDistance_all / numberOfTrips_all));

				bw.newLine();
			}


			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}

	}

	private HashMap<Id<Person>, List<Double>> filterBySubpopulation(HashMap<Id<Person>, List<Double>> distancesPerTripPerPerson, String subpopulation, Scenario scenario) {
		HashMap<Id<Person>, List<Double>> filteredList = new HashMap<>();
		distancesPerTripPerPerson.keySet().stream().filter(personId -> {
			Person person = scenario.getPopulation().getPersons().get(personId);
			return person.getAttributes().getAttribute("subpopulation").equals(subpopulation);
		}).forEach(personId -> {
			filteredList.put(personId, distancesPerTripPerPerson.get(personId));
		});
		return filteredList;
	}

	private void createAnalysisPerVehicle(String travelDistancesPerVehicleOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler) {

		HashMap<String, Object2DoubleOpenHashMap<String>> travelDistancesPerVehicle = linkDemandEventHandler.getTravelDistancesPerVehicle();
		HashMap<Id<Vehicle>, String> vehicleSubpopulations = linkDemandEventHandler.getVehicleSubpopulation();

		Map<String, Integer> maxDistanceWithDepotChargingInKilometers = createBatterieCapacitiesPerVehicleType();

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(travelDistancesPerVehicleOutputFile));
			bw.write("vehicleId;");
			bw.write("vehicleType;");
			bw.write("subpopulation;");
			bw.write("distanceInKm;");
			bw.write("distanceInKmWithDepotCharging;");
			bw.write("shareOfTravelDistanceWithDepotCharging");
			bw.newLine();
			for (String vehicleType : travelDistancesPerVehicle.keySet()) {
				Object2DoubleOpenHashMap<String> travelDistancesForVehiclesWithThisType = travelDistancesPerVehicle.get(vehicleType);
				for (String vehicleId : travelDistancesForVehiclesWithThisType.keySet()) {
					bw.write(vehicleId + ";");
					bw.write(vehicleType + ";");
					bw.write(vehicleSubpopulations.get(Id.createVehicleId(vehicleId)) + ";");
					double traveledDistanceInKm = Math.round(travelDistancesForVehiclesWithThisType.getDouble(vehicleId)/10)/100.0;
					bw.write(traveledDistanceInKm + ";");
					String maxDistanceWithoutRecharging;
					if (maxDistanceWithDepotChargingInKilometers.containsKey(vehicleType)) {
						maxDistanceWithoutRecharging = String.valueOf(maxDistanceWithDepotChargingInKilometers.get(vehicleType));
						bw.write(maxDistanceWithoutRecharging + ";");
					} else {
						throw new IllegalArgumentException("Vehicle type " + vehicleType + " not found in maxDistanceWithDepotChargingInKilometers map.");
					}
					bw.write(String.valueOf(
						Math.round(traveledDistanceInKm / (maxDistanceWithDepotChargingInKilometers.get(vehicleType)) * 100) / 100.0));
					bw.newLine();
				}
			}
			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}
	}

	@NotNull
	private static Map<String, Integer> createBatterieCapacitiesPerVehicleType() {
		Map<String, Integer> maxDistanceWithDepotChargingInKilometers = new HashMap<>();

		// Fahrzeugtyp und zugehörige maximale Reichweite (in Kilometern)
		maxDistanceWithDepotChargingInKilometers.put("golf1.4", 200);
		maxDistanceWithDepotChargingInKilometers.put("car", 200);
		maxDistanceWithDepotChargingInKilometers.put("vwCaddy", 120); // https://www.vw-nutzfahrzeuge.at/caddy/caddy/ehybrid
		maxDistanceWithDepotChargingInKilometers.put("mercedes313_parcel", 440); //https://www.adac.de/rund-ums-fahrzeug/autokatalog/marken-modelle/mercedes-benz/esprinter/
		maxDistanceWithDepotChargingInKilometers.put("mercedes313", 440);
		maxDistanceWithDepotChargingInKilometers.put("light8t", 174);
		maxDistanceWithDepotChargingInKilometers.put("medium18t", 395);
		maxDistanceWithDepotChargingInKilometers.put("medium18t_parcel", 395);
		maxDistanceWithDepotChargingInKilometers.put("waste_collection_diesel", 280);
		maxDistanceWithDepotChargingInKilometers.put("heavy40t", 416);
		maxDistanceWithDepotChargingInKilometers.put("truck40t", 416);
		return maxDistanceWithDepotChargingInKilometers;
	}

	private void createRelationsAnalysis(String relationsOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler) {
		File fileRelations = new File(relationsOutputFile);
		Map<Integer, Object2DoubleMap<String>> relations = linkDemandEventHandler.getRelations();
		ArrayList<String> header = findHeader(relations);
		ArrayList<Integer> relationNumbers = new ArrayList<>(relations.keySet());
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileRelations));
			bw.write("relationNumber;");
			bw.write(String.join(";", header));

			bw.newLine();
			for (Integer relationNumber : relationNumbers) {
				bw.write(relationNumber + ";");
				Object2DoubleMap<String> relation = relations.get(relationNumber);
				for (String value : header) {
					if (relation.containsKey(value))
						bw.write(String.valueOf(relation.getDouble(value)));
					else
						bw.write("0");
					if (header.indexOf(value) < header.size() - 1) {
						bw.write(";");
					}
				}
				bw.newLine();
			}
			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}

	}

	private ArrayList<String> findHeader(Map<Integer, Object2DoubleMap<String>> relations) {
		ArrayList<String> header = new ArrayList<>();
		for (Object2DoubleMap<String> relation : relations.values()) {
			for (String value : relation.keySet()) {
				if (!header.contains(value)) header.add(value);
			}
		}
		header.sort(Comparator.naturalOrder());
		return header;
	}

	private void createTravelDistancesShares(String travelDistancesPerModeOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler) {
		File filePerMode = new File(travelDistancesPerModeOutputFile.replace(".csv", "_perMode.csv"));
		Object2DoubleOpenHashMap<String> travelDistancesPerMode = linkDemandEventHandler.getTravelDistancesPerMode();
		writeDistanceFiles(travelDistancesPerMode, filePerMode);
		File filePerType = new File(travelDistancesPerModeOutputFile.replace(".csv", "_perType.csv"));
		Object2DoubleOpenHashMap<String> travelDistancesPerType = linkDemandEventHandler.getTravelDistancesPerType();
		writeDistanceFiles(travelDistancesPerType, filePerType);
		File filePerSubpopulation = new File(travelDistancesPerModeOutputFile.replace(".csv", "_perSubpopulation.csv"));
		Object2DoubleOpenHashMap<String> travelDistancesPerSubpopulation = linkDemandEventHandler.getTravelDistancesPerSubpopulation();
		writeDistanceFiles(travelDistancesPerSubpopulation, filePerSubpopulation);
	}

	private static void writeDistanceFiles(Object2DoubleOpenHashMap<String> travelDistancesPerMode, File file) {
		ArrayList<String> headerWithModes = new ArrayList<>(travelDistancesPerMode.keySet());
		headerWithModes.sort(Comparator.naturalOrder());
		double sumOfAllDistances = travelDistancesPerMode.values().doubleStream().sum();
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));

			bw.write(String.join(";", headerWithModes));

			bw.newLine();

			// Write the data row
			for (int i = 0; i < headerWithModes.size(); i++) {
				String mode = headerWithModes.get(i);
				bw.write(String.valueOf(travelDistancesPerMode.getDouble(mode) / sumOfAllDistances));

				// Add delimiter if it's not the last element
				if (i < headerWithModes.size() - 1) {
					bw.write(";");
				}
			}
			bw.newLine();
			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}
	}

	public void createLinkVolumeAnalysis(Scenario scenario, String linkDemandOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler) {

		File file = new File(linkDemandOutputFile);
		Map<Id<Link>, Object2DoubleOpenHashMap<String>> linkVolumesPerMode = linkDemandEventHandler.getLinkVolumesPerMode();
		ArrayList<String> headerWithModes = new ArrayList<>(List.of("allCommercialVehicles", "Small-Scale-Commercial-Traffic", "Transit-Freight-Traffic", "FTL-Traffic", "LTL-Traffic", "KEP", "FTL_kv-Traffic", "WasteCollection"));
		scenario.getVehicles().getVehicleTypes().values().forEach(vehicleType -> {
			if (!headerWithModes.contains(vehicleType.getNetworkMode())) headerWithModes.add(vehicleType.getNetworkMode());
		});
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(file));
			bw.write("linkId");
			for (String mode : headerWithModes) {
				bw.write(";" + mode);
			}
			bw.newLine();

			for (Id<Link> linkId : linkVolumesPerMode.keySet()) {
				bw.write(linkId.toString());
				for (String mode : headerWithModes) {
					bw.write(";" + (int) (linkVolumesPerMode.get(linkId).getDouble(mode)));
				}
				bw.newLine();
			}
			bw.close();
		} catch (IOException e) {
			log.error("Could not create output file", e);
		}
	}
}

package org.matsim.analysis;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.eventHandler.LinkVolumeCommercialEventHandler;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
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
	private static Path runDirectory;

	@CommandLine.Option(names = "--runId", description = "The run id of the simulation.", defaultValue = "commercialTraffic_Run100pct")
	private static String runId;

	@CommandLine.Option(names = "--analysisOutputDirectory", description = "The directory where the analysis output will be stored.", defaultValue = "/analysis/traffic")
	private static String analysisOutputDirectory;

	@CommandLine.Option(names = "--zoneShapeFile", description = "The shape file of the zones of the VP2030.", defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/cells_vp2040/cells_vp2040.shp")
	private static Path zoneShapeFile;

	@CommandLine.Option(names = "--shapeFileRuhrArea", description = "The shape file of the ruhr area.", defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp")
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

		Config config = ConfigUtils.createConfig();
		config.vehicles().setVehiclesFile(String.valueOf(globFile(runDirectory, runId, "output_vehicles")));
		config.network().setInputFile(String.valueOf(globFile(runDirectory, runId, "network")));
//		config.facilities().setInputFile(String.valueOf(globFile(runDirectory, runId, "facilities")));

		config.global().setCoordinateSystem("EPSG:25832");
		log.info("Using coordinate system '{}'", config.global().getCoordinateSystem());
//		config.plans().setInputFile(String.valueOf(globFile(runDirectory, runId, "plans.xml")));
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
		createAnalysisPerVehicle(travelDistancesPerVehicleOutputFile, linkDemandEventHandler);
//		createShpForDashboards(scenario, dirShape);

		log.info("Done");
		log.info("All output written to {}", analysisOutputDirectory);
		log.info("-------------------------------------------------");
		return 0;
	}

	private void createAnalysisPerVehicle(String travelDistancesPerVehicleOutputFile, LinkVolumeCommercialEventHandler linkDemandEventHandler) {

		HashMap<String, Object2DoubleOpenHashMap<String>> travelDistancesPerVehicle = linkDemandEventHandler.getTravelDistancesPerVehicle();
		HashMap<Id<Vehicle>, String> vehicleSubpopulations = linkDemandEventHandler.getVehicleSubpopulation();

		Map<String, Integer> maxDistanceWithDepotChargingInKilometers = new HashMap<>();

		// Fahrzeugtyp und zugehörige maximale Reichweite (in Kilometern)
		maxDistanceWithDepotChargingInKilometers.put("golf1.4", 200);
		maxDistanceWithDepotChargingInKilometers.put("vwCaddy", 120); // https://www.vw-nutzfahrzeuge.at/caddy/caddy/ehybrid
		maxDistanceWithDepotChargingInKilometers.put("mercedes313_parcel", 440); //https://www.adac.de/rund-ums-fahrzeug/autokatalog/marken-modelle/mercedes-benz/esprinter/
		maxDistanceWithDepotChargingInKilometers.put("mercedes313", 440);
		maxDistanceWithDepotChargingInKilometers.put("light8t", 174);
		maxDistanceWithDepotChargingInKilometers.put("medium18t", 395);
		maxDistanceWithDepotChargingInKilometers.put("medium18t_parcel", 395);
		maxDistanceWithDepotChargingInKilometers.put("waste_collection_diesel", 280);
		maxDistanceWithDepotChargingInKilometers.put("heavy40t", 416);
		maxDistanceWithDepotChargingInKilometers.put("truck40t", 416);

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
			e.printStackTrace();
		}
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
			e.printStackTrace();
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
			e.printStackTrace();
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
			e.printStackTrace();
		}
	}
}

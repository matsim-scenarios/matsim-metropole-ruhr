package org.matsim.analysis.eventHandler;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

public class LinkVolumeCommercialEventHandler implements LinkLeaveEventHandler, ActivityStartEventHandler {

	private final Map<Id<Link>, Object2DoubleOpenHashMap<String>> linkVolumesPerMode = new HashMap<>();
	private final Object2DoubleOpenHashMap<String> travelDistancesPerMode = new Object2DoubleOpenHashMap<>();
	private final Object2DoubleOpenHashMap<String> travelDistancesPerType = new Object2DoubleOpenHashMap<>();
	private final Map<Integer, Object2DoubleMap<String>> relations = new HashMap<>();
	private final Scenario scenario;
	private final ShpOptions.Index indexZones;
	private final Geometry geometryRuhrArea;
	private final double sampleSize;
	Map<String, Map<String, String>> personMap = new HashMap<>();

	public LinkVolumeCommercialEventHandler(Scenario scenario, String personFile, double sampleSize, ShpOptions shpZones, ShpOptions shpRuhrArea) throws IOException {
		this.indexZones = shpZones.createIndex(shpZones.getShapeCrs(), "name");
		this.geometryRuhrArea = shpRuhrArea.getGeometry();
		this.scenario = scenario;
		this.sampleSize = sampleSize;

		try (BufferedReader br = IOUtils.getBufferedReader(personFile)) {
			String line = br.readLine();  // Read the header line
			if (line != null) {
				// Split the header line by the delimiter to get column names
				String[] headers = line.split(";");

				// Read the rest of the lines
				while ((line = br.readLine()) != null) {
					// Split each line into values
					String[] values = line.split(";");

					// Assuming the first column is the "person"
					String person = values[0];

					// Create a map to store column values for the current person
					Map<String, String> personDetails = new HashMap<>();
					for (int i = 1; i < values.length; i++) {
						personDetails.put(headers[i], values[i]);  // Map column name to value
					}

					// Add the person and their details to the main map
					personMap.put(person, personDetails);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void reset(int iteration) {
		this.linkVolumesPerMode.clear();
		this.travelDistancesPerMode.clear();
		this.travelDistancesPerType.clear();
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {

		String personID = event.getPersonId().toString();
		Map<String, String> personAttributes = personMap.get(personID);
		String subpopulation = personAttributes.get("subpopulation").replace("_trip", "").replace("_service", "");

		if (subpopulation.equals("goodsTraffic") || subpopulation.contains("commercialPersonTraffic")) {
			if (event.getActType().contains("end")) {
				return;
			}
			if (event.getActType().equals("service")) {
				relations.computeIfAbsent(relations.size(), (k) -> new Object2DoubleOpenHashMap<>()).putIfAbsent(subpopulation + "_service_X",
					event.getCoord().getX());
				relations.get(relations.size() - 1).put(subpopulation + "_service_Y", event.getCoord().getY());
				relations.get(relations.size() - 1).put(subpopulation + "_start_X", Double.parseDouble(personAttributes.get("first_act_x")));
				relations.get(relations.size() - 1).put(subpopulation + "_start_Y", Double.parseDouble(personAttributes.get("first_act_y")));
			} else
				throw new IllegalArgumentException("Activity type is not service");
		}
		else if (subpopulation.equals("longDistanceFreight") || subpopulation.equals("FTL_kv") || subpopulation.equals("FTL")) {
			if (event.getActType().equals("freight_end")) {
				relations.computeIfAbsent(relations.size(), (k) -> new Object2DoubleOpenHashMap<>()).putIfAbsent(subpopulation + "_end_X",
					event.getCoord().getX());
				relations.get(relations.size() - 1).put(subpopulation + "_end_Y", event.getCoord().getY());
				relations.get(relations.size() - 1).put(subpopulation + "_start_X", Double.parseDouble(personAttributes.get("first_act_x")));
				relations.get(relations.size() - 1).put(subpopulation + "_start_Y", Double.parseDouble(personAttributes.get("first_act_y")));
			}
		}
		else if (subpopulation.equals("LTL")) {
			if (personID.contains("WasteCollection")) {
				subpopulation = subpopulation.replace("LTL", "WasteCollection");
				if (event.getActType().equals("pickup")) {
					relations.computeIfAbsent(relations.size(), (k) -> new Object2DoubleOpenHashMap<>()).putIfAbsent(subpopulation + "_pickup_X",
						event.getCoord().getX());
					relations.get(relations.size() - 1).put(subpopulation + "_pickup_Y", event.getCoord().getY());
					relations.get(relations.size() - 1).put(subpopulation + "_delivery_X", Double.parseDouble(personAttributes.get("first_act_x")));
					relations.get(relations.size() - 1).put(subpopulation + "_delivery_Y", Double.parseDouble(personAttributes.get("first_act_y")));
				}
				return;
			}
			if (event.getActType().equals("delivery")) {
				if (personID.contains("ParcelDelivery"))
					subpopulation = subpopulation.replace("LTL", "ParcelDelivery");
				relations.computeIfAbsent(relations.size(), (k) -> new Object2DoubleOpenHashMap<>()).putIfAbsent(subpopulation + "_delivery_X",
					event.getCoord().getX());
				relations.get(relations.size() - 1).put(subpopulation + "_delivery_Y", event.getCoord().getY());
				relations.get(relations.size() - 1).put(subpopulation + "_pickup_X", Double.parseDouble(personAttributes.get("first_act_x")));
				relations.get(relations.size() - 1).put(subpopulation + "_pickup_Y", Double.parseDouble(personAttributes.get("first_act_y")));
			}
		}
		else
			throw new IllegalArgumentException("Subpopulation is not recognized");
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		String mode = scenario.getVehicles().getVehicles().get(event.getVehicleId()).getType().getNetworkMode();
		Link link = scenario.getNetwork().getLinks().get(event.getLinkId());

		linkVolumesPerMode.computeIfAbsent(event.getLinkId(), (k) -> new Object2DoubleOpenHashMap<>());
		int factorForSampleOfInput = (int) (1/sampleSize);

		boolean inRuhrArea = geometryRuhrArea.contains(MGC.coord2Point(link.getCoord()));
		if (event.getVehicleId().toString().contains("goodsTraffic_") || event.getVehicleId().toString().contains("commercialPersonTraffic")) {
			String modelType = "Small-Scale-Commercial-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("longDistanceFreight")) {
			String modelType = "Long-Distance-Freight-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("FTL_kv")) {
			String modelType = "FTL_kv-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("FTL")) {
			String modelType = "FTL-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("ParcelDelivery_")) {
			String modelType = "KEP";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("WasteCollection_")) {
			String modelType = "WasteCollection";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("GoodsType_")) {
			String modelType = "LTL-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}
		else if (event.getVehicleId().toString().contains("freight_") && !event.getVehicleId().toString().contains("FTL")) {
			String modelType = "Transit-Freight-Traffic";
			linkVolumesPerMode.get(event.getLinkId()).mergeDouble(modelType, factorForSampleOfInput, Double::sum);
			if (inRuhrArea) {
				travelDistancesPerType.mergeDouble(modelType, link.getLength(), Double::sum);
			}
		}

		linkVolumesPerMode.get(event.getLinkId()).mergeDouble("allCommercialVehicles", factorForSampleOfInput, Double::sum);

		linkVolumesPerMode.get(event.getLinkId()).mergeDouble(mode, factorForSampleOfInput, Double::sum);
		if (inRuhrArea) {
			travelDistancesPerMode.mergeDouble(mode, link.getLength(), Double::sum);
//			travelDistancesPerMode.mergeDouble("allModes", link.getLength(), Double::sum);
//			travelDistancesPerType.mergeDouble("allCommercialVehicles", link.getLength(), Double::sum);
		}
	}

	public Map<Id<Link>, Object2DoubleOpenHashMap<String>> getLinkVolumesPerMode() {
		return linkVolumesPerMode;
	}

	public Object2DoubleOpenHashMap<String> getTravelDistancesPerMode() {
		return travelDistancesPerMode;
	}

	public Object2DoubleOpenHashMap<String> getTravelDistancesPerType() {
		return travelDistancesPerType;
	}

	public Map<Integer, Object2DoubleMap<String>> getRelations() {
		return relations;
	}
}


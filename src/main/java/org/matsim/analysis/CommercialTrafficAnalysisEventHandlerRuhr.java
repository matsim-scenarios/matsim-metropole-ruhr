package org.matsim.analysis;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.analysis.commercialTraffic.CommercialTrafficAnalysisEventHandler;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.common.conventions.vsp.SubpopulationDefaultNames;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.vehicles.Vehicle;

import java.util.*;

public class CommercialTrafficAnalysisEventHandlerRuhr implements LinkLeaveEventHandler, ActivityStartEventHandler, VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler, ActivityEndEventHandler, PersonDepartureEventHandler, PersonArrivalEventHandler {

	private static final Logger log = LogManager.getLogger(CommercialTrafficAnalysisEventHandler.class);
	private final Scenario scenario;
	private final Geometry geometryInvestigationArea;

	private final HashMap<Id<Person>, String> personsToAnalyze = new HashMap<>();
	private final HashMap<Id<Vehicle>, String> groupOfAnalyzedVehicles = new HashMap<>();
	private final HashMap<Id<Vehicle>, Id<Person>> vehicleIdToPersonId = new HashMap<>();

	private final HashMap<String, Object2DoubleOpenHashMap<Id<Person>>> travelDistancesPerVehicle = new HashMap<>();
	private final HashMap<Id<Person>, Object2DoubleOpenHashMap<String>> durationsPerVehicle = new HashMap<>();

	private final HashMap<Id<Person>, Double> tourStartPerPerson = new HashMap<>();
	private final HashMap<Id<Person>, Double> tourEndPerPerson = new HashMap<>();
	private final HashMap<Id<Person>, Double> tripStartTimePerPerson = new HashMap<>();
	private final HashMap<Id<Person>, Double> activityStartPerPerson = new HashMap<>();


	private final Object2DoubleOpenHashMap<String> travelDistancesPerMode = new Object2DoubleOpenHashMap<>();
	private final Object2DoubleOpenHashMap<String> travelDistancesPerComponent = new Object2DoubleOpenHashMap<>();
	private final Object2DoubleOpenHashMap<String> travelDistancesPerSubpopulation = new Object2DoubleOpenHashMap<>();
	private final Object2IntOpenHashMap<String> jobsPerComponent = new Object2IntOpenHashMap<String>();

	public CommercialTrafficAnalysisEventHandlerRuhr(Scenario scenario, ShpOptions shpInvestigationArea) {
		if (shpInvestigationArea.getShapeFile() != null)
			this.geometryInvestigationArea = shpInvestigationArea.getGeometry();
		else
			this.geometryInvestigationArea = null;
		this.scenario = scenario;

	}


	@Override
	public void handleEvent(ActivityEndEvent event) {

		if (activityStartPerPerson.containsKey(event.getPersonId())) {
			double activityDuration = event.getTime() - activityStartPerPerson.get(event.getPersonId());
			activityStartPerPerson.remove(event.getPersonId());
			if (TripStructureUtils.isStageActivityType(event.getActType())) {
				durationsPerVehicle.computeIfAbsent(event.getPersonId(), _ -> new Object2DoubleOpenHashMap<>()).mergeDouble("stagingActivityDurations",
					activityDuration, Double::sum);
				return;
			}
			durationsPerVehicle.computeIfAbsent(event.getPersonId(), _ -> new Object2DoubleOpenHashMap<>()).mergeDouble("activityDurations", activityDuration, Double::sum);
			jobsPerComponent.mergeInt(personsToAnalyze.get(event.getPersonId()), 1, Integer::sum);
			return;
		}
		Person person = scenario.getPopulation().getPersons().get(event.getPersonId());
		String component = getComponent(person);

		// of the small scale commercial traffic we only want agents starting in the investigation area. For all other components we analyze all agents, because the generation (or cut out) is already for this area
		if ((component.equals(SubpopulationDefaultNames.SUBPOP_COM_PERSON) ||
			component.equals(SubpopulationDefaultNames.SUBPOP_COM_PERSON_SERVICE) ||
			component.equals(SubpopulationDefaultNames.SUBPOP_GOODS))
			&& !startsInInvestigationArea(person)) {
			return;
		}
		personsToAnalyze.put(event.getPersonId(), component);
		tourStartPerPerson.computeIfAbsent(event.getPersonId(), personId -> event.getTime());
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (!personsToAnalyze.containsKey(event.getPersonId()))
			return;
		tourEndPerPerson.put(event.getPersonId(), event.getTime());
		activityStartPerPerson.put(event.getPersonId(), event.getTime());
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		if (!groupOfAnalyzedVehicles.containsKey(event.getVehicleId()))
			return;
		String mode = scenario.getVehicles().getVehicles().get(event.getVehicleId()).getType().getNetworkMode();
		Link link = scenario.getNetwork().getLinks().get(event.getLinkId());
		String component = groupOfAnalyzedVehicles.get(event.getVehicleId());

		String vehicleType = scenario.getVehicles().getVehicles().get(event.getVehicleId()).getType().getId().toString();
		travelDistancesPerVehicle.computeIfPresent(vehicleType, (k, v) -> {
			v.mergeDouble(vehicleIdToPersonId.get(event.getVehicleId()), link.getLength(), Double::sum);
			return v;
		});

		travelDistancesPerComponent.mergeDouble(component, link.getLength(), Double::sum);
		travelDistancesPerSubpopulation.mergeDouble(PopulationUtils.getSubpopulation(scenario.getPopulation().getPersons().get(vehicleIdToPersonId.get(event.getVehicleId()))), link.getLength(), Double::sum);
		travelDistancesPerMode.mergeDouble(mode, link.getLength(), Double::sum);
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		// person will not be analyzed
		if (!personsToAnalyze.containsKey(event.getPersonId()))
			return;

		tripStartTimePerPerson.put(event.getPersonId(), event.getTime());
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		if (!personsToAnalyze.containsKey(event.getPersonId()))
			return;
		// add the duration of this trip
		double tripDuration = event.getTime() - tripStartTimePerPerson.get(event.getPersonId());
		durationsPerVehicle.computeIfAbsent(event.getPersonId(), _ -> new Object2DoubleOpenHashMap<>()).mergeDouble("travelDurations", tripDuration, Double::sum);
		tripStartTimePerPerson.remove(event.getPersonId());
	}
	/**
	 * Detects the tour start of a vehicle. All already startet vehicles this will do nothing
	 */
	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {

		// person will not be analyzed
		if (!personsToAnalyze.containsKey(event.getPersonId()))
			return;

		// vehicle is already detected as relevant for analysis
		if (groupOfAnalyzedVehicles.containsKey(event.getVehicleId())) {
			return;
		}

		Person person = scenario.getPopulation().getPersons().get(event.getPersonId());
		String component = getComponent(person);

		vehicleIdToPersonId.put(event.getVehicleId(), event.getPersonId());

		groupOfAnalyzedVehicles.computeIfAbsent(event.getVehicleId(), _ -> component);
		String vehicleType = scenario.getVehicles().getVehicles().get(event.getVehicleId()).getType().getId().toString();
		travelDistancesPerVehicle.computeIfAbsent(vehicleType, vehicleTypeId -> new Object2DoubleOpenHashMap<>()).mergeDouble(vehicleIdToPersonId.get(event.getVehicleId()), 0, Double::sum);
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {

//		if (!personsToAnalyze.containsKey(event.getPersonId()))
//			return;
//		// add the duration of this trip
//		double tripDuration = event.getTime() - tripStartTimePerPerson.get(event.getPersonId());
//		durationsPerVehicle.computeIfAbsent(event.getPersonId(), _ -> new Object2DoubleOpenHashMap<>()).mergeDouble("travelDurations", tripDuration, Double::sum);
//		tripStartTimePerPerson.remove(event.getPersonId());
	}


	private static String getComponent(Person person){
		String personID = person.getId().toString();
		String subpopulation = PopulationUtils.getSubpopulation(person);
		if (subpopulation.equals(SubpopulationDefaultNames.SUBPOP_COM_PERSON) || subpopulation.equals(SubpopulationDefaultNames.SUBPOP_COM_PERSON_SERVICE))
			return SubpopulationDefaultNames.SUBPOP_COM_PERSON;
		else if (subpopulation.equals(SubpopulationDefaultNames.SUBPOP_GOODS)) {
			return SubpopulationDefaultNames.SUBPOP_GOODS;
		} else if (subpopulation.equals(SubpopulationDefaultNames.SUBPOP_LONG_DISTANCE_FREIGHT)) {
			return SubpopulationDefaultNames.SUBPOP_LONG_DISTANCE_FREIGHT;
		} else if (subpopulation.equals("LTL_trip") && personID.contains("GoodsType_")) {
			return "LTL";
		} else if (subpopulation.equals("LTL_trip") && personID.contains("WasteCollection_")) {
			return "WasteCollection";
		} else if (subpopulation.equals("LTL_trip") && personID.contains("ParcelDelivery_")) {
			return "CEP";
		} else if (subpopulation.equals("FTL_trip")) {
			return "FTL";
		}
		else {
			throw new RuntimeException("Unknown subpopulation: " + subpopulation);
		}
	}

	private boolean startsInInvestigationArea(Person person) {
		if (geometryInvestigationArea == null)
			return true;

		List<Activity> activities = PopulationUtils.getActivities(person.getSelectedPlan(),
			TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

		if (activities.isEmpty() || activities.getFirst().getCoord() == null)
			return false;

		return geometryInvestigationArea.contains(MGC.coord2Point(activities.getFirst().getCoord()));
	}

	public HashMap<Id<Person>, Double> getTourDurationPerPerson() {
		HashMap<Id<Person>, Double> tourDurationPerPerson = new HashMap<>();
		for (Id<Person> personId : tourStartPerPerson.keySet()) {
			if (tourEndPerPerson.containsKey(personId)) {
				tourDurationPerPerson.put(personId, tourEndPerPerson.get(personId) - tourStartPerPerson.get(personId));
			}
		}
		return tourDurationPerPerson;
	}

	public HashMap<Id<Person>, String> getAnalyzedPersons() {
		return personsToAnalyze;
	}
	public Object2IntOpenHashMap<String> getJobsPerComponent () {
		return jobsPerComponent;
	}

	public Object2DoubleOpenHashMap<String> getTravelDistancesPerComponent() {
		return travelDistancesPerComponent;
	}
	public HashMap<Id<Person>, Object2DoubleOpenHashMap<String>> getDurationsPerVehicle() {
		return durationsPerVehicle;
	}
	public HashMap<String, Object2DoubleOpenHashMap<Id<Person>>> getTravelDistancesPerVehicle() {
		return travelDistancesPerVehicle;
	}

}

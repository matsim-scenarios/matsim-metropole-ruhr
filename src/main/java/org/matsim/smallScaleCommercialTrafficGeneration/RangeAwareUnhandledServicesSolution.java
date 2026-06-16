package org.matsim.smallScaleCommercialTrafficGeneration;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;
import org.matsim.vehicles.CostInformation;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds a high-cost long-range fallback vehicle for services that cannot be reached by the current range-constrained fleet.
 * The regular {@link DefaultUnhandledServicesSolution} is still used afterwards for the existing time-budget fixes.
 */
public final class RangeAwareUnhandledServicesSolution implements UnhandledServicesSolution {

	private static final Logger log = LogManager.getLogger(RangeAwareUnhandledServicesSolution.class);
	private static final String LONG_RANGE_TYPE_SUFFIX = "_oneRecharge";

	private final double distanceConstraintUsableRange;
	private final double rangeMultiplier;
	private final double fixedCostMultiplier;
	private GenerateSmallScaleCommercialTrafficDemand generator;

	public RangeAwareUnhandledServicesSolution(double distanceConstraintUsableRange, double rangeMultiplier, double fixedCostMultiplier) {
		if (!Double.isFinite(distanceConstraintUsableRange) || distanceConstraintUsableRange <= 0. || distanceConstraintUsableRange > 100.) {
			throw new IllegalArgumentException("distanceConstraintUsableRange must be in the range (0, 100].");
		}
		if (!Double.isFinite(rangeMultiplier) || rangeMultiplier <= 1.) {
			throw new IllegalArgumentException("rangeMultiplier must be greater than 1.");
		}
		if (!Double.isFinite(fixedCostMultiplier) || fixedCostMultiplier < 1.) {
			throw new IllegalArgumentException("fixedCostMultiplier must be greater than or equal to 1.");
		}
		this.distanceConstraintUsableRange = distanceConstraintUsableRange;
		this.rangeMultiplier = rangeMultiplier;
		this.fixedCostMultiplier = fixedCostMultiplier;
	}

	public void setGenerator(GenerateSmallScaleCommercialTrafficDemand generator) {
		this.generator = generator;
	}

	/**
	 * Runs the range pre-check first and then keeps the default unhandled-service repair logic.
	 */
	@Override
	public void tryToSolveAllCarriersCompletely(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers) {
		if (generator == null) {
			throw new IllegalStateException("Generator must be set before using RangeAwareUnhandledServicesSolution.");
		}
		Result result = addLongRangeVehiclesForRangeInfeasibleServices(scenario, nonCompleteSolvedCarriers);
		if (result.addedVehicles() > 0 || result.servicesBeyondOneRechargeRange() > 0) {
			log.info("Range-aware pre-check found {} services outside the current vehicle range. Added {} high-cost long-range vehicles for {} carriers. {} services were still outside the {}x range.",
				result.rangeInfeasibleServices(), result.addedVehicles(), result.carriersWithAddedVehicles(),
				result.servicesBeyondOneRechargeRange(), rangeMultiplier);
		}

		new DefaultUnhandledServicesSolution(generator).tryToSolveAllCarriersCompletely(scenario, nonCompleteSolvedCarriers);
	}

	/**
	 * Adds at most one long-range fallback vehicle per carrier if currently unhandled services cannot be reached
	 * by range-constrained vehicles, but would be reachable with the configured range multiplier.
	 */
	Result addLongRangeVehiclesForRangeInfeasibleServices(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers) {
		CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
		Set<VehicleType> vehicleTypes = nonCompleteSolvedCarriers.stream()
			.map(Carrier::getCarrierCapabilities)
			.filter(Objects::nonNull)
			.map(CarrierCapabilities::getVehicleTypes)
			.flatMap(Collection::stream)
			.collect(Collectors.toSet());
		NetworkBasedTransportCosts transportCosts = NetworkBasedTransportCosts.Builder
			.newInstance(scenario.getNetwork(), vehicleTypes)
			.build();

		Result result = new Result();
		for (Carrier carrier : nonCompleteSolvedCarriers) {
			List<CarrierService> unhandledServices = getUnhandledServices(carrier);
			if (unhandledServices.isEmpty()) {
				continue;
			}

			Optional<RangeCandidate> candidate = findBestRangeCandidate(carrier, unhandledServices, transportCosts, result);
			if (candidate.isEmpty()) {
				continue;
			}

			if (addLongRangeVehicle(carrier, carrierVehicleTypes, candidate.get())) {
				result.addedVehicles++;
				result.carriersWithAddedVehicles++;
			}
		}
		return result;
	}

	/**
	 * Returns all services that are not represented in the selected plan. Without a selected plan, all services are open.
	 */
	private List<CarrierService> getUnhandledServices(Carrier carrier) {
		CarrierPlan selectedPlan = carrier.getSelectedPlan();
		if (selectedPlan == null) {
			return List.copyOf(carrier.getServices().values());
		}

		Set<CarrierService> handledServices = selectedPlan.getScheduledTours().stream()
			.map(ScheduledTour::getTour)
			.flatMap(tour -> tour.getTourElements().stream())
			.filter(Tour.ServiceActivity.class::isInstance)
			.map(Tour.ServiceActivity.class::cast)
			.map(Tour.ServiceActivity::getService)
			.collect(Collectors.toSet());

		return carrier.getServices().values().stream()
			.filter(service -> !handledServices.contains(service))
			.toList();
	}

	/**
	 * Finds the best existing vehicle to clone as a long-range fallback.
	 * If any current vehicle has no range constraint, the carrier is skipped because the fleet is not range-limited.
	 */
	private Optional<RangeCandidate> findBestRangeCandidate(Carrier carrier, List<CarrierService> unhandledServices,
	                                                       NetworkBasedTransportCosts transportCosts, Result result) {
		List<CarrierVehicle> currentVehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values().stream()
			.filter(vehicle -> !isLongRangeFallbackVehicle(vehicle))
			.toList();
		if (currentVehicles.stream().anyMatch(vehicle -> usableRange(vehicle.getType()).isEmpty())) {
			return Optional.empty();
		}

		List<CarrierVehicle> vehiclesWithRange = currentVehicles.stream()
			.filter(vehicle -> usableRange(vehicle.getType()).isPresent())
			.toList();
		if (vehiclesWithRange.isEmpty()) {
			return Optional.empty();
		}

		Optional<RangeCandidate> bestCandidate = Optional.empty();
		for (CarrierService service : unhandledServices) {
			List<ServiceReachability> reachabilityByVehicle = vehiclesWithRange.stream()
				.map(vehicle -> calculateReachability(vehicle, service, transportCosts))
				.toList();

			boolean reachableWithCurrentRange = reachabilityByVehicle.stream()
				.anyMatch(ServiceReachability::reachableWithCurrentRange);
			if (reachableWithCurrentRange) {
				continue;
			}

			result.rangeInfeasibleServices++;
			Optional<ServiceReachability> oneRechargeCandidate = reachabilityByVehicle.stream()
				.filter(ServiceReachability::reachableWithLongRange)
				.min(Comparator.comparingDouble(ServiceReachability::longRangeUtilization));
			if (oneRechargeCandidate.isEmpty()) {
				result.servicesBeyondOneRechargeRange++;
				double shortestRoundTrip = reachabilityByVehicle.stream()
					.mapToDouble(ServiceReachability::roundTripDistance)
					.min()
					.orElse(Double.NaN);
				log.warn("Carrier '{}' has service '{}' that is outside the current range and still not reachable with a {}x range. Shortest depot-service-depot distance is {} km.",
					carrier.getId(), service.getId(), rangeMultiplier, shortestRoundTrip / 1000.);
				continue;
			}

			ServiceReachability selectedReachability = oneRechargeCandidate.get();
			if (bestCandidate.isEmpty() || selectedReachability.longRangeUtilization() < bestCandidate.get().longRangeUtilization()) {
				bestCandidate = Optional.of(new RangeCandidate(selectedReachability.vehicle(), selectedReachability.longRangeUtilization()));
			}
		}

		return bestCandidate;
	}

	/**
	 * Calculates whether a single service can be covered as depot-service-depot tour by the current or multiplied range.
	 */
	private ServiceReachability calculateReachability(CarrierVehicle vehicle, CarrierService service,
	                                                 NetworkBasedTransportCosts transportCosts) {
		double departureTime = Math.max(0., vehicle.getEarliestStartTime());
		Location depot = Location.newInstance(vehicle.getLinkId().toString());
		Location serviceLocation = Location.newInstance(service.getServiceLinkId().toString());
		com.graphhopper.jsprit.core.problem.vehicle.Vehicle jspritVehicle = createJspritVehicle(vehicle);

		double outboundDistance = transportCosts.getDistance(depot, serviceLocation, departureTime, jspritVehicle);
		double returnDistance = transportCosts.getDistance(serviceLocation, depot, departureTime, jspritVehicle);
		double roundTripDistance = outboundDistance + returnDistance;
		double usableRange = usableRange(vehicle.getType()).orElseThrow();
		double longRange = usableRange * rangeMultiplier;

		return new ServiceReachability(vehicle, roundTripDistance, usableRange, longRange);
	}

	/**
	 * Creates the small jsprit vehicle object needed by MATSim's network-based cost calculator.
	 */
	private static com.graphhopper.jsprit.core.problem.vehicle.Vehicle createJspritVehicle(CarrierVehicle vehicle) {
		VehicleTypeImpl jspritType = VehicleTypeImpl.Builder.newInstance(vehicle.getType().getId().toString())
			.setMaxVelocity(vehicle.getType().getMaximumVelocity())
			.build();
		return VehicleImpl.Builder.newInstance(vehicle.getId().toString())
			.setStartLocation(Location.newInstance(vehicle.getLinkId().toString()))
			.setType(jspritType)
			.build();
	}

	/**
	 * Reconstructs the same usable range concept as MATSim's distance constraint from capacity, consumption, and margin.
	 */
	private Optional<Double> usableRange(VehicleType vehicleType) {
		EngineInformation engineInformation = vehicleType.getEngineInformation();
		Double energyCapacity = VehicleUtils.getEnergyCapacity(engineInformation);
		if (energyCapacity == null) {
			return Optional.empty();
		}
		Double consumptionPerMeter = getConsumptionPerMeter(vehicleType);
		if (consumptionPerMeter == null || consumptionPerMeter <= 0.) {
			return Optional.empty();
		}
		return Optional.of(energyCapacity / consumptionPerMeter * distanceConstraintUsableRange / 100.);
	}

	/**
	 * Uses electric consumption for electric vehicles and fuel consumption for all other vehicle technologies.
	 */
	private static Double getConsumptionPerMeter(VehicleType vehicleType) {
		String hbefaTechnology = VehicleUtils.getHbefaTechnology(vehicleType.getEngineInformation());
		if ("electricity".equals(hbefaTechnology)) {
			return VehicleUtils.getEnergyConsumptionKWhPerMeter(vehicleType.getEngineInformation());
		}
		return VehicleUtils.getFuelConsumptionLitersPerMeter(vehicleType.getEngineInformation());
	}

	/**
	 * Excludes vehicles already created by this repair step from the next range diagnosis.
	 */
	private static boolean isLongRangeFallbackVehicle(CarrierVehicle vehicle) {
		return vehicle.getType().getId().toString().endsWith(LONG_RANGE_TYPE_SUFFIX);
	}

	/**
	 * Adds the cloned vehicle type to the carrier capabilities and creates a concrete vehicle at the reference depot.
	 */
	private boolean addLongRangeVehicle(Carrier carrier, CarrierVehicleTypes carrierVehicleTypes, RangeCandidate candidate) {
		CarrierVehicle referenceVehicle = candidate.vehicle();
		VehicleType longRangeVehicleType = getOrCreateLongRangeVehicleType(carrierVehicleTypes, referenceVehicle.getType());
		addVehicleTypeToCarrierCapabilities(carrier.getCarrierCapabilities(), longRangeVehicleType);

		Id<Vehicle> vehicleId = nextLongRangeVehicleId(carrier, referenceVehicle.getId());
		if (carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicleId)) {
			return false;
		}

		CarrierVehicle longRangeVehicle = CarrierVehicle.Builder.newInstance(vehicleId, referenceVehicle.getLinkId(), longRangeVehicleType)
			.setEarliestStart(referenceVehicle.getEarliestStartTime())
			.setLatestEnd(referenceVehicle.getLatestEndTime())
			.build();
		referenceVehicle.getAttributes().getAsMap().forEach((key, value) -> longRangeVehicle.getAttributes().putAttribute(key, value));
		carrier.getCarrierCapabilities().getCarrierVehicles().put(longRangeVehicle.getId(), longRangeVehicle);
		log.info("Added range fallback vehicle '{}' of type '{}' to carrier '{}' based on '{}'.",
			longRangeVehicle.getId(), longRangeVehicleType.getId(), carrier.getId(), referenceVehicle.getId());
		return true;
	}

	/**
	 * Clones the reference type once, increases stored energy, and raises fixed costs to make it an unattractive fallback.
	 */
	private VehicleType getOrCreateLongRangeVehicleType(CarrierVehicleTypes carrierVehicleTypes, VehicleType referenceType) {
		Id<VehicleType> longRangeTypeId = Id.create(referenceType.getId() + LONG_RANGE_TYPE_SUFFIX, VehicleType.class);
		VehicleType existingType = carrierVehicleTypes.getVehicleTypes().get(longRangeTypeId);
		if (existingType != null) {
			return existingType;
		}

		VehicleType longRangeType = VehicleUtils.createVehicleType(longRangeTypeId);
		copyVehicleType(referenceType, longRangeType);

		Double energyCapacity = VehicleUtils.getEnergyCapacity(referenceType.getEngineInformation());
		if (energyCapacity != null) {
			VehicleUtils.setEnergyCapacity(longRangeType.getEngineInformation(), energyCapacity * rangeMultiplier);
		}
		CostInformation costInformation = longRangeType.getCostInformation();
		if (costInformation.getFixedCosts() != null) {
			costInformation.setFixedCost(costInformation.getFixedCosts() * fixedCostMultiplier);
		}
		longRangeType.setDescription((referenceType.getDescription() == null ? referenceType.getId().toString() : referenceType.getDescription())
			+ " (range fallback, assumes one recharge)");
		carrierVehicleTypes.getVehicleTypes().put(longRangeType.getId(), longRangeType);
		return longRangeType;
	}

	/**
	 * Copies the operational, cost, capacity, engine, and custom attributes of the original type.
	 */
	private static void copyVehicleType(VehicleType source, VehicleType target) {
		target.setMaximumVelocity(source.getMaximumVelocity())
			.setDescription(source.getDescription())
			.setPcuEquivalents(source.getPcuEquivalents())
			.setLength(source.getLength())
			.setWidth(source.getWidth())
			.setFlowEfficiencyFactor(source.getFlowEfficiencyFactor());
		if (source.hasNetworkMode()) {
			target.setNetworkMode(source.getNetworkMode());
		}
		AttributesUtils.copyAttributesFromTo(source, target);

		CostInformation sourceCost = source.getCostInformation();
		target.getCostInformation()
			.setCostsPerSecond(sourceCost.getCostsPerSecond())
			.setCostsPerMeter(sourceCost.getCostsPerMeter())
			.setFixedCost(sourceCost.getFixedCosts());
		AttributesUtils.copyAttributesFromTo(sourceCost, target.getCostInformation());

		VehicleCapacity sourceCapacity = source.getCapacity();
		target.getCapacity()
			.setSeats(sourceCapacity.getSeats())
			.setStandingRoom(sourceCapacity.getStandingRoom())
			.setVolumeInCubicMeters(sourceCapacity.getVolumeInCubicMeters())
			.setWeightInTons(sourceCapacity.getWeightInTons())
			.setOther(sourceCapacity.getOther());
		AttributesUtils.copyAttributesFromTo(sourceCapacity, target.getCapacity());

		AttributesUtils.copyAttributesFromTo(source.getEngineInformation(), target.getEngineInformation());
	}

	/**
	 * Makes the cloned type available for this carrier without duplicating an already registered type.
	 */
	private static void addVehicleTypeToCarrierCapabilities(CarrierCapabilities carrierCapabilities, VehicleType vehicleType) {
		Set<Id<VehicleType>> existingTypeIds = carrierCapabilities.getVehicleTypes().stream()
			.map(VehicleType::getId)
			.collect(Collectors.toCollection(HashSet::new));
		if (!existingTypeIds.contains(vehicleType.getId())) {
			carrierCapabilities.getVehicleTypes().add(vehicleType);
		}
	}

	/**
	 * Creates a deterministic fallback id and appends a numeric suffix if the carrier already has one.
	 */
	private static Id<Vehicle> nextLongRangeVehicleId(Carrier carrier, Id<Vehicle> referenceVehicleId) {
		String baseId = referenceVehicleId + LONG_RANGE_TYPE_SUFFIX;
		Id<Vehicle> vehicleId = Id.create(baseId, Vehicle.class);
		int index = 1;
		while (carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicleId)) {
			vehicleId = Id.create(baseId + "_" + index, Vehicle.class);
			index++;
		}
		return vehicleId;
	}

	static final class Result {
		private int rangeInfeasibleServices;
		private int servicesBeyondOneRechargeRange;
		private int addedVehicles;
		private int carriersWithAddedVehicles;

		int rangeInfeasibleServices() {
			return rangeInfeasibleServices;
		}

		int servicesBeyondOneRechargeRange() {
			return servicesBeyondOneRechargeRange;
		}

		int addedVehicles() {
			return addedVehicles;
		}

		int carriersWithAddedVehicles() {
			return carriersWithAddedVehicles;
		}
	}

	private record ServiceReachability(CarrierVehicle vehicle, double roundTripDistance, double usableRange,
	                                   double longRange) {

		private boolean reachableWithCurrentRange() {
			return roundTripDistance <= usableRange;
		}

		private boolean reachableWithLongRange() {
			return roundTripDistance <= longRange;
		}

		private double longRangeUtilization() {
			return roundTripDistance / longRange;
		}
	}

	private record RangeCandidate(CarrierVehicle vehicle, double longRangeUtilization) {
	}
}

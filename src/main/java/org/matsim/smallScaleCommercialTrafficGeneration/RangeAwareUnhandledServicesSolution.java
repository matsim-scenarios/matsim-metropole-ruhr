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
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
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
	private static final String RECHARGE_SUFFIX = "Recharge";

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
			log.info(
				"Range-aware pre-check found {} services outside the current vehicle range. Added {} high-cost long-range vehicles for {} carriers. {} services had no finite range-multiplier fallback.",
				result.rangeInfeasibleServices(), result.addedVehicles(), result.carriersWithAddedVehicles(),
				result.servicesBeyondOneRechargeRange());
		}

		new DefaultUnhandledServicesSolution(generator).tryToSolveAllCarriersCompletely(scenario, nonCompleteSolvedCarriers);
		int restoredTypes = RechargeVehicleTypeUtils.restoreRechargeCostsAndCapacity(
			CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes());
		if (restoredTypes > 0) {
			log.info("Restored costs and capacities for {} range fallback vehicle types after tour planning.", restoredTypes);
		}
	}

	/**
	 * Adds at most one long-range fallback vehicle per carrier if currently unhandled services need extra range.
	 */
	Result addLongRangeVehiclesForRangeInfeasibleServices(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers) {
		CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
		Set<VehicleType> vehicleTypes = nonCompleteSolvedCarriers.stream().map(Carrier::getCarrierCapabilities).filter(Objects::nonNull).map(
			CarrierCapabilities::getVehicleTypes).flatMap(Collection::stream).collect(Collectors.toSet());
		NetworkBasedTransportCosts transportCosts = NetworkBasedTransportCosts.Builder.newInstance(scenario.getNetwork(), vehicleTypes).build();

		Result result = new Result();
		for (Carrier carrier : nonCompleteSolvedCarriers) {
			// get handled and unhandled services of the old plan
			Set<CarrierService> handledServices = carrier.getSelectedPlan() == null ? Set.of() : carrier.getSelectedPlan().getScheduledTours().stream().flatMap(
				tour -> tour.getTour().getTourElements().stream()).filter(te -> te instanceof Tour.ServiceActivity).map(
				te -> ((Tour.ServiceActivity) te).getService()).collect(Collectors.toSet());
			List<CarrierService> unhandledServices = carrier.getServices().values().stream().filter(
				thisService -> !handledServices.contains(thisService)).toList();
			if (unhandledServices.isEmpty()) continue;

			Optional<RangeCandidate> candidate = findBestRangeCandidate(carrier, unhandledServices, transportCosts, result);
			if (candidate.isEmpty()) continue;

			if (addLongRangeVehicle(carrier, carrierVehicleTypes, candidate.get())) {
				result.addedVehicles++;
				result.carriersWithAddedVehicles++;
			}
		}
		return result;
	}

	/**
	 * Finds the best existing vehicle to clone as a long-range fallback.
	 * If any current vehicle has no range constraint, the carrier is skipped because the fleet is not range-limited.
	 */
	private Optional<RangeCandidate> findBestRangeCandidate(Carrier carrier, List<CarrierService> unhandledServices,
	                                                        NetworkBasedTransportCosts transportCosts, Result result) {
		List<CarrierVehicle> currentVehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(
			vehicle -> !RechargeVehicleTypeUtils.isRechargeVehicleType(vehicle.getType().getId())).toList();
		if (currentVehicles.stream().anyMatch(vehicle -> usableRange(vehicle.getType()).isEmpty())) return Optional.empty();

		List<CarrierVehicle> vehiclesWithRange = currentVehicles.stream().filter(vehicle -> usableRange(vehicle.getType()).isPresent()).toList();
		if (vehiclesWithRange.isEmpty()) return Optional.empty();

		Optional<RangeCandidate> bestCandidate = Optional.empty();
		for (CarrierService service : unhandledServices) {
			List<ServiceReachability> reachabilityByVehicle = vehiclesWithRange.stream().map(
				vehicle -> calculateReachability(vehicle, service, transportCosts)).toList();

			boolean reachableWithCurrentRange = reachabilityByVehicle.stream().anyMatch(ServiceReachability::reachableWithCurrentRange);
			if (reachableWithCurrentRange) continue;

			result.rangeInfeasibleServices++;
			Optional<ServiceReachability> rechargeCandidate = reachabilityByVehicle.stream().filter(ServiceReachability::reachableWithLongRange).min(
				Comparator.comparingInt(ServiceReachability::requiredMultiplierApplications).thenComparingDouble(
					ServiceReachability::longRangeUtilization));
			if (rechargeCandidate.isEmpty()) {
				result.servicesBeyondOneRechargeRange++;
				double shortestRoundTrip = reachabilityByVehicle.stream().mapToDouble(ServiceReachability::roundTripDistance).min().orElse(
					Double.NaN);
				log.warn(
					"Carrier '{}' has service '{}' that is outside the current range and still has no finite range-multiplier fallback. Shortest depot-service-depot distance is {} km. Usable vehicleTypes: {}",
					carrier.getId(), service.getId(), shortestRoundTrip / 1000., vehiclesWithRange.toString());
				continue;
			}

			ServiceReachability selectedReachability = rechargeCandidate.get();
			if (bestCandidate.isEmpty() || selectedReachability.requiredMultiplierApplications() > bestCandidate.get().requiredMultiplierApplications() || (selectedReachability.requiredMultiplierApplications() == bestCandidate.get().requiredMultiplierApplications() && selectedReachability.longRangeUtilization() < bestCandidate.get().longRangeUtilization())) {
				bestCandidate = Optional.of(new RangeCandidate(selectedReachability.vehicle(), selectedReachability.requiredMultiplierApplications(),
					selectedReachability.longRangeUtilization()));
			}
		}

		return bestCandidate;
	}

	/**
	 * Calculates whether a single service can be covered as depot-service-depot tour by the current or multiplied range.
	 */
	private ServiceReachability calculateReachability(CarrierVehicle vehicle, CarrierService service, NetworkBasedTransportCosts transportCosts) {
		double departureTime = Math.max(0., vehicle.getEarliestStartTime());
		Location depot = Location.newInstance(vehicle.getLinkId().toString());
		Location serviceLocation = Location.newInstance(service.getServiceLinkId().toString());
		com.graphhopper.jsprit.core.problem.vehicle.Vehicle jspritVehicle = createJspritVehicle(vehicle);

		double outboundDistance = transportCosts.getDistance(depot, serviceLocation, departureTime, jspritVehicle);
		double returnDistance = transportCosts.getDistance(serviceLocation, depot, departureTime, jspritVehicle);
		double roundTripDistance = outboundDistance + returnDistance;
		double usableRange = usableRange(vehicle.getType()).orElseThrow();
		int requiredMultiplierApplications = Math.max(1, (int) Math.ceil(roundTripDistance / (usableRange * rangeMultiplier)));
		double longRange = usableRange * rangeMultiplier * requiredMultiplierApplications;

		return new ServiceReachability(vehicle, roundTripDistance, usableRange, requiredMultiplierApplications, longRange);
	}

	/**
	 * Creates the small jsprit vehicle object needed by MATSim's network-based cost calculator.
	 */
	private static com.graphhopper.jsprit.core.problem.vehicle.Vehicle createJspritVehicle(CarrierVehicle vehicle) {
		VehicleTypeImpl jspritType = VehicleTypeImpl.Builder.newInstance(vehicle.getType().getId().toString()).setMaxVelocity(
			vehicle.getType().getMaximumVelocity()).build();
		return VehicleImpl.Builder.newInstance(vehicle.getId().toString()).setStartLocation(
			Location.newInstance(vehicle.getLinkId().toString())).setType(jspritType).build();
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
	 * Adds the cloned vehicle type to the carrier capabilities and creates a concrete vehicle at the reference depot.
	 */
	private boolean addLongRangeVehicle(Carrier carrier, CarrierVehicleTypes carrierVehicleTypes, RangeCandidate candidate) {
		CarrierVehicle referenceVehicle = candidate.vehicle();
		VehicleType longRangeVehicleType = getOrCreateLongRangeVehicleType(carrierVehicleTypes, referenceVehicle.getType(),
			candidate.requiredMultiplierApplications());
		addVehicleTypeToCarrierCapabilities(carrier.getCarrierCapabilities(), longRangeVehicleType);

		Id<Vehicle> vehicleId = nextLongRangeVehicleId(carrier, referenceVehicle.getId(), candidate.requiredMultiplierApplications());
		if (carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicleId)) {
			return false;
		}

		CarrierVehicle longRangeVehicle = CarrierVehicle.Builder.newInstance(vehicleId, referenceVehicle.getLinkId(),
			longRangeVehicleType).setEarliestStart(referenceVehicle.getEarliestStartTime()).setLatestEnd(referenceVehicle.getLatestEndTime()).build();
		referenceVehicle.getAttributes().getAsMap().forEach((key, value) -> longRangeVehicle.getAttributes().putAttribute(key, value));
		carrier.getCarrierCapabilities().getCarrierVehicles().put(longRangeVehicle.getId(), longRangeVehicle);
		log.info("Added range fallback vehicle '{}' of type '{}' to carrier '{}' based on '{}' using {} range multiplier applications.",
			longRangeVehicle.getId(), longRangeVehicleType.getId(), carrier.getId(), referenceVehicle.getId(),
			candidate.requiredMultiplierApplications());
		return true;
	}

	/**
	 * Clones the reference type, increases stored energy, and raises fixed costs to make higher range fallbacks unattractive.
	 */
	private VehicleType getOrCreateLongRangeVehicleType(CarrierVehicleTypes carrierVehicleTypes, VehicleType referenceType,
	                                                    int requiredMultiplierApplications) {
		Id<VehicleType> longRangeTypeId = Id.create(referenceType.getId() + "_" + requiredMultiplierApplications + RECHARGE_SUFFIX,
			VehicleType.class);
		VehicleType existingType = carrierVehicleTypes.getVehicleTypes().get(longRangeTypeId);
		if (existingType != null) {
			applyLongRangeAdjustments(existingType, referenceType, rangeMultiplier * requiredMultiplierApplications,
				fixedCostMultiplier * requiredMultiplierApplications);
			return existingType;
		}

		VehicleType longRangeType = createLongRangeVehicleType(longRangeTypeId, referenceType,
			rangeMultiplier * requiredMultiplierApplications, fixedCostMultiplier * requiredMultiplierApplications,
			" (range fallback, applies range multiplier " + requiredMultiplierApplications + "x)");
		carrierVehicleTypes.getVehicleTypes().put(longRangeType.getId(), longRangeType);
		return longRangeType;
	}

	static VehicleType createLongRangeVehicleType(Id<VehicleType> longRangeTypeId, VehicleType referenceType, double rangeMultiplier,
	                                              double fixedCostMultiplier, String descriptionSuffix) {
		VehicleType longRangeType = VehicleUtils.createVehicleType(longRangeTypeId);
		VehicleUtils.copyFromTo(referenceType, longRangeType);
		applyLongRangeAdjustments(longRangeType, referenceType, rangeMultiplier, fixedCostMultiplier);
		longRangeType.setDescription(
			(referenceType.getDescription() == null ? referenceType.getId().toString() : referenceType.getDescription()) + descriptionSuffix);
		return longRangeType;
	}

	static void applyLongRangeAdjustments(VehicleType longRangeType, VehicleType referenceType, double rangeMultiplier, double fixedCostMultiplier) {
		Double energyCapacity = VehicleUtils.getEnergyCapacity(referenceType.getEngineInformation());
		if (energyCapacity != null) {
			VehicleUtils.setEnergyCapacity(longRangeType.getEngineInformation(), energyCapacity * rangeMultiplier);
		}
		longRangeType.getCostInformation().setFixedCost(referenceType.getCostInformation().getFixedCosts() * fixedCostMultiplier);
	}

	/**
	 * Makes the cloned type available for this carrier without duplicating an already registered type.
	 */
	private static void addVehicleTypeToCarrierCapabilities(CarrierCapabilities carrierCapabilities, VehicleType vehicleType) {
		Set<Id<VehicleType>> existingTypeIds = carrierCapabilities.getVehicleTypes().stream().map(VehicleType::getId).collect(
			Collectors.toCollection(HashSet::new));
		if (!existingTypeIds.contains(vehicleType.getId())) {
			carrierCapabilities.getVehicleTypes().add(vehicleType);
		}
	}

	/**
	 * Creates a deterministic fallback id and appends a numeric suffix if the carrier already has one.
	 */
	private static Id<Vehicle> nextLongRangeVehicleId(Carrier carrier, Id<Vehicle> referenceVehicleId, int requiredMultiplierApplications) {
		String baseId = referenceVehicleId + "_" + requiredMultiplierApplications + RECHARGE_SUFFIX;
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

	private record ServiceReachability(CarrierVehicle vehicle, double roundTripDistance, double usableRange, int requiredMultiplierApplications,
	                                   double longRange) {

		private boolean reachableWithCurrentRange() {
			return roundTripDistance <= usableRange;
		}

		private boolean reachableWithLongRange() {
			return requiredMultiplierApplications != Integer.MAX_VALUE && roundTripDistance <= longRange;
		}

		private double longRangeUtilization() {
			return roundTripDistance / longRange;
		}
	}

	private record RangeCandidate(CarrierVehicle vehicle, int requiredMultiplierApplications, double longRangeUtilization) {
	}
}

package org.matsim.smallScaleCommercialTrafficGeneration;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.vehicles.EngineInformation;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds a high-cost long-range fallback vehicle for services that cannot be reached safely by the current range-constrained fleet.
 * The regular {@link DefaultUnhandledServicesSolution} is still used afterwards for the existing time-budget fixes.
 */
public final class RangeAwareUnhandledServicesSolution implements UnhandledServicesSolution {

	private static final Logger log = LogManager.getLogger(RangeAwareUnhandledServicesSolution.class);
	/**
	 * Treats an otherwise feasible free vehicle as borderline when the single-service tour already uses at least this
	 * share of the conservative current range. Borderline services trigger the next Recharge level as a safety margin
	 * because jsprit's final routed tour may be slightly longer than the depot-service-depot pre-check.
	 */
	private static final double NEAR_CURRENT_RANGE_LIMIT_UTILIZATION = 0.90;
	private static final double MAX_FALLBACK_VEHICLE_AVAILABILITY = 18. * 3600.;
	private static final double FALLBACK_VEHICLE_SLACK = 30. * 60.;
	/**
	 * Additional RangeAware-only buffer on top of the configured jsprit usable range. Jsprit still receives the original
	 * usable range; RangeAware uses only this share in its pre-check to avoid accepting services that sit exactly at the
	 * distance constraint boundary.
	 */
	private static final double USABLE_RANGE_PRECHECK_SAFETY_FACTOR = 0.85;

	private final double distanceConstraintUsableRange;
	private final double rangeMultiplier;
	private final double fixedCostMultiplier;
	private GenerateSmallScaleCommercialTrafficDemand generator;

	/**
	 * Creates the range-aware repair step.
	 *
	 * @param distanceConstraintUsableRange usable range percentage used by MATSim's distance constraint
	 * @param rangeMultiplier multiplier applied once for _1Recharge, twice for _2Recharge, and so on
	 * @param fixedCostMultiplier fixed-cost penalty per Recharge level so jsprit uses fallbacks only when needed
	 */
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

	@Override
	public void tryToSolveAllCarriersCompletely(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers) {
		if (generator == null) {
			throw new IllegalStateException("Generator must be set before using RangeAwareUnhandledServicesSolution.");
		}
		Result result = addLongRangeVehiclesForRangeInfeasibleServices(scenario, nonCompleteSolvedCarriers);
		logRangeCheckResult("pre-check", result);

		new DefaultUnhandledServicesSolution(generator, (currentScenario, currentNonCompleteSolvedCarriers, _) -> {
			// NetworkChangeEvents plus the fixed 30-minute fallback slack already cover route-time uncertainty.
			Result iterationResult = addLongRangeVehiclesForRangeInfeasibleServices(currentScenario, currentNonCompleteSolvedCarriers);
			logRangeCheckResult("loop check", iterationResult);
			return iterationResult.addedVehicles();
		}, vehicleType -> !RechargeVehicleTypeUtils.isRechargeVehicleType(vehicleType.getId()))
			.tryToSolveAllCarriersCompletely(scenario, nonCompleteSolvedCarriers);
		int restoredTypes = RechargeVehicleTypeUtils.restoreRechargeCostsAndCapacity(
			CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes());
		if (restoredTypes > 0) {
			log.info("Restored costs and capacities for {} range fallback vehicle types after tour planning.", restoredTypes);
		}
	}

	/**
	 * Writes one compact summary per range-aware phase, but only when the phase inspected services and made at least
	 * one relevant decision. This keeps successful no-op iterations out of the logs.
	 */
	private static void logRangeCheckResult(String phase, Result result) {
		if (result.checkedUnhandledServices() > 0 && result.hasDecisionToReport()) {
			log.info(
				"Range-aware {} checked {} unhandled services with {}% of jsprit's usable range as conservative pre-check range. {} services were covered by a currently free range/time-feasible vehicle ({} near the current range limit >= {}%). {} services needed Recharge-range evaluation. Added {} high-cost Recharge vehicles for {} carriers. {} services had no feasible Recharge fallback. Skipped {} carriers with at least one free vehicle without a range constraint and {} carriers without a base Recharge template.",
				phase, result.checkedUnhandledServices(), USABLE_RANGE_PRECHECK_SAFETY_FACTOR * 100.,
				result.servicesCoveredByFreeVehicle(), result.servicesCoveredByFreeVehicleNearRangeLimit(),
				NEAR_CURRENT_RANGE_LIMIT_UTILIZATION * 100.,
				result.rangeInfeasibleServices(), result.addedVehicles(), result.carriersWithAddedVehicles(),
				result.servicesWithoutFeasibleRechargeFallback(), result.carriersSkippedBecauseFreeVehicleWithoutRange(),
				result.carriersWithoutBaseRechargeTemplate());
		}
	}

	Result addLongRangeVehiclesForRangeInfeasibleServices(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers) {
		return addLongRangeVehiclesForRangeInfeasibleServices(scenario, nonCompleteSolvedCarriers, 1.);
	}

	/**
	 * Adds Recharge fallback vehicles for currently unhandled services that need extra range.
	 * The effective travel-buffer factor comes from the default unhandled-service loop so the range repair uses the
	 * same buffered single-service time-window check as the normal vehicle fallback logic.
	 */
	Result addLongRangeVehiclesForRangeInfeasibleServices(Scenario scenario, List<Carrier> nonCompleteSolvedCarriers,
	                                                      double effectiveTravelBufferFactor) {
		CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getOrAddCarrierVehicleTypes(scenario);
		Set<VehicleType> vehicleTypes = nonCompleteSolvedCarriers.stream().map(Carrier::getCarrierCapabilities).filter(Objects::nonNull).map(
			CarrierCapabilities::getVehicleTypes).flatMap(Collection::stream).collect(Collectors.toSet());
		NetworkBasedTransportCosts transportCosts = createNetworkBasedTransportCosts(scenario, vehicleTypes);
		double safeEffectiveTravelBufferFactor = Math.max(1., effectiveTravelBufferFactor);

		Result result = new Result();
		for (Carrier carrier : nonCompleteSolvedCarriers) {
			// get handled and unhandled services of the old plan
			Set<CarrierService> handledServices = carrier.getSelectedPlan() == null ? Set.of() : carrier.getSelectedPlan().getScheduledTours().stream().flatMap(
				tour -> tour.getTour().getTourElements().stream()).filter(te -> te instanceof Tour.ServiceActivity).map(
				te -> ((Tour.ServiceActivity) te).getService()).collect(Collectors.toSet());
			Set<Id<Vehicle>> usedVehicleIds = carrier.getSelectedPlan() == null ? Set.of() : carrier.getSelectedPlan().getScheduledTours().stream()
				.map(tour -> tour.getVehicle().getId()).collect(Collectors.toSet());
			List<CarrierService> unhandledServices = carrier.getServices().values().stream().filter(
				thisService -> !handledServices.contains(thisService)).toList();
			if (unhandledServices.isEmpty()) continue;
			result.checkedUnhandledServices += unhandledServices.size();

			List<RangeCandidate> candidates = findRangeCandidates(carrier, unhandledServices, usedVehicleIds, transportCosts,
				safeEffectiveTravelBufferFactor, result);
			if (candidates.isEmpty()) continue;

			int addedVehiclesForCarrier = 0;
			for (RangeCandidate candidate : candidates) {
				if (addLongRangeVehicle(carrier, carrierVehicleTypes, candidate)) {
					result.addedVehicles++;
					addedVehiclesForCarrier++;
				}
			}
			if (addedVehiclesForCarrier > 0) {
				result.carriersWithAddedVehicles++;
			}
		}
		return result;
	}

	/**
	 * Finds the best vehicle to clone as a Recharge fallback for each range-infeasible service.
	 * A vehicle that is already used in the selected plan is not considered available for the unhandled service.
	 * Existing free Recharge vehicles count as available range before another Recharge clone is created.
	 * If any free vehicle has no range constraint, the carrier is skipped because the free fleet is not range-limited.
	 */
	private List<RangeCandidate> findRangeCandidates(Carrier carrier, List<CarrierService> unhandledServices,
	                                                 Set<Id<Vehicle>> usedVehicleIds, NetworkBasedTransportCosts transportCosts,
	                                                 double effectiveTravelBufferFactor, Result result) {
		// Only vehicles that are not assigned to the selected plan can directly serve the remaining unhandled services.
		// Used vehicles may still have a suitable depot or type, but treating them as available would hide cases where
		// a second Recharge vehicle is actually needed.
		List<CarrierVehicle> freeVehicles = carrier.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(
			vehicle -> !usedVehicleIds.contains(vehicle.getId())).toList();
		if (freeVehicles.stream().anyMatch(vehicle -> usableRange(vehicle.getType()).isEmpty())) {
			result.carriersSkippedBecauseFreeVehicleWithoutRange++;
			log.info(
				"Carrier '{}': Range-aware check skipped {} unhandled services because at least one currently free vehicle has no range constraint. Free vehicles: {}",
				carrier.getId(), unhandledServices.size(), freeVehicles);
			return List.of();
		}

		List<CarrierVehicle> freeVehiclesWithRange = freeVehicles.stream().filter(vehicle -> usableRange(vehicle.getType()).isPresent()).toList();
		Set<Id<Vehicle>> availableFreeVehicleIds = freeVehiclesWithRange.stream().map(CarrierVehicle::getId).collect(
			Collectors.toCollection(HashSet::new));

		// Free Recharge vehicles are checked as already available capacity, but new Recharge vehicle types are cloned
		// from an existing base vehicle whose time window can serve the service. Used base vehicles are allowed only as
		// templates for capacity/range, never as currently available vehicles.
		List<CarrierVehicle> baseVehicleTemplatesWithRange = carrier.getCarrierCapabilities().getCarrierVehicles().values().stream().filter(
			vehicle -> usableRange(vehicle.getType()).isPresent()).filter(
			vehicle -> !RechargeVehicleTypeUtils.isRechargeVehicleType(vehicle.getType().getId())).toList();
		if (baseVehicleTemplatesWithRange.isEmpty()) {
			result.carriersWithoutBaseRechargeTemplate++;
			log.warn(
				"Carrier '{}': Range-aware check found {} unhandled services but no non-Recharge base vehicle template with a finite range. Free vehicles: {}",
				carrier.getId(), unhandledServices.size(), freeVehicles);
			return List.of();
		}

		List<RangeCandidate> candidates = new ArrayList<>();
		for (CarrierService service : unhandledServices) {
			List<ServiceReachability> reachabilityByFreeVehicle = freeVehiclesWithRange.stream().map(
				vehicle -> calculateReachability(vehicle, service, transportCosts, effectiveTravelBufferFactor)).toList();

			// Reserve a currently free vehicle for at most one remaining service in this conservative one-service check.
			// That prevents a single free Recharge vehicle from masking the need for a second one when multiple unhandled
			// services each require long range. The actual multi-stop assignment is still left to jsprit afterwards.
			Optional<ServiceReachability> currentRangeVehicle = reachabilityByFreeVehicle.stream()
				.filter(ServiceReachability::reachableWithCurrentRangeAndTime)
				.filter(reachability -> availableFreeVehicleIds.contains(reachability.vehicle().getId()))
				.min(Comparator.comparingDouble(ServiceReachability::currentRangeUtilization));
			boolean evaluateRechargeFallbackForSafetyMargin = false;
			int minimumRechargeMultiplierApplications = 1;
			if (currentRangeVehicle.isPresent()) {
				ServiceReachability selectedFreeVehicle = currentRangeVehicle.get();
				availableFreeVehicleIds.remove(selectedFreeVehicle.vehicle().getId());
				result.servicesCoveredByFreeVehicle++;
				boolean nearCurrentRangeLimit = isNearCurrentRangeLimit(selectedFreeVehicle);
				if (nearCurrentRangeLimit) {
					result.servicesCoveredByFreeVehicleNearRangeLimit++;
					minimumRechargeMultiplierApplications = minimumRechargeMultiplierApplicationsForRangeMargin(selectedFreeVehicle);
					log.info(
						"Carrier '{}': Range-aware check evaluates a Recharge fallback for service '{}' because free vehicle '{}' is range/time-feasible but close to the current range limit. Required minimum Recharge applications: {}. {}",
						carrier.getId(), service.getId(), selectedFreeVehicle.vehicle().getId(),
						minimumRechargeMultiplierApplications, describeReachability(selectedFreeVehicle));
					evaluateRechargeFallbackForSafetyMargin = true;
				} else {
					log.debug(
						"Carrier '{}': Range-aware check keeps service '{}' without Recharge because free vehicle '{}' is range/time-feasible: {}",
						carrier.getId(), service.getId(), selectedFreeVehicle.vehicle().getId(), describeReachability(selectedFreeVehicle));
					continue;
				}
			}

			// Near-limit services can still be rejected by jsprit's distance constraint if the final routed tour is only
			// slightly longer than this single-service pre-check. In that case we allow a Recharge clone as a safety
			// margin, but only after the service stayed unhandled and a concrete free vehicle was almost sufficient.
			result.rangeInfeasibleServices++;
			List<ServiceReachability> reachabilityByBaseTemplate = baseVehicleTemplatesWithRange.stream().map(
				vehicle -> calculateReachability(vehicle, service, transportCosts, effectiveTravelBufferFactor)).toList();
			boolean allowRechargeFallbackForSafetyMargin = evaluateRechargeFallbackForSafetyMargin;
			int minimumRequiredMultiplierApplications = minimumRechargeMultiplierApplications;

			// The selected reachability may require more than one range-multiplier application. This intentionally
			// creates _2Recharge, _3Recharge, ... types when _1Recharge would still be too short. If an existing
			// free Recharge vehicle is barely range-feasible, the next Recharge level is used as safety buffer because
			// jsprit's final routed tour can be slightly longer than this single-service depot-service-depot estimate.
			Optional<ServiceReachability> rechargeCandidate = reachabilityByBaseTemplate.stream()
				// If a cloned base vehicle can already serve the service without extra range, this is a vehicle-count
				// or depot-selection problem for the default loop rather than a Recharge problem. The exception is the
				// near-limit case above, where Recharge provides a deliberate range buffer for jsprit's final routing.
				.filter(reachability -> !reachability.reachableWithCurrentRangeAndTime()
					|| (allowRechargeFallbackForSafetyMargin && isNearCurrentRangeLimit(reachability)))
				.map(reachability -> withAtLeastMultiplierApplications(reachability, minimumRequiredMultiplierApplications))
				.filter(ServiceReachability::reachableWithLongRangeAndTime)
				.min(rechargeCandidateComparator());
			if (rechargeCandidate.isEmpty()) {
				result.servicesWithoutFeasibleRechargeFallback++;
				double shortestRoundTrip = reachabilityByBaseTemplate.stream().mapToDouble(ServiceReachability::roundTripDistance).min().orElse(
					Double.NaN);
				double shortestRoundTripDuration = reachabilityByBaseTemplate.stream().mapToDouble(ServiceReachability::roundTripDuration).min().orElse(
					Double.NaN);
				double longestTemplateAvailability = baseVehicleTemplatesWithRange.stream().mapToDouble(
					vehicle -> vehicle.getLatestEndTime() - vehicle.getEarliestStartTime()).max().orElse(Double.NaN);
				log.warn(
					"Carrier '{}' has service '{}' that is outside the current free range/time-feasible fleet and still has no feasible Recharge fallback. Shortest template depot-service-depot distance is {} km, shortest template round-trip duration is {} min, longest template vehicle availability is {} min. Best free vehicle check: {}. Best base template check: {}. Free vehicleTypes: {}, base templates: {}",
					carrier.getId(), service.getId(), shortestRoundTrip / 1000., shortestRoundTripDuration / 60.,
					longestTemplateAvailability / 60., describeBestReachability(reachabilityByFreeVehicle),
					describeBestReachability(reachabilityByBaseTemplate), freeVehiclesWithRange.toString(), baseVehicleTemplatesWithRange.toString());
				continue;
			}

			ServiceReachability selectedReachability = rechargeCandidate.get();
			log.info(
				"Carrier '{}': Range-aware check selected Recharge fallback for service '{}': {}",
				carrier.getId(), service.getId(), describeReachability(selectedReachability));
			candidates.add(new RangeCandidate(service, selectedReachability));
		}

		return candidates;
	}

	/**
	 * Calculates whether a single service can be covered as depot-service-depot tour by the current or multiplied range.
	 * Both checks include the complete route duration multiplied by the effective travel-buffer factor from the default
	 * loop. RangeAware still only creates Recharge vehicles; if no base template fits the buffered time window, the
	 * normal DefaultUnhandledServicesSolution vehicle fallback has to create a better-timed base vehicle first.
	 */
	private ServiceReachability calculateReachability(CarrierVehicle vehicle, CarrierService service,
	                                                  NetworkBasedTransportCosts transportCosts, double effectiveTravelBufferFactor) {
		double departureTime = Math.max(0., vehicle.getEarliestStartTime());
		double safeEffectiveTravelBufferFactor = Math.max(1., effectiveTravelBufferFactor);
		Location depot = Location.newInstance(vehicle.getLinkId().toString());
		Location serviceLocation = Location.newInstance(service.getServiceLinkId().toString());
		com.graphhopper.jsprit.core.problem.vehicle.Vehicle jspritVehicle = createJspritVehicle(vehicle);

		double outboundDistance = transportCosts.getDistance(depot, serviceLocation, departureTime, jspritVehicle);
		double outboundTravelTime = transportCosts.getTransportTime(depot, serviceLocation, departureTime, null, jspritVehicle);
		double serviceStartTime = Math.max(departureTime + outboundTravelTime, service.getServiceStaringTimeWindow().getStart());
		double serviceEndTime = serviceStartTime + service.getServiceDuration();
		double returnDistance = transportCosts.getDistance(serviceLocation, depot, serviceEndTime, jspritVehicle);
		double returnTravelTime = transportCosts.getTransportTime(serviceLocation, depot, serviceEndTime, null, jspritVehicle);
		double roundTripDistance = outboundDistance + returnDistance;
		double roundTripDuration = serviceEndTime + returnTravelTime - departureTime;
		boolean serviceStartWithinTimeWindow = serviceStartTime <= service.getServiceStaringTimeWindow().getEnd();
		double requiredLatestEndTime = departureTime + roundTripDuration * safeEffectiveTravelBufferFactor;
		boolean roundTripWithinVehicleAvailability = requiredLatestEndTime <= vehicle.getLatestEndTime();
		double usableRange = usableRange(vehicle.getType()).orElseThrow();
		int requiredMultiplierApplications = Math.max(1, (int) Math.ceil(roundTripDistance / (usableRange * rangeMultiplier)));
		double longRange = usableRange * rangeMultiplier * requiredMultiplierApplications;

		return new ServiceReachability(vehicle, roundTripDistance, roundTripDuration, usableRange, requiredMultiplierApplications, longRange,
			serviceStartTime, service.getServiceStaringTimeWindow().getEnd(), requiredLatestEndTime, safeEffectiveTravelBufferFactor,
			serviceStartWithinTimeWindow, roundTripWithinVehicleAvailability);
	}

	private static int minimumRechargeMultiplierApplicationsForRangeMargin(ServiceReachability selectedFreeVehicle) {
		return Math.max(1, RechargeVehicleTypeUtils.getRechargeMultiplierApplications(selectedFreeVehicle.vehicle().getType().getId()) + 1);
	}

	private ServiceReachability withAtLeastMultiplierApplications(ServiceReachability reachability, int minimumRequiredMultiplierApplications) {
		if (reachability.requiredMultiplierApplications() >= minimumRequiredMultiplierApplications) {
			return reachability;
		}
		double adjustedLongRange = reachability.usableRange() * rangeMultiplier * minimumRequiredMultiplierApplications;
		return new ServiceReachability(reachability.vehicle(), reachability.roundTripDistance(), reachability.roundTripDuration(),
			reachability.usableRange(), minimumRequiredMultiplierApplications, adjustedLongRange, reachability.serviceStartTime(),
			reachability.serviceTimeWindowEnd(), reachability.requiredLatestEndTime(), reachability.effectiveTravelBufferFactor(),
			reachability.serviceStartWithinTimeWindow(), reachability.roundTripWithinVehicleAvailability());
	}

	/**
	 * Prefers the cheapest sufficient Recharge level, then the candidate with the lowest long-range utilization, and
	 * finally the candidate with the most remaining vehicle availability.
	 */
	private static Comparator<ServiceReachability> rechargeCandidateComparator() {
		return Comparator.comparingInt(ServiceReachability::requiredMultiplierApplications)
			.thenComparingDouble(ServiceReachability::longRangeUtilization)
			.thenComparing((left, right) -> Double.compare(right.vehicleEndSlack(), left.vehicleEndSlack()));
	}

	private static String describeBestReachability(List<ServiceReachability> reachabilities) {
		return reachabilities.stream()
			.min(Comparator.comparingInt(ServiceReachability::requiredMultiplierApplications)
				.thenComparingDouble(ServiceReachability::currentRangeUtilization))
			.map(RangeAwareUnhandledServicesSolution::describeReachability)
			.orElse("n/a");
	}

	private static String describeReachability(ServiceReachability reachability) {
		return String.format(Locale.ROOT,
			"vehicle=%s, type=%s, roundTrip=%s km, currentRange=%s km, currentRangeSlack=%s km, currentRangeUtilization=%s%%, RechargeRange=%s km, RechargeSlack=%s km, requiredMultiplierApplications=%d, roundTripDuration=%s min, timeBufferFactor=%.3f, serviceTimeWindowSlack=%s, bufferedVehicleEndSlack=%s min, serviceStartWithinTimeWindow=%s, vehicleAvailabilityOk=%s",
			reachability.vehicle().getId(), reachability.vehicle().getType().getId(),
			formatKilometers(reachability.roundTripDistance()), formatKilometers(reachability.usableRange()),
			formatKilometers(reachability.currentRangeSlack()), formatPercent(reachability.currentRangeUtilization()),
			formatKilometers(reachability.longRange()), formatKilometers(reachability.longRangeSlack()),
			reachability.requiredMultiplierApplications(), formatMinutes(reachability.roundTripDuration()),
			reachability.effectiveTravelBufferFactor(), formatDurationOrUnbounded(reachability.serviceTimeWindowSlack()),
			formatMinutes(reachability.vehicleEndSlack()), reachability.serviceStartWithinTimeWindow(),
			reachability.roundTripWithinVehicleAvailability());
	}

	private static String formatKilometers(double meters) {
		return String.format(Locale.ROOT, "%.3f", meters / 1000.);
	}

	private static String formatPercent(double share) {
		return String.format(Locale.ROOT, "%.1f", share * 100.);
	}

	private static boolean isNearCurrentRangeLimit(ServiceReachability reachability) {
		return reachability.currentRangeUtilization() >= NEAR_CURRENT_RANGE_LIMIT_UTILIZATION;
	}

	private static String formatMinutes(double seconds) {
		return String.format(Locale.ROOT, "%.3f", seconds / 60.);
	}

	private static String formatDurationOrUnbounded(double seconds) {
		if (!Double.isFinite(seconds) || seconds > 365. * 24. * 3600.) {
			return "unbounded";
		}
		return formatMinutes(seconds) + " min";
	}

	private static com.graphhopper.jsprit.core.problem.vehicle.Vehicle createJspritVehicle(CarrierVehicle vehicle) {
		VehicleTypeImpl jspritType = VehicleTypeImpl.Builder.newInstance(vehicle.getType().getId().toString()).setMaxVelocity(
			vehicle.getType().getMaximumVelocity()).build();
		return VehicleImpl.Builder.newInstance(vehicle.getId().toString()).setStartLocation(
			Location.newInstance(vehicle.getLinkId().toString())).setType(jspritType).build();
	}

	/**
	 * Builds the same time-dependent transport-cost view used by the jsprit solver.
	 */
	private static NetworkBasedTransportCosts createNetworkBasedTransportCosts(Scenario scenario, Collection<VehicleType> vehicleTypes) {
		NetworkBasedTransportCosts.Builder builder = NetworkBasedTransportCosts.Builder.newInstance(scenario.getNetwork(), vehicleTypes);
		if (scenario.getNetwork() instanceof TimeDependentNetwork timeDependentNetwork
			&& !timeDependentNetwork.getNetworkChangeEvents().isEmpty()) {
			FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCarriersConfigGroup.class);
			builder.setTimeSliceWidth(freightConfig.getTravelTimeSliceWidth());
		}
		return builder.build();
	}

	/**
	 * Reconstructs MATSim's distance-constraint range from capacity, consumption, and configured usable-range margin,
	 * then applies an additional RangeAware-only buffer. Jsprit still uses the configured usable range itself; the
	 * conservative pre-check leaves distance slack for time-dependent routing and avoids classifying borderline jobs as
	 * already covered by the existing fleet.
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
		return Optional.of(energyCapacity / consumptionPerMeter * distanceConstraintUsableRange / 100.
			* USABLE_RANGE_PRECHECK_SAFETY_FACTOR);
	}

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
		CarrierVehicle referenceVehicle = candidate.reachability().vehicle();
		VehicleType longRangeVehicleType = getOrCreateLongRangeVehicleType(carrierVehicleTypes, referenceVehicle.getType(),
			candidate.reachability().requiredMultiplierApplications());
		addVehicleTypeToCarrierCapabilities(carrier.getCarrierCapabilities(), longRangeVehicleType);

		Id<Vehicle> vehicleId = nextLongRangeVehicleId(carrier, referenceVehicle.getId(),
			candidate.reachability().requiredMultiplierApplications());
		if (carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicleId)) {
			return false;
		}

		double earliestStartTime = referenceVehicle.getEarliestStartTime();
		double latestEndTime = Math.min(referenceVehicle.getLatestEndTime(),
			Math.min(candidate.reachability().requiredLatestEndTime() + FALLBACK_VEHICLE_SLACK,
				earliestStartTime + MAX_FALLBACK_VEHICLE_AVAILABILITY));
		if (candidate.reachability().requiredLatestEndTime() > latestEndTime) {
			log.warn(
				"Carrier '{}': Skipping range fallback vehicle for service '{}' because the required buffered latestEnd {} would exceed the capped fallback window {}-{}.",
				carrier.getId(), candidate.service().getId(), candidate.reachability().requiredLatestEndTime(),
				earliestStartTime, latestEndTime);
			return false;
		}

		CarrierVehicle longRangeVehicle = CarrierVehicle.Builder.newInstance(vehicleId, referenceVehicle.getLinkId(),
			longRangeVehicleType).setEarliestStart(earliestStartTime).setLatestEnd(latestEndTime).build();
		referenceVehicle.getAttributes().getAsMap().forEach((key, value) -> longRangeVehicle.getAttributes().putAttribute(key, value));
		carrier.getCarrierCapabilities().getCarrierVehicles().put(longRangeVehicle.getId(), longRangeVehicle);
		log.info("Added range fallback vehicle '{}' of type '{}' to carrier '{}' for service '{}' based on '{}'. Window {}-{} (availability {} minutes, max {} hours). Details: {}.",
			longRangeVehicle.getId(), longRangeVehicleType.getId(), carrier.getId(), candidate.service().getId(),
			referenceVehicle.getId(), earliestStartTime, latestEndTime, (latestEndTime - earliestStartTime) / 60.,
			MAX_FALLBACK_VEHICLE_AVAILABILITY / 3600., describeReachability(candidate.reachability()));
		return true;
	}

	/**
	 * Returns an existing Recharge vehicle type or creates it by cloning the reference type. The Recharge level controls
	 * both the stored-energy multiplier and the fixed-cost penalty.
	 */
	private VehicleType getOrCreateLongRangeVehicleType(CarrierVehicleTypes carrierVehicleTypes, VehicleType referenceType,
	                                                    int requiredMultiplierApplications) {
		Id<VehicleType> longRangeTypeId = RechargeVehicleTypeUtils.createRechargeVehicleTypeId(referenceType.getId(),
			requiredMultiplierApplications);
		VehicleType existingType = carrierVehicleTypes.getVehicleTypes().get(longRangeTypeId);
		if (existingType != null) {
			RechargeVehicleTypeUtils.applyRechargeAdjustments(existingType, referenceType, rangeMultiplier * requiredMultiplierApplications,
				fixedCostMultiplier * requiredMultiplierApplications);
			return existingType;
		}

		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(longRangeTypeId, referenceType,
			rangeMultiplier * requiredMultiplierApplications, fixedCostMultiplier * requiredMultiplierApplications,
			" (range fallback, applies range multiplier " + requiredMultiplierApplications + "x)");
		carrierVehicleTypes.getVehicleTypes().put(longRangeType.getId(), longRangeType);
		return longRangeType;
	}

	private static void addVehicleTypeToCarrierCapabilities(CarrierCapabilities carrierCapabilities, VehicleType vehicleType) {
		Set<Id<VehicleType>> existingTypeIds = carrierCapabilities.getVehicleTypes().stream().map(VehicleType::getId).collect(
			Collectors.toCollection(HashSet::new));
		if (!existingTypeIds.contains(vehicleType.getId())) {
			carrierCapabilities.getVehicleTypes().add(vehicleType);
		}
	}

	private static Id<Vehicle> nextLongRangeVehicleId(Carrier carrier, Id<Vehicle> referenceVehicleId, int requiredMultiplierApplications) {
		String baseId = referenceVehicleId + RechargeVehicleTypeUtils.rechargeIdSuffix(requiredMultiplierApplications);
		Id<Vehicle> vehicleId = Id.create(baseId, Vehicle.class);
		int index = 1;
		while (carrier.getCarrierCapabilities().getCarrierVehicles().containsKey(vehicleId)) {
			vehicleId = Id.create(baseId + "_" + index, Vehicle.class);
			index++;
		}
		return vehicleId;
	}

	/**
	 * Collects counters for a single range-aware pass. These counters are intentionally solver-facing diagnostics:
	 * they separate "covered by existing free fleet", "needs Recharge", and "still impossible" cases.
	 */
	static final class Result {
		private int checkedUnhandledServices;
		private int servicesCoveredByFreeVehicle;
		private int servicesCoveredByFreeVehicleNearRangeLimit;
		private int rangeInfeasibleServices;
		private int servicesWithoutFeasibleRechargeFallback;
		private int carriersSkippedBecauseFreeVehicleWithoutRange;
		private int carriersWithoutBaseRechargeTemplate;
		private int addedVehicles;
		private int carriersWithAddedVehicles;

		int checkedUnhandledServices() {
			return checkedUnhandledServices;
		}

		int servicesCoveredByFreeVehicle() {
			return servicesCoveredByFreeVehicle;
		}

		int servicesCoveredByFreeVehicleNearRangeLimit() {
			return servicesCoveredByFreeVehicleNearRangeLimit;
		}

		int rangeInfeasibleServices() {
			return rangeInfeasibleServices;
		}

		int servicesWithoutFeasibleRechargeFallback() {
			return servicesWithoutFeasibleRechargeFallback;
		}

		int addedVehicles() {
			return addedVehicles;
		}

		int carriersWithAddedVehicles() {
			return carriersWithAddedVehicles;
		}

		int carriersSkippedBecauseFreeVehicleWithoutRange() {
			return carriersSkippedBecauseFreeVehicleWithoutRange;
		}

		int carriersWithoutBaseRechargeTemplate() {
			return carriersWithoutBaseRechargeTemplate;
		}

		boolean hasDecisionToReport() {
			return servicesCoveredByFreeVehicle > 0
				|| rangeInfeasibleServices > 0
				|| servicesWithoutFeasibleRechargeFallback > 0
				|| addedVehicles > 0
				|| carriersSkippedBecauseFreeVehicleWithoutRange > 0
				|| carriersWithoutBaseRechargeTemplate > 0;
		}
	}

	/**
	 * Immutable reachability snapshot for one vehicle-service pair. It keeps the raw range/time calculations together
	 * with the derived slack/utilization helpers used for candidate selection and logging.
	 */
	private record ServiceReachability(CarrierVehicle vehicle, double roundTripDistance, double roundTripDuration, double usableRange,
	                                   int requiredMultiplierApplications, double longRange, double serviceStartTime, double serviceTimeWindowEnd,
	                                   double requiredLatestEndTime, double effectiveTravelBufferFactor,
	                                   boolean serviceStartWithinTimeWindow, boolean roundTripWithinVehicleAvailability) {

		private boolean reachableWithCurrentRangeAndTime() {
			return roundTripDistance <= usableRange && serviceStartWithinTimeWindow && roundTripWithinVehicleAvailability;
		}

		private boolean reachableWithLongRangeAndTime() {
			return requiredMultiplierApplications != Integer.MAX_VALUE
				&& roundTripDistance <= longRange
				&& serviceStartWithinTimeWindow
				&& roundTripWithinVehicleAvailability;
		}

		private double longRangeUtilization() {
			return roundTripDistance / longRange;
		}

		private double currentRangeUtilization() {
			return roundTripDistance / usableRange;
		}

		private double currentRangeSlack() {
			return usableRange - roundTripDistance;
		}

		private double longRangeSlack() {
			return longRange - roundTripDistance;
		}

		private double serviceTimeWindowSlack() {
			return serviceTimeWindowEnd - serviceStartTime;
		}

		private double vehicleEndSlack() {
			return vehicle.getLatestEndTime() - requiredLatestEndTime;
		}
	}

	private record RangeCandidate(CarrierService service, ServiceReachability reachability) {
	}
}

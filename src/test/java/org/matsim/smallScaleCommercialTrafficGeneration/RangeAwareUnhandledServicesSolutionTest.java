package org.matsim.smallScaleCommercialTrafficGeneration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeAwareUnhandledServicesSolutionTest {

	private static final Id<Link> DEPOT_LINK_ID = Id.createLinkId("depot");
	private static final Id<Link> SERVICE_LINK_ID = Id.createLinkId("service");

	@Test
	void addsHighCostLongRangeVehicleWhenServiceOnlyFitsWithOneRecharge() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.rangeInfeasibleServices());
		assertEquals(0, result.servicesWithoutFeasibleRechargeFallback());
		assertEquals(1, result.addedVehicles());
		assertEquals(1, result.carriersWithAddedVehicles());
		assertEquals(2, carrier.getCarrierCapabilities().getCarrierVehicles().size());

		CarrierVehicle longRangeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_1Recharge", Vehicle.class));
		assertNotNull(longRangeVehicle);
		assertEquals("electric_1Recharge", longRangeVehicle.getType().getId().toString());
		assertEquals(180., VehicleUtils.getEnergyCapacity(longRangeVehicle.getType().getEngineInformation()));
		assertEquals(1230., longRangeVehicle.getType().getCostInformation().getFixedCosts());
		assertEquals(DEPOT_LINK_ID, longRangeVehicle.getLinkId());
	}

	@Test
	void usesNetworkChangeEventsWhenSizingRangeFallbackWindow() {
		Scenario scenario = createScenario(true);
		NetworkChangeEvent changeEvent = new NetworkChangeEvent(3600.);
		changeEvent.addLink(scenario.getNetwork().getLinks().get(DEPOT_LINK_ID));
		changeEvent.addLink(scenario.getNetwork().getLinks().get(SERVICE_LINK_ID));
		changeEvent.setFreespeedChange(new NetworkChangeEvent.ChangeValue(
			NetworkChangeEvent.ChangeType.ABSOLUTE_IN_SI_UNITS, 1.));
		((TimeDependentNetwork) scenario.getNetwork()).addNetworkChangeEvent(changeEvent);

		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle delayedVehicle = CarrierVehicle.Builder
			.newInstance(Id.create("vehicle", Vehicle.class), DEPOT_LINK_ID, electricType)
			.setEarliestStart(4000.)
			.setLatestEnd(24. * 3600.)
			.build();
		carrier.getCarrierCapabilities().getCarrierVehicles().put(delayedVehicle.getId(), delayedVehicle);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		CarrierVehicle fallback = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_1Recharge", Vehicle.class));
		assertNotNull(fallback);
		assertTrue(fallback.getLatestEndTime() - fallback.getEarliestStartTime() > 1850.,
			"Fallback window must include event-adjusted travel time plus the 30-minute slack.");
	}

	@Test
	void addsHigherRechargeVehicleWhenOneMultiplierApplicationIsNotEnough() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(40., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.rangeInfeasibleServices());
		assertEquals(0, result.servicesWithoutFeasibleRechargeFallback());
		assertEquals(1, result.addedVehicles());

		CarrierVehicle longRangeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_2Recharge", Vehicle.class));
		assertNotNull(longRangeVehicle);
		assertEquals("electric_2Recharge", longRangeVehicle.getType().getId().toString());
		assertEquals(160., VehicleUtils.getEnergyCapacity(longRangeVehicle.getType().getEngineInformation()));
		assertEquals(2460., longRangeVehicle.getType().getCostInformation().getFixedCosts());
	}

	@Test
	void addsOneRechargeVehiclePerRangeInfeasibleService() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addService(carrier, CarrierService.Builder
			.newInstance(Id.create("service2", CarrierService.class), SERVICE_LINK_ID, 1)
			.build());
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(2, result.rangeInfeasibleServices());
		assertEquals(0, result.servicesWithoutFeasibleRechargeFallback());
		assertEquals(2, result.addedVehicles());
		assertEquals(1, result.carriersWithAddedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge_1", Vehicle.class)));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void doesNotAddVehicleWhenCurrentRangeCanReachServiceWithClearMargin() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(200., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(0, result.rangeInfeasibleServices());
		assertEquals(0, result.addedVehicles());
		assertEquals(1, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void addsRechargeVehicleWhenCurrentRangeOnlyHasSmallSafetyMargin() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(110., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.rangeInfeasibleServices());
		assertEquals(1, result.addedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
	}

	@Test
	void doesNotAddRechargeVehicleFromUsedTemplateWhenRangeAlreadyFits() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(130., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle usedBaseVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle", Vehicle.class));
		CarrierService handledService = CarrierService.Builder
			.newInstance(Id.create("handledService", CarrierService.class), SERVICE_LINK_ID, 1)
			.build();
		CarriersUtils.addService(carrier, handledService);
		addSelectedPlanWithService(carrier, usedBaseVehicle, handledService);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.rangeInfeasibleServices());
		assertEquals(0, result.addedVehicles());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
	}

	@Test
	void doesNotAddRechargeVehicleWhenOnlyTimeWindowNeedsRepair() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(100., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType, 8.);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.rangeInfeasibleServices());
		assertEquals(0, result.addedVehicles());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
	}

	@Test
	void doesNotAddVehicleWhenFleetContainsVehicleWithoutRangeConstraint() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		VehicleType unrestrictedType = createUnrestrictedVehicleType();
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle unrestrictedVehicle = CarrierVehicle.Builder
			.newInstance(Id.create("unrestrictedVehicle", Vehicle.class), DEPOT_LINK_ID, unrestrictedType)
			.setEarliestStart(0.)
			.setLatestEnd(24. * 3600.)
			.build();
		carrier.getCarrierCapabilities().getCarrierVehicles().put(unrestrictedVehicle.getId(), unrestrictedVehicle);
		carrier.getCarrierCapabilities().getVehicleTypes().add(unrestrictedType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(unrestrictedType.getId(), unrestrictedType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(0, result.rangeInfeasibleServices());
		assertEquals(0, result.addedVehicles());
		assertEquals(2, carrier.getCarrierCapabilities().getCarrierVehicles().size());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
	}

	@Test
	void doesNotAddAnotherLongRangeVehicleWhenExistingRechargeVehicleIsFree() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		RangeAwareUnhandledServicesSolution solution = new RangeAwareUnhandledServicesSolution(100., 2., 10.);

		solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));
		RangeAwareUnhandledServicesSolution.Result result = solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(0, result.addedVehicles());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge_1", Vehicle.class)));
		assertEquals(2, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void addsNextRechargeLevelWhenExistingRechargeVehicleIsOutsideBufferedRange() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(55., 1., 123.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle freeRechargeVehicle = CarrierVehicle.Builder
			.newInstance(Id.create("vehicle_1Recharge", Vehicle.class), DEPOT_LINK_ID, longRangeType)
			.setEarliestStart(0.)
			.setLatestEnd(24. * 3600.)
			.build();
		carrier.getCarrierCapabilities().getCarrierVehicles().put(freeRechargeVehicle.getId(), freeRechargeVehicle);
		carrier.getCarrierCapabilities().getVehicleTypes().add(longRangeType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(longRangeType.getId(), longRangeType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.addedVehicles());
		assertEquals(0, result.servicesCoveredByFreeVehicleNearRangeLimit());
		assertEquals(1, result.rangeInfeasibleServices());
		CarrierVehicle nextRechargeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_2Recharge", Vehicle.class));
		assertNotNull(nextRechargeVehicle);
		assertEquals("electric_2Recharge", nextRechargeVehicle.getType().getId().toString());
		assertEquals(220., VehicleUtils.getEnergyCapacity(nextRechargeVehicle.getType().getEngineInformation()));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void addsSameRechargeLevelFromBetterTemplateWhenExistingRechargeVehicleFailsBufferedTimeCheck() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(70., 1., 123.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle tightRechargeVehicle = CarrierVehicle.Builder
			.newInstance(Id.create("tight_1Recharge", Vehicle.class), DEPOT_LINK_ID, longRangeType)
			.setEarliestStart(0.)
			.setLatestEnd(40.)
			.build();
		carrier.getCarrierCapabilities().getCarrierVehicles().put(tightRechargeVehicle.getId(), tightRechargeVehicle);
		carrier.getCarrierCapabilities().getVehicleTypes().add(longRangeType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(longRangeType.getId(), longRangeType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier), 5.);

		assertEquals(1, result.addedVehicles());
		assertEquals(0, result.servicesCoveredByFreeVehicle());
		assertEquals(0, result.servicesCoveredByFreeVehicleNearRangeLimit());
		assertEquals(1, result.rangeInfeasibleServices());
		CarrierVehicle robustRechargeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_1Recharge", Vehicle.class));
		assertNotNull(robustRechargeVehicle);
		assertEquals("electric_1Recharge", robustRechargeVehicle.getType().getId().toString());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_2Recharge", Vehicle.class)));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void addsAnotherLongRangeVehicleWhenExistingRechargeVehicleIsAlreadyUsed() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		RangeAwareUnhandledServicesSolution solution = new RangeAwareUnhandledServicesSolution(100., 2., 10.);

		solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));
		CarrierVehicle alreadyUsedRechargeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_1Recharge", Vehicle.class));
		CarrierService handledService = CarrierService.Builder
			.newInstance(Id.create("handledService", CarrierService.class), SERVICE_LINK_ID, 1)
			.build();
		CarriersUtils.addService(carrier, handledService);
		addSelectedPlanWithService(carrier, alreadyUsedRechargeVehicle, handledService);

		RangeAwareUnhandledServicesSolution.Result result = solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.addedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge_1", Vehicle.class)));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void addsLongRangeVehicleFromUsedBaseTemplateWhenExtraRangeIsNeeded() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierVehicle usedBaseVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle", Vehicle.class));
		CarrierService handledService = CarrierService.Builder
			.newInstance(Id.create("handledService", CarrierService.class), SERVICE_LINK_ID, 1)
			.build();
		CarriersUtils.addService(carrier, handledService);
		addSelectedPlanWithService(carrier, usedBaseVehicle, handledService);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.addedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
	}

	@Test
	void addsOnlyMissingLongRangeVehicleWhenOneFreeRechargeCannotCoverAllUnhandledServices() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addService(carrier, CarrierService.Builder
			.newInstance(Id.create("service2", CarrierService.class), SERVICE_LINK_ID, 1)
			.build());
		CarrierVehicle freeRechargeVehicle = CarrierVehicle.Builder
			.newInstance(Id.create("vehicle_1Recharge", Vehicle.class), DEPOT_LINK_ID, longRangeType)
			.setEarliestStart(0.)
			.setLatestEnd(24. * 3600.)
			.build();
		carrier.getCarrierCapabilities().getCarrierVehicles().put(freeRechargeVehicle.getId(), freeRechargeVehicle);
		carrier.getCarrierCapabilities().getVehicleTypes().add(longRangeType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(longRangeType.getId(), longRangeType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.addedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge_1", Vehicle.class)));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void doesNotAddRechargeVehicleWhenReferenceVehicleTimeWindowIsTooShort() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarrierService serviceLongerThanVehicleAvailability = CarrierService.Builder
			.newInstance(Id.create("service", CarrierService.class), SERVICE_LINK_ID, 1)
			.setServiceDuration(25. * 3600.)
			.build();
		carrier.getServices().put(serviceLongerThanVehicleAvailability.getId(), serviceLongerThanVehicleAvailability);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);

		RangeAwareUnhandledServicesSolution.Result result = new RangeAwareUnhandledServicesSolution(100., 2., 10.)
			.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(0, result.addedVehicles());
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_1Recharge", Vehicle.class)));
		assertEquals(1, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	@Test
	void writesMainRunVehicleTypesWithOriginalFixedCosts(@TempDir Path tempDir) throws Exception {
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		electricType.setWidth(1.5);
		electricType.getCapacity().setSeats(1).setStandingRoom(0).setOther(4600.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		VehicleType unrestrictedType = createUnrestrictedVehicleType();
		Vehicles inputVehicles = VehicleUtils.createVehiclesContainer();
		inputVehicles.addVehicleType(electricType);
		inputVehicles.addVehicleType(unrestrictedType);

		Path inputVehicleTypes = tempDir.resolve("vehicles.xml.gz");
		Path carrierVehicleTypes = tempDir.resolve("output_carriersVehicleTypes.xml.gz");
		Path outputVehicleTypes = tempDir.resolve("vehicles_withFallbacks.xml.gz");
		new MatsimVehicleWriter(inputVehicles).writeFile(inputVehicleTypes.toString());
		writeCarrierVehicleTypes(carrierVehicleTypes, longRangeType);

		int addedTypes = RechargeVehicleTypeUtils.writeMainRunVehicleTypesMergedWithCarrierVehicleTypes(inputVehicleTypes,
			List.of(carrierVehicleTypes), outputVehicleTypes);

		Vehicles outputVehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(outputVehicles).readFile(outputVehicleTypes.toString());
		VehicleType normalizedLongRangeType = outputVehicles.getVehicleTypes().get(Id.create("electric_1Recharge", VehicleType.class));

		assertEquals(1, addedTypes);
		assertNotNull(normalizedLongRangeType);
		assertEquals(90., VehicleUtils.getEnergyCapacity(normalizedLongRangeType.getEngineInformation()));
		assertEquals(123., normalizedLongRangeType.getCostInformation().getFixedCosts());
		assertEquals(1., normalizedLongRangeType.getCostInformation().getCostsPerMeter());
		assertEquals(1., normalizedLongRangeType.getCostInformation().getCostsPerSecond());
		assertEquals(TransportMode.car, normalizedLongRangeType.getNetworkMode());
		assertEquals(1.5, normalizedLongRangeType.getWidth());
		assertEquals(1, normalizedLongRangeType.getCapacity().getSeats());
		assertEquals(0, normalizedLongRangeType.getCapacity().getStandingRoom());
		assertEquals(4600., normalizedLongRangeType.getCapacity().getOther());
		assertNull(outputVehicles.getVehicleTypes().get(Id.create("unrestricted_1Recharge", VehicleType.class)));
	}

	@Test
	void normalizesExistingMainRunFallbackCostsAndCapacity(@TempDir Path tempDir) throws Exception {
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		Vehicles inputVehicles = VehicleUtils.createVehiclesContainer();
		inputVehicles.addVehicleType(electricType);
		inputVehicles.addVehicleType(longRangeType);

		Path inputVehicleTypes = tempDir.resolve("vehicles.xml.gz");
		Path outputVehicleTypes = tempDir.resolve("vehicles_withFallbacks.xml.gz");
		new MatsimVehicleWriter(inputVehicles).writeFile(inputVehicleTypes.toString());

		int addedTypes = RechargeVehicleTypeUtils.writeMainRunVehicleTypesMergedWithCarrierVehicleTypes(inputVehicleTypes,
			List.of(), outputVehicleTypes);

		Vehicles outputVehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(outputVehicles).readFile(outputVehicleTypes.toString());
		VehicleType normalizedLongRangeType = outputVehicles.getVehicleTypes().get(Id.create("electric_1Recharge", VehicleType.class));

		assertEquals(0, addedTypes);
		assertEquals(90., VehicleUtils.getEnergyCapacity(normalizedLongRangeType.getEngineInformation()));
		assertEquals(123., normalizedLongRangeType.getCostInformation().getFixedCosts());
	}

	@Test
	void mergesTwoRechargeVehicleTypeByRechargeSuffix(@TempDir Path tempDir) throws Exception {
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		VehicleType twoRechargeType = createElectricVehicleType("electric_2Recharge", 270., 1., 1230.);
		Vehicles inputVehicles = VehicleUtils.createVehiclesContainer();
		inputVehicles.addVehicleType(electricType);

		Path inputVehicleTypes = tempDir.resolve("vehicles.xml.gz");
		Path carrierVehicleTypes = tempDir.resolve("output_carriersVehicleTypes.xml.gz");
		Path outputVehicleTypes = tempDir.resolve("vehicles_withFallbacks.xml.gz");
		new MatsimVehicleWriter(inputVehicles).writeFile(inputVehicleTypes.toString());
		writeCarrierVehicleTypes(carrierVehicleTypes, twoRechargeType);

		int addedTypes = RechargeVehicleTypeUtils.writeMainRunVehicleTypesMergedWithCarrierVehicleTypes(inputVehicleTypes,
			List.of(carrierVehicleTypes), outputVehicleTypes);

		Vehicles outputVehicles = VehicleUtils.createVehiclesContainer();
		new MatsimVehicleReader(outputVehicles).readFile(outputVehicleTypes.toString());
		VehicleType normalizedTwoRechargeType = outputVehicles.getVehicleTypes().get(Id.create("electric_2Recharge", VehicleType.class));

		assertEquals(1, addedTypes);
		assertNotNull(normalizedTwoRechargeType);
		assertEquals(90., VehicleUtils.getEnergyCapacity(normalizedTwoRechargeType.getEngineInformation()));
		assertEquals(123., normalizedTwoRechargeType.getCostInformation().getFixedCosts());
	}

	@Test
	void restoresFallbackCostsAfterTourPlanning() {
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		VehicleType longRangeType = RechargeVehicleTypeUtils.createRechargeVehicleType(
			Id.create("electric_1Recharge", VehicleType.class), electricType, 2., 10.,
			" (range fallback)");
		Map<Id<VehicleType>, VehicleType> vehicleTypes = new HashMap<>();
		vehicleTypes.put(electricType.getId(), electricType);
		vehicleTypes.put(longRangeType.getId(), longRangeType);

		assertEquals(180., VehicleUtils.getEnergyCapacity(longRangeType.getEngineInformation()));
		assertEquals(1230., longRangeType.getCostInformation().getFixedCosts());

		int restoredTypes = RechargeVehicleTypeUtils.restoreRechargeCostsAndCapacity(vehicleTypes);

		assertEquals(1, restoredTypes);
		assertEquals(90., VehicleUtils.getEnergyCapacity(longRangeType.getEngineInformation()));
		assertEquals(123., longRangeType.getCostInformation().getFixedCosts());
	}

	private static Scenario createScenario() {
		return createScenario(false);
	}

	private static Scenario createScenario(boolean timeVariantNetwork) {
		var config = ConfigUtils.createConfig();
		config.network().setTimeVariantNetwork(timeVariantNetwork);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = scenario.getNetwork();
		Node fromDepot = NetworkUtils.createAndAddNode(network, Id.createNodeId("fromDepot"), new Coord(0., 0.));
		Node toDepot = NetworkUtils.createAndAddNode(network, Id.createNodeId("toDepot"), new Coord(50., 0.));
		Link depotLink = NetworkUtils.createAndAddLink(network, DEPOT_LINK_ID, fromDepot, toDepot, 50., 10., 1_000., 1.);
		Link serviceLink = NetworkUtils.createAndAddLink(network, SERVICE_LINK_ID, toDepot, fromDepot, 50., 10., 1_000., 1.);
		Set<String> allowedModes = Set.of(TransportMode.car);
		depotLink.setAllowedModes(allowedModes);
		serviceLink.setAllowedModes(allowedModes);
		return scenario;
	}

	private static VehicleType createElectricVehicleType(double energyCapacity, double consumptionPerMeter, double fixedCost) {
		return createElectricVehicleType("electric", energyCapacity, consumptionPerMeter, fixedCost);
	}

	private static VehicleType createElectricVehicleType(String id, double energyCapacity, double consumptionPerMeter, double fixedCost) {
		VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create(id, VehicleType.class));
		vehicleType.setNetworkMode(TransportMode.car);
		vehicleType.setMaximumVelocity(10.);
		vehicleType.getCostInformation().setCostsPerMeter(1.);
		vehicleType.getCostInformation().setCostsPerSecond(1.);
		vehicleType.getCostInformation().setFixedCost(fixedCost);
		VehicleUtils.setHbefaTechnology(vehicleType.getEngineInformation(), "electricity");
		VehicleUtils.setEnergyCapacity(vehicleType.getEngineInformation(), energyCapacity);
		VehicleUtils.setEnergyConsumptionKWhPerMeter(vehicleType.getEngineInformation(), consumptionPerMeter);
		return vehicleType;
	}

	private static void writeCarrierVehicleTypes(Path carrierVehicleTypesFile, VehicleType... vehicleTypes) {
		CarrierVehicleTypes carrierVehicleTypes = new CarrierVehicleTypes();
		for (VehicleType vehicleType : vehicleTypes) {
			carrierVehicleTypes.getVehicleTypes().put(vehicleType.getId(), vehicleType);
		}
		CarriersUtils.writeCarrierVehicleTypes(carrierVehicleTypes, carrierVehicleTypesFile.toString());
	}

	private static void addSelectedPlanWithService(Carrier carrier, CarrierVehicle vehicle, CarrierService handledService) {
		Tour.Builder tourBuilder = Tour.Builder.newInstance(Id.create("tour", Tour.class));
		tourBuilder.scheduleStart(vehicle.getLinkId());
		tourBuilder.addLeg(tourBuilder.createLeg(null, vehicle.getEarliestStartTime(), 0.));
		tourBuilder.scheduleService(handledService);
		tourBuilder.addLeg(tourBuilder.createLeg(null, vehicle.getEarliestStartTime(), 0.));
		tourBuilder.scheduleEnd(vehicle.getLinkId());
		CarrierPlan plan = new CarrierPlan(List.of(ScheduledTour.newInstance(tourBuilder.build(), vehicle, vehicle.getEarliestStartTime())));
		carrier.addPlan(plan);
		carrier.setSelectedPlan(plan);
	}

	private static VehicleType createUnrestrictedVehicleType() {
		VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create("unrestricted", VehicleType.class));
		vehicleType.setNetworkMode(TransportMode.car);
		vehicleType.setMaximumVelocity(10.);
		vehicleType.getCostInformation().setCostsPerMeter(1.);
		vehicleType.getCostInformation().setCostsPerSecond(1.);
		vehicleType.getCostInformation().setFixedCost(123.);
		return vehicleType;
	}

	private static Carrier createCarrierWithVehicleAndService(VehicleType vehicleType) {
		return createCarrierWithVehicleAndService(vehicleType, 24. * 3600.);
	}

	private static Carrier createCarrierWithVehicleAndService(VehicleType vehicleType, double latestEndTime) {
		CarrierVehicle vehicle = CarrierVehicle.Builder
			.newInstance(Id.create("vehicle", Vehicle.class), DEPOT_LINK_ID, vehicleType)
			.setEarliestStart(0.)
			.setLatestEnd(latestEndTime)
			.build();
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.setFleetSize(CarrierCapabilities.FleetSize.FINITE)
			.addVehicle(vehicle)
			.build());
		CarriersUtils.addService(carrier, CarrierService.Builder
			.newInstance(Id.create("service", CarrierService.class), SERVICE_LINK_ID, 1)
			.build());
		return carrier;
	}
}

package org.matsim.smallScaleCommercialTrafficGeneration;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierService;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
		assertEquals(0, result.servicesBeyondOneRechargeRange());
		assertEquals(1, result.addedVehicles());
		assertEquals(1, result.carriersWithAddedVehicles());
		assertEquals(2, carrier.getCarrierCapabilities().getCarrierVehicles().size());

		CarrierVehicle longRangeVehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.get(Id.create("vehicle_oneRecharge", Vehicle.class));
		assertNotNull(longRangeVehicle);
		assertEquals("electric_oneRecharge", longRangeVehicle.getType().getId().toString());
		assertEquals(180., VehicleUtils.getEnergyCapacity(longRangeVehicle.getType().getEngineInformation()));
		assertEquals(1230., longRangeVehicle.getType().getCostInformation().getFixedCosts());
		assertEquals(DEPOT_LINK_ID, longRangeVehicle.getLinkId());
	}

	@Test
	void doesNotAddVehicleWhenCurrentRangeCanReachService() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(100., 1., 123.);
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
		assertNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_oneRecharge", Vehicle.class)));
	}

	@Test
	void addsAnotherLongRangeVehicleWhenRangeInfeasibleServiceRemainsUnhandled() {
		Scenario scenario = createScenario();
		VehicleType electricType = createElectricVehicleType(90., 1., 123.);
		Carrier carrier = createCarrierWithVehicleAndService(electricType);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		CarriersUtils.getOrAddCarrierVehicleTypes(scenario).getVehicleTypes().put(electricType.getId(), electricType);
		RangeAwareUnhandledServicesSolution solution = new RangeAwareUnhandledServicesSolution(100., 2., 10.);

		solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));
		RangeAwareUnhandledServicesSolution.Result result = solution.addLongRangeVehiclesForRangeInfeasibleServices(scenario, List.of(carrier));

		assertEquals(1, result.addedVehicles());
		assertNotNull(carrier.getCarrierCapabilities().getCarrierVehicles().get(Id.create("vehicle_oneRecharge_1", Vehicle.class)));
		assertEquals(3, carrier.getCarrierCapabilities().getCarrierVehicles().size());
	}

	private static Scenario createScenario() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
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
		VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create("electric", VehicleType.class));
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
		CarrierVehicle vehicle = CarrierVehicle.Builder
			.newInstance(Id.create("vehicle", Vehicle.class), DEPOT_LINK_ID, vehicleType)
			.setEarliestStart(0.)
			.setLatestEnd(24. * 3600.)
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

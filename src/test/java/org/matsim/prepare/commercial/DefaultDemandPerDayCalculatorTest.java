package org.matsim.prepare.commercial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDemandPerDayCalculatorTest {

	private DefaultDemandPerDayCalculator calculator;
	private Person freightDemandDataRelation;
	private Carrier carrier;

	@BeforeEach
	void setUp() {
		calculator = new DefaultDemandPerDayCalculator(250, 1.0);

		freightDemandDataRelation = createFreightDemandDataRelation();

		carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(Id.create("vehicle", Vehicle.class),
			Id.createLinkId("link"), createVehicleType()).build();
		CarrierCapabilities capabilities = CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).build();
		carrier.setCarrierCapabilities(capabilities);
	}

	private Person createFreightDemandDataRelation() {
		Person freightDemandDataRelation = PopulationUtils.getFactory().createPerson(Id.createPersonId("exampleFreightDemandDataRelation"));
		freightDemandDataRelation.getAttributes().putAttribute("origin_cell", "origin1");
		freightDemandDataRelation.getAttributes().putAttribute("destination_locationId", "destination1");
		freightDemandDataRelation.getAttributes().putAttribute("tons_per_year", 5.0);
		return freightDemandDataRelation;
	}

	private VehicleType createVehicleType() {
		VehicleType vehicleType = VehicleUtils.getFactory().createVehicleType(Id.create("vehicleType", VehicleType.class));
		vehicleType.getCapacity().setOther(5000);
		return vehicleType;
	}

	@Test
	void testCalculateKilogramsPerDay() {
		assertEquals(4000, calculator.calculateKilogramsPerDay(1000.0));
	}

	@Test
	void testCalculateWasteDemandPerDay() {
		int demandPerDay = calculator.calculateWasteDemandPerDay(freightDemandDataRelation);
		assertEquals(96, demandPerDay);  // Depending on the random value, the actual value may vary
	}

	@Test
	void testCalculateParcelsPerDay() {
		assertEquals(38, calculator.calculateParcelsPerDay(10000));
	}

	@Test
	void testCalculateNumberOfJobsForDemand() {
		assertEquals(1, calculator.calculateNumberOfJobsForDemand(carrier, 4000));
		assertEquals(2, calculator.calculateNumberOfJobsForDemand(carrier, 6000));
	}
}

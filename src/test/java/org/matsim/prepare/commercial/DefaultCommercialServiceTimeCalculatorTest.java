package org.matsim.prepare.commercial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCommercialServiceTimeCalculatorTest {

	private DefaultCommercialServiceTimeCalculator calculator;
	private Person freightDemandDataRelationParcel;
	private Person freightDemandDataRelationWaste;

	@BeforeEach
	void setUp() {
		calculator = new DefaultCommercialServiceTimeCalculator();
		freightDemandDataRelationParcel = createFreightDemandDataRelation(150);
		freightDemandDataRelationWaste = createFreightDemandDataRelation(140);
	}

	private Person createFreightDemandDataRelation(int goodsType) {
		Person freightDemandDataRelation = PopulationUtils.getFactory().createPerson(Id.createPersonId("exampleFreightDemandDataRelation"));
		freightDemandDataRelation.getAttributes().putAttribute("goodsType", goodsType);
		return freightDemandDataRelation;
	}

	@Test
	void testCalculateDeliveryTime_ParcelDelivery_LargeDemand() {
		int deliveryTime = calculator.calculateDeliveryTime(freightDemandDataRelationParcel, 150);
		assertEquals(1800, deliveryTime); // 30 minutes
	}

	@Test
	void testCalculateDeliveryTime_ParcelDelivery_SmallDemand() {
		int deliveryTime = calculator.calculateDeliveryTime(freightDemandDataRelationParcel, 50);
		assertEquals(9000, deliveryTime); // 50 parcels * 180 seconds
	}

	@Test
	void testCalculateDeliveryTime_WasteCollection() {
		int deliveryTime = calculator.calculateDeliveryTime(freightDemandDataRelationWaste, 22000);
		assertEquals(5400, deliveryTime); // (22000 / 11000) * 45 * 60 seconds
	}

	@Test
	void testCalculateDeliveryTime_Default() {
		Person personOther = createFreightDemandDataRelation(100);
		int deliveryTime = calculator.calculateDeliveryTime(personOther, 100);
		assertEquals(1800, deliveryTime); // 30 minutes default
	}

	@Test
	void testCalculatePickupTime_WasteCollection() {
		int pickupTime = calculator.calculatePickupTime(freightDemandDataRelationWaste, 220);
		assertEquals(82, pickupTime); // 41 seconds per bin * 2 bins
	}

	@Test
	void testCalculatePickupTime_Default() {
		Person personOther = createFreightDemandDataRelation(100);
		int pickupTime = calculator.calculatePickupTime(personOther, 100);
		assertEquals(0, pickupTime); // default case, no time needed
	}
}

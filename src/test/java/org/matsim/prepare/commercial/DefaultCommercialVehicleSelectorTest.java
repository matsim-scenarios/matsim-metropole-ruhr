package org.matsim.prepare.commercial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.CarrierCapabilities;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultCommercialVehicleSelectorTest {

	private DefaultCommercialVehicleSelector selector;
	private Person freightDemandDataRelationFTL;
	private Person freightDemandDataRelationWaste;
	private Person freightDemandDataRelationParcel;
	private Person freightDemandDataRelationParcelTruck18t;
	private Person freightDemandDataRelationRest;

	@BeforeEach
	void setUp() {
		selector = new DefaultCommercialVehicleSelector();

		freightDemandDataRelationFTL = createFreightDemandDataRelation("FTL", 100);
		freightDemandDataRelationWaste = createFreightDemandDataRelation("other", 140);
		freightDemandDataRelationParcel = createFreightDemandDataRelation("other", 150);
		freightDemandDataRelationParcelTruck18t = createFreightDemandDataRelation("other", 150);
		freightDemandDataRelationRest = createFreightDemandDataRelation("other", 120);
	}

	private Person createFreightDemandDataRelation(String transportType, int goodsType) {

		Person freightDemandDataRelation = PopulationUtils.getFactory().createPerson(Id.createPersonId("exampleFreightDemandDataRelation"));
		freightDemandDataRelation.getAttributes().putAttribute("goods_type", goodsType);
		freightDemandDataRelation.getAttributes().putAttribute("transportType", transportType);
		return freightDemandDataRelation;
	}

	@Test
	void testGetVehicleTypeForPlan() {
		assertEquals("heavy40t", selector.getVehicleTypeForPlan(freightDemandDataRelationFTL, ""));
		assertEquals("waste_collection_diesel", selector.getVehicleTypeForPlan(freightDemandDataRelationWaste, ""));
		assertEquals("mercedes313_parcel", selector.getVehicleTypeForPlan(freightDemandDataRelationParcel, ""));
		assertEquals("medium18t_parcel", selector.getVehicleTypeForPlan(freightDemandDataRelationParcelTruck18t, "parcel_truck18t"));
		assertEquals("medium18t", selector.getVehicleTypeForPlan(freightDemandDataRelationRest, ""));
	}

	@Test
	void testGetPossibleVehicleTypes() {
		List<String> vehicleTypesFTL = selector.getPossibleVehicleTypes(freightDemandDataRelationFTL, "", CarrierCapabilities.FleetSize.INFINITE);
		assertEquals(1, vehicleTypesFTL.size());
		assertTrue(vehicleTypesFTL.contains("heavy40t"));

		vehicleTypesFTL = selector.getPossibleVehicleTypes(freightDemandDataRelationFTL, "", CarrierCapabilities.FleetSize.FINITE);
		assertEquals(1, vehicleTypesFTL.size());
		assertTrue(vehicleTypesFTL.contains("heavy40t"));

		List<String> vehicleTypesWaste = selector.getPossibleVehicleTypes(freightDemandDataRelationWaste, "", CarrierCapabilities.FleetSize.INFINITE);
		assertEquals(1, vehicleTypesWaste.size());
		assertTrue(vehicleTypesWaste.contains("waste_collection_diesel"));

		vehicleTypesWaste = selector.getPossibleVehicleTypes(freightDemandDataRelationWaste, "", CarrierCapabilities.FleetSize.FINITE);
		assertEquals(1, vehicleTypesWaste.size());
		assertTrue(vehicleTypesWaste.contains("waste_collection_diesel"));

		List<String> vehicleTypesParcel = selector.getPossibleVehicleTypes(freightDemandDataRelationParcel, "", CarrierCapabilities.FleetSize.INFINITE);
		assertEquals(1, vehicleTypesParcel.size());
		assertTrue(vehicleTypesParcel.contains("mercedes313_parcel"));

		vehicleTypesParcel = selector.getPossibleVehicleTypes(freightDemandDataRelationParcel, "", CarrierCapabilities.FleetSize.FINITE);
		assertEquals(1, vehicleTypesParcel.size());
		assertTrue(vehicleTypesParcel.contains("mercedes313_parcel"));

		List<String> vehicleTypesParcelTruck18t = selector.getPossibleVehicleTypes(freightDemandDataRelationParcelTruck18t, "_truck18t",
			CarrierCapabilities.FleetSize.INFINITE);
		assertEquals(1, vehicleTypesParcelTruck18t.size());
		assertTrue(vehicleTypesParcelTruck18t.contains("medium18t_parcel"));

		vehicleTypesParcelTruck18t = selector.getPossibleVehicleTypes(freightDemandDataRelationParcelTruck18t, "_truck18t",
			CarrierCapabilities.FleetSize.FINITE);
		assertEquals(1, vehicleTypesParcelTruck18t.size());
		assertTrue(vehicleTypesParcelTruck18t.contains("medium18t_parcel"));

		List<String> vehicleTypesRest = selector.getPossibleVehicleTypes(freightDemandDataRelationRest, "", CarrierCapabilities.FleetSize.INFINITE);
		assertEquals(1, vehicleTypesRest.size());
		assertTrue(vehicleTypesRest.contains("medium18t"));

		vehicleTypesRest = selector.getPossibleVehicleTypes(freightDemandDataRelationRest, "", CarrierCapabilities.FleetSize.FINITE);
		assertEquals(1, vehicleTypesRest.size());
		assertTrue(vehicleTypesRest.contains("medium18t"));
	}

	@Test
	void testGetModeForFTLTrip() {
		assertEquals("truck40t", selector.getModeForFTLTrip(freightDemandDataRelationFTL));
		assertNull(selector.getModeForFTLTrip(freightDemandDataRelationWaste));
		assertNull(selector.getModeForFTLTrip(freightDemandDataRelationParcel));
	}
}

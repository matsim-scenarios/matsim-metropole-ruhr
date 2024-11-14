package org.matsim.prepare.commercial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PopulationUtils;

import static org.junit.jupiter.api.Assertions.*;

class CommercialTrafficUtilsTest {

	private Person person;
	private RvrTripRelation rvrTripRelation;

	@BeforeEach
	void setUp() {
		person = PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	@Test
	void testWriteCommonAttributesForNonParcel() {
		rvrTripRelation = new RvrTripRelation.Builder()
			.goodsType("100") // Non-parcel goods type
			.originCell("originCell")
			.destinationCell("destinationCell")
			.originLocationId("originLocationId")
			.destinationLocationId("destinationLocationId")
			.tonsPerYear(500)
			.transportType("LTL")
			.originX(1.0)
			.originY(2.0)
			.destinationX(3.0)
			.destinationY(4.0)
			.build();

		CommercialTrafficUtils.writeCommonAttributes(person, rvrTripRelation, "tripRelationId");

		assertEquals("tripRelationId", person.getAttributes().getAttribute("trip_relation_index"));
		assertEquals("originCell", person.getAttributes().getAttribute("origin_cell"));
		assertEquals("destinationCell", person.getAttributes().getAttribute("destination_cell"));
		assertEquals("originLocationId", person.getAttributes().getAttribute("origin_locationId"));
		assertEquals("destinationLocationId", person.getAttributes().getAttribute("destination_locationId"));
		assertEquals(500.0, person.getAttributes().getAttribute("tons_per_year"));
		assertEquals("LTL", person.getAttributes().getAttribute("transportType"));
		assertEquals(1.0, person.getAttributes().getAttribute("origin_x"));
		assertEquals(2.0, person.getAttributes().getAttribute("origin_y"));
		assertEquals(3.0, person.getAttributes().getAttribute("destination_x"));
		assertEquals(4.0, person.getAttributes().getAttribute("destination_y"));
	}

	@Test
	void testWriteCommonAttributesForParcel() {
		rvrTripRelation = new RvrTripRelation.Builder()
			.goodsType("150") // Parcel goods type
			.parcelOperator("operator")
			.parcelHubId("hubId")
			.parcelsPerYear(1000)
			.build();

		CommercialTrafficUtils.writeCommonAttributes(person, rvrTripRelation, "tripRelationId");

		assertEquals("tripRelationId", person.getAttributes().getAttribute("trip_relation_index"));
		assertEquals("operator", person.getAttributes().getAttribute("parcelOperator"));
		assertEquals("hubId", person.getAttributes().getAttribute("parcelHubId"));
		assertEquals(1000.0, person.getAttributes().getAttribute("parcelsPerYear"));
	}

	@Test
	void testGetters() {
		person.getAttributes().putAttribute("transportType", "LTL");
		person.getAttributes().putAttribute("trip_relation_index", "tripRelationId");
		person.getAttributes().putAttribute("origin_cell", "originCell");
		person.getAttributes().putAttribute("destination_cell", "destinationCell");
		person.getAttributes().putAttribute("origin_x", 1.0);
		person.getAttributes().putAttribute("origin_y", 2.0);
		person.getAttributes().putAttribute("destination_x", 3.0);
		person.getAttributes().putAttribute("destination_y", 4.0);
		person.getAttributes().putAttribute("goodsType", "100");
		person.getAttributes().putAttribute("tons_per_year", 500.0);
		person.getAttributes().putAttribute("origin_locationId", "originLocationId");
		person.getAttributes().putAttribute("destination_locationId", "destinationLocationId");
		person.getAttributes().putAttribute("parcelOperator", "operator");
		person.getAttributes().putAttribute("parcelsPerYear", 1000.0);
		person.getAttributes().putAttribute("parcelHubId", "hubId");

		assertEquals("LTL", CommercialTrafficUtils.getTransportType(person));
		assertEquals("tripRelationId", CommercialTrafficUtils.getTripRelationIndex(person));
		assertEquals("originCell", CommercialTrafficUtils.getOriginCell(person));
		assertEquals("destinationCell", CommercialTrafficUtils.getDestinationCell(person));
		assertEquals(1.0, CommercialTrafficUtils.getOriginX(person));
		assertEquals(2.0, CommercialTrafficUtils.getOriginY(person));
		assertEquals(3.0, CommercialTrafficUtils.getDestinationX(person));
		assertEquals(4.0, CommercialTrafficUtils.getDestinationY(person));
		assertEquals(100, CommercialTrafficUtils.getGoodsType(person));
		assertEquals(500.0, CommercialTrafficUtils.getTonsPerYear(person));
		assertEquals("originLocationId", CommercialTrafficUtils.getOriginLocationId(person));
		assertEquals("destinationLocationId", CommercialTrafficUtils.getDestinationLocationId(person));
		assertEquals("operator", CommercialTrafficUtils.getParcelOperator(person));
		assertEquals(1000, CommercialTrafficUtils.getParcelsPerYear(person));
		assertEquals("hubId", CommercialTrafficUtils.getParcelHubId(person));
	}
}

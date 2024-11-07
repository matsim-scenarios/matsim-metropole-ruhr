package org.matsim.prepare.commercial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RvrTripRelationTest {

	@Test
	void testBuilderAndGetters() {
		RvrTripRelation rvrTripRelation = new RvrTripRelation.Builder()
			.originCell("originCell")
			.originLocationId("originLocationId")
			.destinationCell("destinationCell")
			.destinationLocationId("destinationLocationId")
			.transportType("LTL")
			.goodsType("100")
			.tonsPerYear(500)
			.originX(1.0)
			.originY(2.0)
			.destinationX(3.0)
			.destinationY(4.0)
			.parcelsPerYear(1000)
			.parcelOperator("operator")
			.parcelHubId("hubId")
			.build();

		assertEquals("originCell", rvrTripRelation.getOriginCell());
		assertEquals("originLocationId", rvrTripRelation.getOriginLocationId());
		assertEquals("destinationCell", rvrTripRelation.getDestinationCell());
		assertEquals("destinationLocationId", rvrTripRelation.getDestinationLocationId());
		assertEquals("LTL", rvrTripRelation.getTransportType());
		assertEquals("100", rvrTripRelation.getGoodsType());
		assertEquals(500.0, rvrTripRelation.getTonsPerYear());
		assertEquals(1.0, rvrTripRelation.getOriginX());
		assertEquals(2.0, rvrTripRelation.getOriginY());
		assertEquals(3.0, rvrTripRelation.getDestinationX());
		assertEquals(4.0, rvrTripRelation.getDestinationY());
		assertEquals(1000.0, rvrTripRelation.getParcelsPerYear());
		assertEquals("operator", rvrTripRelation.getParcelOperator());
		assertEquals("hubId", rvrTripRelation.getParcelHubId());
	}

	@Test
	void testParcelAttributes() {
		RvrTripRelation rvrTripRelation = new RvrTripRelation.Builder()
			.goodsType("150") // Parcel goods type
			.parcelOperator("operator")
			.parcelHubId("hubId")
			.parcelsPerYear(1000)
			.build();

		assertEquals("150", rvrTripRelation.getGoodsType());
		assertEquals("operator", rvrTripRelation.getParcelOperator());
		assertEquals("hubId", rvrTripRelation.getParcelHubId());
		assertEquals(1000.0, rvrTripRelation.getParcelsPerYear());
	}
}

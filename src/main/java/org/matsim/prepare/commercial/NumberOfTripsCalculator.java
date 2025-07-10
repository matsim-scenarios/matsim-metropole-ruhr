package org.matsim.prepare.commercial;

public interface NumberOfTripsCalculator {
	int calculateNumberOfTrips(double tonsPerYear, String goodsType);
}

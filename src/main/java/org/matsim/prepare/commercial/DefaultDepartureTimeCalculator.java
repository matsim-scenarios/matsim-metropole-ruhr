package org.matsim.prepare.commercial;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.Pair;
import org.matsim.api.core.v01.population.Person;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Default implementation of the {@link DepartureTimeCalculator}.
 * Calculates the departure time of a tour for a given freight demand data relation.
 *
 * @Author Ricardo Ewert
 */
public class DefaultDepartureTimeCalculator implements DepartureTimeCalculator {

    private final Random rnd = new Random(1111);

	private final EnumeratedDistribution<DurationsBounds> longDistanceStartTimeDistribution;

	public DefaultDepartureTimeCalculator() {
		// Hourly weights are derived from the BASt long-distance freight start-time
		// analysis in MATSim-Germany: CalculateStartTimesForLongDistanceTrips.R.
		// The distribution samples one one-hour interval

		RandomGenerator rndGenerator = new MersenneTwister(4711);

		List<Pair<DurationsBounds, Double>> longDistanceStartTimeDistributionBounds = new ArrayList<>();
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(0, 1), 0.0388254168523453));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(1, 2), 0.0438873470682074));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(2, 3), 0.0488922045279851));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(3, 4), 0.0535853768337898));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(4, 5), 0.0575570741626213));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(5, 6), 0.0602146906994415));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(6, 7), 0.0611531098376689));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(7, 8), 0.060672649437304));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(8, 9), 0.059194087445975));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(9, 10), 0.0567526875444281));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(10, 11), 0.0534399937238652));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(11, 12), 0.0494918016667157));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(12, 13), 0.0451276015756904));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(13, 14), 0.0404679896001495));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(14, 15), 0.035658101882499));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(15, 16), 0.0310139421807826));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(16, 17), 0.0269416178998621));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(17, 18), 0.0237761380830235));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(18, 19), 0.0217210571392102));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(19, 20), 0.021057043105208));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(20, 21), 0.0221801166161617));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(21, 22), 0.025154141369418));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(22, 23), 0.0293096183361158));
		longDistanceStartTimeDistributionBounds.add(Pair.create(new DurationsBounds(23, 24), 0.0339261924115318));
		longDistanceStartTimeDistribution = new EnumeratedDistribution<>(rndGenerator, longDistanceStartTimeDistributionBounds);
	}

    @Override
	public double calculateDepartureTime(Person freightDemandDataRelation) {
		if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
			return rnd.nextInt(6 * 3600, (7 * 3600));
		else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) // parcel delivery
			if (CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation).equals("dhl"))
				return rnd.nextInt((int) (9.5 * 3600), 11 * 3600);
			else if (CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation).equals("dpd"))
				return rnd.nextInt(8 * 3600, (int) (10.25 * 3600));
			else if (CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation).equals("ups"))
				return rnd.nextInt(8 * 3600, (int) (9.25 * 3600));
			else if (CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation).equals("hermes"))
				return rnd.nextInt(8 * 3600, 12 * 3600);
			else if (CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation).equals("gls"))
				return rnd.nextInt(8 * 3600, 10 * 3600);
			else
				throw new RuntimeException("Parcel operator not recognized: " + CommercialTrafficUtils.getParcelOperator(freightDemandDataRelation));
		else if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).contains("FTL"))
			return calculateLongDistanceDepartureTime();
		else if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("LTL"))
			return rnd.nextInt(6 * 3600, 12 * 3600);
		else
			throw new RuntimeException("Transport type not recognized: " + CommercialTrafficUtils.getTransportType(freightDemandDataRelation));
	}

	private int calculateLongDistanceDepartureTime() {
		DurationsBounds durationBounds = longDistanceStartTimeDistribution.sample();
		return rnd.nextInt(durationBounds.lowerBoundStartTime * 3600, durationBounds.upperBoundStartTime * 3600);
    }

	private record DurationsBounds(int lowerBoundStartTime, int upperBoundStartTime) {}
}

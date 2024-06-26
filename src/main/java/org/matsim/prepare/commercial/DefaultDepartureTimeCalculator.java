package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.Random;

/**
 * Default implementation of the {@link DepartureTimeCalculator}.
 * Calculates the departure time of a tour for a given freight demand data relation.
 *
 * @Author Ricardo Ewert
 */
public class DefaultDepartureTimeCalculator implements DepartureTimeCalculator {

    private final Random rnd = new Random(1111);

    @Override
    public double calculateDepartureTime(Person freightDemandDataRelation) {
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
            return rnd.nextInt(6 * 3600, (7 * 3600));
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) // parcel delivery
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
        return rnd.nextInt(6 * 3600, 12 * 3600); // possibility to add assumptions for other goods types
    }

}

package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.Random;

public class DefaultDepartureTimeCalculatorRuhr implements DepartureTimeCalculatorRuhr {

    private final Random rnd = new Random(1111);

    @Override
    public double calculateDepartureTime(Person freightDemandDataRelation) {
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
            return 6 * 3600;
        return rnd.nextInt(5 * 3600, 12 *3600); // TODO add assumptions for other goods types
    }

}

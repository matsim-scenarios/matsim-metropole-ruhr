package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public interface DemandPerDayCalculator {
    int calculateKilogramsPerDay(double tonsPerYear);

    int calculateWasteDemandPerDay(Person freightDemandDataRelation);
}

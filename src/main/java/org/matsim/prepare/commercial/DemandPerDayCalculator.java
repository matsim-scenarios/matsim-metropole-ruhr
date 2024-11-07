package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;
import org.matsim.freight.carriers.Carrier;

public interface DemandPerDayCalculator {
    int calculateKilogramsPerDay(double tonsPerYear);

    int calculateWasteDemandPerDay(Person freightDemandDataRelation);

    int calculateParcelsPerDay(int parcelsPerYear);

    int calculateNumberOfJobsForDemand(Carrier freightDemandDataRelation, int demand);
}

package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public interface DepartureTimeCalculator {
    double calculateDepartureTime(Person freightDemandDataRelation);

}

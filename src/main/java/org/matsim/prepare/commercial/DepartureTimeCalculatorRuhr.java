package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public interface DepartureTimeCalculatorRuhr {
    double calculateDepartureTime(Person freightDemandDataRelation);

}

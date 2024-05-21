package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public interface CommercialServiceTimeCalculator {

    int calculateDeliveryTime(Person freightDemandDataRelation, int demand);

    int calculatePickupTime(Person freightDemandDataRelation, int demand);
}

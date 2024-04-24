package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public interface CommercialVehicleSelector {
    String getVehicleType(Person freightDemandDataRelation);

    String getModeForTrip(Person freightDemandDataRelation);
}

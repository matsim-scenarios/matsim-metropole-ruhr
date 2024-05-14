package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.List;

public interface CommercialVehicleSelector {
    List<String> getPossibleVehicleTypes(Person freightDemandDataRelation, String string);

    String getModeForTrip(Person freightDemandDataRelation);
}

package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.List;

public interface CommercialVehicleSelector {
    String getVehicleTypeForPlan(Person freightDemandDataRelation, String carrierId);

	List<String> getPossibleVehicleTypes(Person freightDemandDataRelation, String carrierId);

    String getModeForFTLTrip(Person freightDemandDataRelation);
}

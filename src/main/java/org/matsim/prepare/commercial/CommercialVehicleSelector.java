package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;
import org.matsim.freight.carriers.CarrierCapabilities;

import java.util.List;

public interface CommercialVehicleSelector {
    String getVehicleTypeForPlan(Person freightDemandDataRelation, String carrierId);

	List<String> getPossibleVehicleTypes(Person freightDemandDataRelation, String carrierId, CarrierCapabilities.FleetSize fleetSize);

    String getModeForFTLTrip(Person freightDemandDataRelation);
}

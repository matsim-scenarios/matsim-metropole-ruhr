package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public class DefaultCommercialVehicleSelector implements CommercialVehicleSelector {

    @Override
    public String getVehicleType(Person freightDemandDataRelation) {
        if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL"))
            return "heavy40t";
        else
            if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140)
                return "waste_collection_diesel";
        return "medium18t";
    }

    @Override
    public String getModeForTrip(Person freightDemandDataRelation) {
        if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL"))
            return "truck40t";
        else
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140)
            return "truck26t";
        return "truck18t";
    }
}

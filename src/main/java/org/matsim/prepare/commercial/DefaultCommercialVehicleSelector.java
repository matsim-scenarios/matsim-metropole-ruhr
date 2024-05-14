package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.List;

public class DefaultCommercialVehicleSelector implements CommercialVehicleSelector {
    // TODO perhaps vehicleTypes to constructor to get relevant data
    @Override
    public List<String> getPossibleVehicleTypes(Person freightDemandDataRelation, String carrierId) {

        if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL"))
            return List.of("heavy40t");
        else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
            return List.of("waste_collection_diesel");
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) // parcel delivery
            if (carrierId.contains("_truck18t"))
                return List.of("medium18t_parcel");
            else
                return List.of("mercedes313_parcel");
        return List.of("medium18t");
    }

    @Override
    public String getModeForTrip(Person freightDemandDataRelation) {
        if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL"))
            return "truck40t";
        else
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140)
            return "truck26t";
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150)
            return "car"; //TODO truck18t
        return "truck18t";
    }
}

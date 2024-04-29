package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public class DefaultCommercialServiceTimeCalculator implements CommercialServiceTimeCalculator {
    /**
     * @param freightDemandDataRelation this relation data
     * @param demand                    demand of teh good
     * @return time in seconds
     */
    @Override
    public int calculateDeliveryTime(Person freightDemandDataRelation, int demand) {
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) { // parcel delivery
            int timePerParcel = 180;
            return (demand * timePerParcel);
        }
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
            return (int) ((double) demand / 11000) * 45 * 60; // assuming that the delivery time is 45 minutes (lunch break) and the vehicle is full when driving to the dump;
        return (int) (0.5 * 3600); //TODO
    }

    /**
     * @param freightDemandDataRelation this relation data
     * @return time in seconds
     */
    @Override
    public int calculatePickupTime(Person freightDemandDataRelation, int demand) {
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) { // waste collection
            double maxLoadPerBinInKilogramm = 110; //bin with 1100l and density 100kg/m^3
            int timePerBin = 41;
            return timePerBin * (int) Math.ceil(demand / maxLoadPerBinInKilogramm);
        }
        // assuming that the vehicle is already loaded and the driver only has to drive to the customer
        return 0;
    }
}

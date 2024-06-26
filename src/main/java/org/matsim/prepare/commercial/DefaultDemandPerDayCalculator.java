package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierVehicle;

import java.util.*;

/**
 * Calculates the demand per day for a given freight demand data relation.
 *
 * @Author Ricardo Ewert
 */
public class DefaultDemandPerDayCalculator implements DemandPerDayCalculator {
    private final Random rnd = new Random(1234L);
    private final int workingDays;
    private final double sample;
    private final Map<String, List<String>> countZonesForWasteCollections;
    private final Map<String, List<String>> zonesWithWasteCollectionsOnThisDay;
    private static final Logger log = LogManager.getLogger(DefaultDemandPerDayCalculator.class);


    public DefaultDemandPerDayCalculator(int workingDays, double sample) {
        this.workingDays = workingDays;
        this.sample = sample;
        this.countZonesForWasteCollections = new HashMap<>();
        this.zonesWithWasteCollectionsOnThisDay = new HashMap<>();
    }

	/**
	 * Calculate the kilograms per day.
	 *
	 * @param tonsPerYear the tons per year
	 * @return the kilograms per day
	 */
    @Override
    public int calculateKilogramsPerDay(double tonsPerYear) {
        double kilogramsPerDay = this.sample * tonsPerYear * 1000 / ((double) this.workingDays);
        kilogramsPerDay = Math.floor(kilogramsPerDay + this.rnd.nextDouble());
        return (int) kilogramsPerDay;
    }


	/**
	 * Calculate the waste demand per day.
	 * The assumption is that waste is collected once a week and is collected in 20% of the zones on each day.
	 *
	 * @param freightDemandDataRelation the freight demand data relation
	 * @return the waste demand per day
	 */
    @Override
    public int calculateWasteDemandPerDay(Person freightDemandDataRelation) {
        String destination = CommercialTrafficUtils.getDestinationLocationId(freightDemandDataRelation);
        String originCell = CommercialTrafficUtils.getOriginCell(freightDemandDataRelation);
        countZonesForWasteCollections.computeIfAbsent(destination, k -> new ArrayList<>());
        zonesWithWasteCollectionsOnThisDay.computeIfAbsent(destination, k -> new ArrayList<>());
		// add 20% of the zones to the list of zones with waste collections on this day
        if (!countZonesForWasteCollections.get(destination).contains(originCell)){
            countZonesForWasteCollections.get(destination).add(originCell);
            if (((countZonesForWasteCollections.get(destination).size() + 4) % 5) == 0){
                zonesWithWasteCollectionsOnThisDay.get(destination).add(originCell);
            }
        }
		//if zone has a waste collection on this day, calculate the demand
        if (zonesWithWasteCollectionsOnThisDay.get(destination).contains(originCell)){
			int numberOfCollectionsPerWeek = 1;
			double tonsPerYear = CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation);
			double kilogramsPerDay = tonsPerYear * 1000 / (52 * numberOfCollectionsPerWeek);
			kilogramsPerDay = Math.floor(kilogramsPerDay + this.rnd.nextDouble());
			return (int) kilogramsPerDay;
        }
        return 0;
    }

	/**
	 * Calculate the number of parcels per day.
	 *
	 * @param parcelsPerYear the number of parcels per year. This demand calculation contains no sampling. This results in a 100% demand.
	 * @return the number of parcels per day
	 */
    @Override
    public int calculateParcelsPerDay(int parcelsPerYear) {
        int deliveryDaysPerWeek = 5;
        return (int) Math.floor((double) parcelsPerYear / (52 * deliveryDaysPerWeek));
    }

	/**
	 * Calculate the number of jobs for a given demand and carrier.
	 *
	 * @param existingCarrier the carrier
	 * @param demand          the demand in kilograms
	 * @return the number of jobs
	 */
    @Override
    public int calculateNumberOfJobsForDemand(Carrier existingCarrier, int demand) {
        double largestVehicleCapacity = 0;
        for (CarrierVehicle vehicle : existingCarrier.getCarrierCapabilities().getCarrierVehicles().values()) {
            if (vehicle.getType().getCapacity().getOther() > largestVehicleCapacity) {
                largestVehicleCapacity = vehicle.getType().getCapacity().getOther();
            }
        }
        if (demand > largestVehicleCapacity) {
            log.info("Demand {} is larger than the largest vehicle capacity ({}). Splitting demand into multiple jobs.", demand, largestVehicleCapacity);
            return (int) Math.ceil((double) demand / largestVehicleCapacity);
        }
        return 1;
    }
}

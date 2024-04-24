package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class DefaultDemandPerDayCalculator implements DemandPerDayCalculator {
    private final Random rnd = new Random(1234L);
    private final int workingDays;
    private final double sample;
    private final Map<String, Integer> countWasteCollections;

    public DefaultDemandPerDayCalculator(int workingDays, double sample) {
        this.workingDays = workingDays;
        this.sample = sample;
        this.countWasteCollections = new HashMap<>();
    }

    @Override
    public int calculateKilogramsPerDay(double tonsPerYear) {
        double kilogramsPerDay = this.sample * tonsPerYear * 1000 / ((double) this.workingDays);
        kilogramsPerDay = Math.floor(kilogramsPerDay + this.rnd.nextDouble());
        return (int) kilogramsPerDay;
    }

    @Override
    public int calculateWasteDemandPerDay(Person freightDemandDataRelation) {
        String destination = CommercialTrafficUtils.getDestinationLocationId(freightDemandDataRelation);
        countWasteCollections.putIfAbsent(destination, 0);
        int numberOfCollectionsPerWeek = 1;
        double kilogramsPerDay = 0;
        // the sampling happens by reducing the number of pickups instead of reducing the demand on every pickup
        if (countWasteCollections.get(destination) == 0 || countWasteCollections.get(destination) % (1/sample) == 0){
            double tonsPerYear = CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation);
            kilogramsPerDay = tonsPerYear * 1000 / (52 * numberOfCollectionsPerWeek);
            kilogramsPerDay = Math.floor(kilogramsPerDay + this.rnd.nextDouble());
        }
        countWasteCollections.put(destination, countWasteCollections.get(destination) + 1);
        return (int) kilogramsPerDay;
    }
}

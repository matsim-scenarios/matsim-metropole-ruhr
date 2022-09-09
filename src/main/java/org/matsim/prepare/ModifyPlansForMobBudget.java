package org.matsim.prepare;

import org.apache.commons.lang3.mutable.MutableInt;
import org.matsim.analysis.TransportPlanningMainModeIdentifier;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;

public class ModifyPlansForMobBudget {


    public static void main (String args[]) {

        Population pop = PopulationUtils.readPopulation("/Users/gregorr/Desktop/hamburg-v3.0-10pct-base.plans.xml");
        TransportPlanningMainModeIdentifier transportPlanningMainModeIdentifier = new TransportPlanningMainModeIdentifier();
        PopulationFactory fac =pop.getFactory();


        for (Person p: pop.getPersons().values()) {
            if (!p.getId().toString().contains("commercial")) {
                Plan originalPlan = p.getSelectedPlan();
                Plan newPlan = PopulationUtils.createPlan(p);
                PopulationUtils.copyFromTo(originalPlan, newPlan);
                List<TripStructureUtils.Trip> tripList = TripStructureUtils.getTrips(newPlan);

                for (TripStructureUtils.Trip trip: tripList) {
                    String mainMode = transportPlanningMainModeIdentifier.identifyMainMode(trip.getTripElements());

                    if (mainMode.equals(TransportMode.car)) {
                        List<PlanElement> newTrip = List.of(fac.createLeg(TransportMode.bike));
                        TripRouter.insertTrip(newPlan,trip.getOriginActivity(), newTrip, trip.getDestinationActivity());
                    }
                }
                p.addPlan(newPlan);
            }
        }

        PopulationUtils.writePopulation(pop, "/Users/gregorr/Desktop/hamburg-v3.0-10pct-baseWithSecondPlan.plans.xml");
    }
}

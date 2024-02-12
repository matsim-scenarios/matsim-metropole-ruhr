package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.prepare.freight.tripGeneration.DefaultDepartureTimeCalculator;
import org.matsim.application.prepare.freight.tripGeneration.DefaultNumberOfTripsCalculator;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.util.ArrayList;
import java.util.List;

public class CommercialAgentGeneratorForRVR {
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.DepartureTimeCalculator departureTimeCalculator;
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.NumOfTripsCalculator numOfTripsCalculator;
    private final PopulationFactory populationFactory;

    public CommercialAgentGeneratorForRVR(double averageLoad, int workingDays, double sample) {
        this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.numOfTripsCalculator = new DefaultNumberOfTripsCalculator(averageLoad, workingDays, sample);
        this.populationFactory = PopulationUtils.getFactory();
    }

    public List<Person> generateFreightAgents(RvrTripRelation tripRelation, String tripRelationId) {
        List<Person> freightAgents = new ArrayList<>();

        int numOfTrips = numOfTripsCalculator.calculateNumberOfTrips(tripRelation.getTonsPerYear(), tripRelation.getGoodsType());
        for (int i = 1; i <= numOfTrips; i++) {

            Person person = populationFactory.createPerson(Id.createPersonId("commercial_" + tripRelationId + "_trip_" + i));
            Plan plan = populationFactory.createPlan();
            double departureTime = departureTimeCalculator.getDepartureTime();

            Activity startAct = populationFactory.createActivityFromCoord("commercial_start",
                    MGC.point2Coord(MGC.xy2Point(tripRelation.getOriginX(), tripRelation.getOriginY())));
            //TODO location not on motorway
            startAct.setEndTime(departureTime);
            startAct.setMaximumDuration(30 * 60);

            plan.addActivity(startAct);

//            Leg leg = populationFactory.createLeg("freight");
            Leg leg = populationFactory.createLeg("car");

            plan.addLeg(leg);

            Activity endAct = populationFactory.createActivityFromCoord("commercial_end",
                    MGC.point2Coord(MGC.xy2Point(tripRelation.getDestinationX(), tripRelation.getDestinationY())));
            endAct.setMaximumDuration(30 * 60);
            plan.addActivity(endAct);

            person.addPlan(plan);
            writeCommonAttributes(person, tripRelation, tripRelationId, numOfTrips);

            freightAgents.add(person);
        }

        return freightAgents;
    }

    // TODO store this attribute names as public static strings
    private void writeCommonAttributes(Person person, RvrTripRelation rvrTripRelation, String tripRelationId, int numOfTrips) {

        person.getAttributes().putAttribute("origin_cell", rvrTripRelation.getOriginCell());
        person.getAttributes().putAttribute("origin_locationId", rvrTripRelation.getOriginLocationId());
        person.getAttributes().putAttribute("destination_cell", rvrTripRelation.getDestinationCell());
        person.getAttributes().putAttribute("destination_locationId", rvrTripRelation.getDestinationLocationId());
        person.getAttributes().putAttribute("origin_x", rvrTripRelation.getOriginX());
        person.getAttributes().putAttribute("origin_y", rvrTripRelation.getOriginY());
        person.getAttributes().putAttribute("destination_x", rvrTripRelation.getDestinationX());
        person.getAttributes().putAttribute("destination_y", rvrTripRelation.getDestinationY());
        person.getAttributes().putAttribute("transportType", rvrTripRelation.getTransportType());

        person.getAttributes().putAttribute("subpopulation", "freight");
        person.getAttributes().putAttribute("trip_relation_index", tripRelationId);
        person.getAttributes().putAttribute("goods_type", rvrTripRelation.getGoodsType());
        person.getAttributes().putAttribute("tons_per_year", rvrTripRelation.getTonsPerYear());
        person.getAttributes().putAttribute("num_of_trips_perDay", numOfTrips);

    }

}


package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.prepare.freight.tripGeneration.DefaultDepartureTimeCalculator;
import org.matsim.application.prepare.freight.tripGeneration.DefaultNumberOfTripsCalculator;
import org.matsim.core.population.PopulationUtils;

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

            Activity startAct = populationFactory.createActivityFromCoord("commercial_start", new Coord(tripRelation.getOriginX(), tripRelation.getOriginY()));
            //TODO location not on motorway
            startAct.setEndTime(departureTime);
            startAct.setMaximumDuration(30 * 60);

            plan.addActivity(startAct);

            //TODO later change mode to vehicle class
            PopulationUtils.createAndAddLeg(plan, "car");

            PopulationUtils.createAndAddActivityFromCoord(plan, "commercial_end",
                    new Coord(tripRelation.getDestinationX(), tripRelation.getDestinationY())).setMaximumDuration(30 * 60);

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


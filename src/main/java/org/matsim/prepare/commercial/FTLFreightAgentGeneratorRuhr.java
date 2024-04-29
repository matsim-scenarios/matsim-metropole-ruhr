package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.prepare.freight.tripGeneration.DefaultNumberOfTripsCalculator;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.ArrayList;
import java.util.List;

public class FTLFreightAgentGeneratorRuhr {
    private final DepartureTimeCalculator departureTimeCalculator;
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.NumOfTripsCalculator numOfTripsCalculator;
    private final PopulationFactory populationFactory;
    private final CommercialVehicleSelector commercialVehicleSelector;

    public FTLFreightAgentGeneratorRuhr(double averageLoad, int workingDays, double sample) {
        this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.numOfTripsCalculator = new DefaultNumberOfTripsCalculator(averageLoad, workingDays, sample);
        this.commercialVehicleSelector = new DefaultCommercialVehicleSelector();
        this.populationFactory = PopulationUtils.getFactory();
    }

    public List<Person> generateFreightFTLAgents(Person freightDemandDataRelation, int maxKilometerForReturnJourney) {
        List<Person> createdFreightAgentsForThisRelation = new ArrayList<>();
        createFreightFTLAgentWithPlan(freightDemandDataRelation, createdFreightAgentsForThisRelation, maxKilometerForReturnJourney);

        return createdFreightAgentsForThisRelation;

    }

    /**
     * Generates all necessary freight agents for a given freight demand data relation.
     *
     * @param freightDemandDataRelation    the freight demand data relation
     * @param freightAgents                the list of freight agents to which the generated freight agents are added
     * @param maxKilometerForReturnJourney the maximum Euclidean distance to add an empty return journey at the end of FLT trip
     */
    private void createFreightFTLAgentWithPlan(Person freightDemandDataRelation, List<Person> freightAgents, int maxKilometerForReturnJourney) {
        double tonsPerYear = CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation);
        int goodsType = CommercialTrafficUtils.getGoodsType(freightDemandDataRelation);
        int numOfTrips = numOfTripsCalculator.calculateNumberOfTrips(tonsPerYear, String.valueOf(goodsType));
        String transportType = CommercialTrafficUtils.getTransportType(freightDemandDataRelation);
        String tripRelationId = CommercialTrafficUtils.getTripRelationIndex(freightDemandDataRelation);
        double originX = CommercialTrafficUtils.getOriginX(freightDemandDataRelation);
        double originY = CommercialTrafficUtils.getOriginY(freightDemandDataRelation);
        double destinationX = CommercialTrafficUtils.getDestinationX(freightDemandDataRelation);
        double destinationY = CommercialTrafficUtils.getDestinationY(freightDemandDataRelation);
        String FTL_mode = commercialVehicleSelector.getModeForTrip(freightDemandDataRelation);
        for (int i = 0; i < numOfTrips; i++) {
            Person person = populationFactory.createPerson(Id.createPersonId("freight_" + tripRelationId + "_" + i + "_" + transportType));
            double departureTime = departureTimeCalculator.calculateDepartureTime(freightDemandDataRelation);

            Plan plan = populationFactory.createPlan();
            PopulationUtils.createAndAddActivityFromCoord(plan, "freight_start", new Coord(originX, originY)).setEndTime(departureTime);

            PopulationUtils.createAndAddLeg(plan, FTL_mode);
            PopulationUtils.createAndAddActivityFromCoord(plan, "freight_end", new Coord(destinationX, destinationY)).setMaximumDuration(0.5 * 3600);
            double distanceBetweenOriginAndDestination = NetworkUtils.getEuclideanDistance(new Coord(originX, originY),
                    new Coord(destinationX, destinationY));
            if (distanceBetweenOriginAndDestination <= maxKilometerForReturnJourney * 1000) {
                PopulationUtils.createAndAddLeg(plan, FTL_mode);
                PopulationUtils.createAndAddActivityFromCoord(plan, "freight_return", new Coord(originX, originY));
            }
            person.addPlan(plan);
            freightDemandDataRelation.getAttributes().getAsMap().forEach((k, v) -> person.getAttributes().putAttribute(k, v));
            PopulationUtils.putSubpopulation(person, "FTL_trip");
            freightAgents.add(person);
        }
    }
}


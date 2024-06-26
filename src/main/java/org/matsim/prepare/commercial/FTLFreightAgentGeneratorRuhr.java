package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.prepare.freight.tripGeneration.DefaultNumberOfTripsCalculator;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates freight agents for full truck load (FTL) trips in the Ruhr area.
 *
 * @Author Ricardo Ewert
 */
public class FTLFreightAgentGeneratorRuhr {
    private final DepartureTimeCalculator departureTimeCalculator;
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.NumOfTripsCalculator numOfTripsCalculator;
    private final PopulationFactory populationFactory;
    private final CommercialVehicleSelector commercialVehicleSelector;

	/**
	 * FTLLightFreightAgentGeneratorRuhr constructor to
	 *
	 * @param averageLoad               the average load of a truck in tons
	 * @param workingDays               the number of working days per year
	 * @param sample                    the scaling factor of the freight traffic (0, 1)
	 * @param departureTimeCalculator   the departure time calculator, if null, a default implementation is used
	 * @param numOfTripsCalculator      the number of trips calculator, if null, a default implementation is used
	 * @param commercialVehicleSelector the commercial vehicle selector, if null, a default implementation is used
	 */
    public FTLFreightAgentGeneratorRuhr(double averageLoad, int workingDays, double sample, DepartureTimeCalculator departureTimeCalculator, FreightAgentGenerator.NumOfTripsCalculator numOfTripsCalculator, CommercialVehicleSelector commercialVehicleSelector) {
		this.departureTimeCalculator = Objects.requireNonNullElseGet(departureTimeCalculator, DefaultDepartureTimeCalculator::new);
		this.numOfTripsCalculator = Objects.requireNonNullElseGet(numOfTripsCalculator, () -> new DefaultNumberOfTripsCalculator(averageLoad, workingDays, sample));
		this.commercialVehicleSelector = Objects.requireNonNullElseGet(commercialVehicleSelector, DefaultCommercialVehicleSelector::new);
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
        String FTL_mode = commercialVehicleSelector.getModeForFTLTrip(freightDemandDataRelation);
        String vehicleType = commercialVehicleSelector.getVehicleTypeForPlan(freightDemandDataRelation, null);
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
            else {
                // Create a return trip for the FTL trip. This trip represents the empty return journey of the truck. Because we simulate one day, we add a return trip on the same day.
                Person person_return = populationFactory.createPerson(Id.createPersonId("freight_" + tripRelationId + "_" + i + "_" + transportType + "_return"));
                double departureTime_return = departureTimeCalculator.calculateDepartureTime(freightDemandDataRelation);

                Plan plan_return = populationFactory.createPlan();
                PopulationUtils.createAndAddActivityFromCoord(plan_return, "freight_start", new Coord(destinationX, destinationY)).setEndTime(departureTime_return);

                PopulationUtils.createAndAddLeg(plan_return, FTL_mode);
                PopulationUtils.createAndAddActivityFromCoord(plan_return, "freight_end", new Coord(originX, originY)).setMaximumDuration(0.5 * 3600);

                person_return.addPlan(plan_return);
                freightDemandDataRelation.getAttributes().getAsMap().forEach((k, v) -> person_return.getAttributes().putAttribute(k, v));
                PopulationUtils.putSubpopulation(person_return, transportType + "_trip");
                Id<VehicleType> vehicleTypeId = Id.create(vehicleType, VehicleType.class);
                VehicleUtils.insertVehicleTypesIntoPersonAttributes(person_return, Map.of(FTL_mode, vehicleTypeId));
                freightAgents.add(person_return);
            }
            person.addPlan(plan);
            freightDemandDataRelation.getAttributes().getAsMap().forEach((k, v) -> person.getAttributes().putAttribute(k, v));
            PopulationUtils.putSubpopulation(person, transportType + "_trip");
            Id<VehicleType> vehicleTypeId = Id.create(vehicleType, VehicleType.class);
            VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, Map.of(FTL_mode, vehicleTypeId));
            freightAgents.add(person);
        }
    }
}


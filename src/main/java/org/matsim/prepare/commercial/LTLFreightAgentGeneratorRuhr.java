package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.application.prepare.freight.tripGeneration.DefaultDepartureTimeCalculator;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LTLFreightAgentGeneratorRuhr {
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.DepartureTimeCalculator departureTimeCalculator;
    private final PopulationFactory populationFactory;
    private final DefaultKilogramsPerDayCalculator kilogramsPerDayCalculator;

    public LTLFreightAgentGeneratorRuhr(int workingDays, double sample) {
        this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.kilogramsPerDayCalculator = new DefaultKilogramsPerDayCalculator(workingDays, sample);
        this.populationFactory = PopulationUtils.getFactory();
    }

    public List<Person> generateFreightLTLAgents(Person freightDemandDataRelation) {
        List<Person> createdFreightAgentsForThisRelation = new ArrayList<>();
//        createFreightAgentWithPlan(freightDemandDataRelation, createdFreightAgentsForThisRelation, maxKilometerForReturnJourney);

        return createdFreightAgentsForThisRelation;

    }

    public void createCarriersForLTL(Population inputFreightDemandData, Scenario scenario) {
        Carriers carriers  = CarriersUtils.addOrGetCarriers(scenario);

        TransportModeNetworkFilter filter = new TransportModeNetworkFilter(scenario.getNetwork());
        Set<String> modes = new HashSet<>();
        modes.add("car");
        Network filteredNetwork = NetworkUtils.createNetwork(scenario.getConfig().network());
        filter.filter(filteredNetwork, modes);

        for(Person freightDemandDataRelation : inputFreightDemandData.getPersons().values()) {
            if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) continue; //TODO: wasteCollection, other oder of Start/End location
            if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(CommercialTrafficUtils.TransportType.LTL.toString())){
                Id<Carrier> carrierId = createCarrierId(freightDemandDataRelation);
                if (carriers.getCarriers().containsKey(carrierId)) {
                    Carrier existingCarrier = carriers.getCarriers().get(carrierId);
                    Id<Link> fromLinkId = existingCarrier.getShipments().values().iterator().next().getFrom();
                    addShipment(filteredNetwork, existingCarrier, freightDemandDataRelation, fromLinkId);

                }
                else {
                    Carrier newCarrier = CarriersUtils.createCarrier(carrierId);
                    CarriersUtils.setJspritIterations(newCarrier, 1);
                    Link fromLinkId = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation), CommercialTrafficUtils.getOriginY(freightDemandDataRelation)));
                    createFreightVehicles (scenario, newCarrier, fromLinkId.getId());
                    addShipment(filteredNetwork, newCarrier, freightDemandDataRelation, fromLinkId.getId());
                    carriers.addCarrier(newCarrier);
                }
            }
        }
    }

    private void addShipment(Network filteredNetwork, Carrier existingCarrier, Person freightDemandDataRelation, Id<Link> fromLinkId) {
        Link toLink = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                CommercialTrafficUtils.getDestinationY(freightDemandDataRelation)));
        if (existingCarrier.getShipments().size() > 50) return;
        int demand = kilogramsPerDayCalculator.calculateKilogramsPerDay(CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation));
        int serviceTime = (int) (0.5 * 3600);
        TimeWindow timeWindow = TimeWindow.newInstance(0, 24 * 3600);
        CarrierShipment newCarrierShipment = CarrierShipment.Builder.newInstance(
                Id.create(freightDemandDataRelation.getId().toString(), CarrierShipment.class),
                fromLinkId,
                toLink.getId(),
                demand).setPickupTimeWindow(timeWindow).setDeliveryTimeWindow(timeWindow).setPickupServiceTime(serviceTime).setDeliveryServiceTime(serviceTime).build();
        existingCarrier.getShipments().put(newCarrierShipment.getId(), newCarrierShipment);
    }

    private static void createFreightVehicles(Scenario scenario, Carrier newCarrier, Id<Link> nearestLinkOrigin) {

        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);

        CarrierCapabilities carrierCapabilities = CarrierCapabilities.Builder.newInstance().setFleetSize(
                CarrierCapabilities.FleetSize.INFINITE).build();

        int vehicleStartTime = 0 * 3600;
        int vehicleEndTime = 24 * 3600;
        VehicleType vehicleType = carrierVehicleTypes.getVehicleTypes().get(Id.create("heavy40t", VehicleType.class));
        vehicleType.setNetworkMode("car"); //TODO change later
        CarrierVehicle newCarrierVehicle = CarrierVehicle.Builder.newInstance(
                Id.create(newCarrier.getId().toString() + "_" + (carrierCapabilities.getCarrierVehicles().size() + 1), Vehicle.class),
                nearestLinkOrigin, vehicleType).setEarliestStart(vehicleStartTime).setLatestEnd(vehicleEndTime).build();
        carrierCapabilities.getCarrierVehicles().put(newCarrierVehicle.getId(), newCarrierVehicle);
        if (!carrierCapabilities.getVehicleTypes().contains(vehicleType)) carrierCapabilities.getVehicleTypes().add(vehicleType);

        newCarrier.setCarrierCapabilities(carrierCapabilities);
    }


    private static Id<Carrier> createCarrierId(Person freightDemandDataRelation) {
        return Id.create("Carrier-goodsType_" + CommercialTrafficUtils.getGoodsType(
                freightDemandDataRelation) + "-facility_" + CommercialTrafficUtils.getOriginLocationId(freightDemandDataRelation), Carrier.class);
    }
}


package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.prepare.freight.tripGeneration.DefaultDepartureTimeCalculator;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class LTLFreightAgentGeneratorRuhr {
    private final org.matsim.application.prepare.freight.tripGeneration.FreightAgentGenerator.DepartureTimeCalculator departureTimeCalculator;
    private final DefaultKilogramsPerDayCalculator kilogramsPerDayCalculator;

    public LTLFreightAgentGeneratorRuhr(int workingDays, double sample) {
        this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.kilogramsPerDayCalculator = new DefaultKilogramsPerDayCalculator(workingDays, sample);
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

    /**
     * Creates a population including the plans based on the scheduled tours of the carriers.
     * If a tour has multiple similar activities (e.g., multiple pickups at the same location), the activities are merged tp one activity.
     */
    static void createPlansBasedOnCarrierPlans(Scenario scenario, Population outputPopulation) {

        Population population = scenario.getPopulation();
        PopulationFactory popFactory = population.getFactory();

        Map<String, AtomicLong> idCounter = new HashMap<>();

        Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);

            carriers.getCarriers().values().forEach(carrier -> {
                carrier.getSelectedPlan().getScheduledTours().forEach(scheduledTour -> {
                    Plan plan = PopulationUtils.createPlan();
                    String subpopulation = "LTL_trips";
                    final String mode = "car";
                    List<Tour.TourElement> carrierScheduledPlanElements = scheduledTour.getTour().getTourElements();

                    //TODO add times
                    PopulationUtils.createAndAddActivityFromLinkId(plan, "start", scheduledTour.getTour().getStart().getLocation()).setEndTime(scheduledTour.getTour().getStart().getExpectedArrival()); //TODO check if correct
                    Id<Link> previousLocation = scheduledTour.getTour().getStart().getLocation();
                    Id<Link> lastLocationOfTour = scheduledTour.getTour().getEnd().getLocation();

                    for (int i = 0; i < carrierScheduledPlanElements.size(); i++) {

                        Tour.TourElement tourElement = carrierScheduledPlanElements.get(i);
                        if (tourElement instanceof Tour.Pickup pickup) {
                            Id<Link> linkID = pickup.getLocation();
                            Activity lastActivity = plan.getPlanElements().get(plan.getPlanElements().size() -1) instanceof Activity activity ? activity : null;
                            if (lastActivity != null && lastActivity.getType().equals("pickup") && lastActivity.getLinkId().equals(linkID)) {
                                lastActivity.setMaximumDuration(lastActivity.getMaximumDuration().seconds() + pickup.getDuration());
                            } else {
                                PopulationUtils.createAndAddActivityFromLinkId(plan, "pickup", linkID).setMaximumDuration(
                                        pickup.getDuration());
                                previousLocation = linkID;
                            }
                        }
                        if (tourElement instanceof Tour.Delivery delivery) {
                            Id<Link> linkID = delivery.getLocation();
                            Activity lastActivity = plan.getPlanElements().get(plan.getPlanElements().size() -1) instanceof Activity activity ? activity : null;
                            if (lastActivity != null && lastActivity.getType().equals("delivery") && lastActivity.getLinkId().equals(linkID)) {
                                lastActivity.setMaximumDuration(
                                        lastActivity.getMaximumDuration().seconds() + delivery.getDuration());
                            } else {
                                PopulationUtils.createAndAddActivityFromLinkId(plan, "delivery", linkID).setMaximumDuration(
                                        delivery.getDuration());
                                previousLocation = linkID;
                            }
                        }
                        if (tourElement instanceof Tour.Leg) {
                            Id<Link> nextlinkID = carrierScheduledPlanElements.size() == i + 1 ? null : carrierScheduledPlanElements.get(i + 1) instanceof Tour.TourActivity activity ? activity.getLocation() : null;
                            if (nextlinkID != null && nextlinkID.equals(previousLocation))
                                continue;
                            PopulationUtils.createAndAddLeg(plan, mode);
                        }
                    }
                    PopulationUtils.createAndAddActivityFromLinkId(plan, "end", lastLocationOfTour).setMaximumDuration(scheduledTour.getTour().getEnd().getDuration());

                    String key = carrier.getId().toString();

                    long id = idCounter.computeIfAbsent(key, (k) -> new AtomicLong()).getAndIncrement();

                    Person newPerson = popFactory.createPerson(Id.createPersonId(key + "_" + id));

                    newPerson.addPlan(plan);
                    PopulationUtils.putSubpopulation(newPerson, subpopulation);

                    Id<Vehicle> vehicleId = scheduledTour.getVehicle().getId();

                    VehicleUtils.insertVehicleIdsIntoPersonAttributes(newPerson, Map.of(mode, vehicleId));
                    Id<VehicleType> vehicleTypeId = scheduledTour.getVehicle().getType().getId();
                    VehicleUtils.insertVehicleTypesIntoPersonAttributes(newPerson, Map.of(mode, vehicleTypeId));

                    outputPopulation.addPerson(newPerson);

                });
            });
        scenario.getPopulation().getPersons().clear(); //TODO check if needed
    }
    private static Id<Carrier> createCarrierId(Person freightDemandDataRelation) {
        return Id.create("Carrier-goodsType_" + CommercialTrafficUtils.getGoodsType(
                freightDemandDataRelation) + "-facility_" + CommercialTrafficUtils.getOriginLocationId(freightDemandDataRelation), Carrier.class);
    }
}


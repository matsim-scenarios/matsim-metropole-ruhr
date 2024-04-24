package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
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
    private static DepartureTimeCalculatorRuhr departureTimeCalculator;
    private static DemandPerDayCalculator demandPerDayCalculator;
    private static CommercialVehicleSelector commercialVehicleSelector;

    public LTLFreightAgentGeneratorRuhr(int workingDays, double sample) {
        departureTimeCalculator = new DefaultDepartureTimeCalculatorRuhr();
        demandPerDayCalculator = new DefaultDemandPerDayCalculator(workingDays, sample);
        commercialVehicleSelector = new DefaultCommercialVehicleSelector();
    }

    private static void createFreightVehicles(Scenario scenario, Carrier newCarrier, Id<Link> nearestLinkOrigin, Person freightDemandDataRelation) {

        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);

        CarrierCapabilities carrierCapabilities = CarrierCapabilities.Builder.newInstance().setFleetSize(
                CarrierCapabilities.FleetSize.INFINITE).build();

        int earliestVehicleStartTime = (int) departureTimeCalculator.calculateDepartureTime(freightDemandDataRelation);
        int latestVehicleEndTime = earliestVehicleStartTime + 10 * 3600; //assumption that working only max 10hours
        String vehicleTypeString = commercialVehicleSelector.getVehicleType(freightDemandDataRelation);
        VehicleType vehicleType = carrierVehicleTypes.getVehicleTypes().get(Id.create(vehicleTypeString, VehicleType.class));
        CarrierVehicle newCarrierVehicle = CarrierVehicle.Builder.newInstance(
                Id.create(newCarrier.getId().toString() + "_" + (carrierCapabilities.getCarrierVehicles().size() + 1), Vehicle.class),
                nearestLinkOrigin, vehicleType).setEarliestStart(earliestVehicleStartTime).setLatestEnd(latestVehicleEndTime).build();
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
                String mode = scheduledTour.getVehicle().getType().getNetworkMode();
                List<Tour.TourElement> carrierScheduledPlanElements = scheduledTour.getTour().getTourElements();

                //TODO add times
                PopulationUtils.createAndAddActivityFromLinkId(plan, "start", scheduledTour.getTour().getStart().getLocation()).setEndTime(
                        scheduledTour.getTour().getStart().getExpectedArrival()); //TODO check if correct
                Id<Link> previousLocation = scheduledTour.getTour().getStart().getLocation();
                Id<Link> lastLocationOfTour = scheduledTour.getTour().getEnd().getLocation();

                for (int i = 0; i < carrierScheduledPlanElements.size(); i++) {

                    Tour.TourElement tourElement = carrierScheduledPlanElements.get(i);
                    if (tourElement instanceof Tour.Pickup pickup) {
                        Id<Link> linkID = pickup.getLocation();
                        Activity lastActivity = plan.getPlanElements().get(
                                plan.getPlanElements().size() - 1) instanceof Activity activity ? activity : null;
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
                        Activity lastActivity = plan.getPlanElements().get(
                                plan.getPlanElements().size() - 1) instanceof Activity activity ? activity : null;
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
                        Id<Link> nextlinkID = carrierScheduledPlanElements.size() == i + 1 ? null : carrierScheduledPlanElements.get(
                                i + 1) instanceof Tour.TourActivity activity ? activity.getLocation() : null;
                        if (nextlinkID != null && nextlinkID.equals(previousLocation))
                            continue;
                        PopulationUtils.createAndAddLeg(plan, mode);
                    }
                }
                PopulationUtils.createAndAddActivityFromLinkId(plan, "end", lastLocationOfTour).setMaximumDuration(
                        scheduledTour.getTour().getEnd().getDuration());

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
    }

    private static Id<Carrier> createCarrierId(Person freightDemandDataRelation) {

        int goodsType = CommercialTrafficUtils.getGoodsType(freightDemandDataRelation);
        if (goodsType == 140)
            return Id.create("Carrier-wasteCollection-facility_" + CommercialTrafficUtils.getDestinationLocationId(freightDemandDataRelation),
                    Carrier.class);
        return Id.create("Carrier-goodsType_" + goodsType + "-facility_" + CommercialTrafficUtils.getOriginLocationId(freightDemandDataRelation),
                Carrier.class);
    }

    public void createCarriersForLTL(Population inputFreightDemandData, Scenario scenario, int jspritIterationsForLTL) {
        Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);

        TransportModeNetworkFilter filter = new TransportModeNetworkFilter(scenario.getNetwork());
        Set<String> modes = new HashSet<>();
        modes.add("car"); // TODO adjust for other modes
        Network filteredNetwork = NetworkUtils.createNetwork(scenario.getConfig().network());
        filter.filter(filteredNetwork, modes);

        for (Person freightDemandDataRelation : inputFreightDemandData.getPersons().values()) {
            if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(CommercialTrafficUtils.TransportType.LTL.toString())) {
                Id<Carrier> carrierId = createCarrierId(freightDemandDataRelation);
                if (carriers.getCarriers().containsKey(carrierId) ) {
                    Carrier existingCarrier = carriers.getCarriers().get(carrierId);
                    if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) != 140) {
                        Id<Link> fromLinkId;
                        if (existingCarrier.getShipments().isEmpty()) {
                            fromLinkId = NetworkUtils.getNearestLink(filteredNetwork,
                                    new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation),
                                            CommercialTrafficUtils.getOriginY(freightDemandDataRelation))).getId();
                        } else
                            fromLinkId = existingCarrier.getShipments().values().iterator().next().getFrom();
                        addShipment(filteredNetwork, existingCarrier, freightDemandDataRelation, fromLinkId, null);
                    } else { //waste collection
                        Id<Link> toLinkId;
                        if (existingCarrier.getShipments().isEmpty()) {
                            toLinkId = NetworkUtils.getNearestLink(filteredNetwork,
                                    new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                                            CommercialTrafficUtils.getDestinationY(freightDemandDataRelation))).getId();
                        } else
                            toLinkId = existingCarrier.getShipments().values().iterator().next().getTo();
                        addShipment(filteredNetwork, existingCarrier, freightDemandDataRelation, null, toLinkId);
                    }

                } else {
                    Carrier newCarrier = CarriersUtils.createCarrier(carrierId);
                    CarriersUtils.setJspritIterations(newCarrier, jspritIterationsForLTL);
                    Link vehicleLocation;
                    if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) != 140) {
                        vehicleLocation = NetworkUtils.getNearestLink(filteredNetwork,
                                new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation),
                                        CommercialTrafficUtils.getOriginY(freightDemandDataRelation)));
                        addShipment(filteredNetwork, newCarrier, freightDemandDataRelation, vehicleLocation.getId(), null);
                    }
                    else { //waste collection
                        vehicleLocation = NetworkUtils.getNearestLink(filteredNetwork,
                                new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                                        CommercialTrafficUtils.getDestinationY(freightDemandDataRelation)));
                        addShipment(filteredNetwork, newCarrier, freightDemandDataRelation, null, vehicleLocation.getId());

                    }
                    createFreightVehicles(scenario, newCarrier, vehicleLocation.getId(), freightDemandDataRelation);
                    carriers.addCarrier(newCarrier);
                }
            }
        }
    }

    private void addShipment(Network filteredNetwork, Carrier existingCarrier, Person freightDemandDataRelation, Id<Link> fromLinkId,
                             Id<Link> toLinkId) {
        CarrierShipment newCarrierShipment;

        if (toLinkId == null) {
            Link toLink = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                    CommercialTrafficUtils.getDestinationY(freightDemandDataRelation)));
            int demand = demandPerDayCalculator.calculateKilogramsPerDay(CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation));
            if (demand == 0) return;
            int serviceTime = (int) (0.5 * 3600); //TODO
            TimeWindow timeWindow = TimeWindow.newInstance(8 * 3600, 18 * 3600); //TODO
            newCarrierShipment = CarrierShipment.Builder.newInstance(
                    Id.create(freightDemandDataRelation.getId().toString(), CarrierShipment.class),
                    fromLinkId,
                    toLink.getId(),
                    demand).setPickupTimeWindow(timeWindow).setDeliveryTimeWindow(timeWindow).setPickupServiceTime(
                    serviceTime).setDeliveryServiceTime(serviceTime).build();

        } else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) { //waste collection
            Link fromLink = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation),
                    CommercialTrafficUtils.getOriginY(freightDemandDataRelation)));
            List<CarrierShipment> existingShipments = existingCarrier.getShipments().values().stream().filter(
                    carrierShipment -> carrierShipment.getTo().equals(toLinkId) && carrierShipment.getFrom().equals(fromLink.getId())).toList();
            int volumeWaste = demandPerDayCalculator.calculateWasteDemandPerDay(freightDemandDataRelation);
            if (volumeWaste == 0) return;
            double maxLoadPerBinInKilogramm = 110; //bin with 1100l and density 100kg/m^3
            int timePerBin = 41;
            double collectionTimeForBins = timePerBin * (int) Math.ceil(volumeWaste / maxLoadPerBinInKilogramm);
            double deliveryTime = ((double) volumeWaste / 11000) * 45 * 60; // assuming that the delivery time is 45 minutes (lunch break) and the vehicle is full when driving to the dump
            TimeWindow timeWindow = TimeWindow.newInstance(6 * 3600, 16 * 3600);

            // combines waste collections from/to the same locations to one shipment
            if (!existingShipments.isEmpty()) {
                CarrierShipment existingShipment = existingCarrier.getShipments().get(existingShipments.get(0).getId());
                collectionTimeForBins = collectionTimeForBins + existingShipment.getPickupServiceTime();
                volumeWaste = volumeWaste + existingShipment.getSize();
                deliveryTime = deliveryTime + existingShipment.getDeliveryServiceTime();
                existingCarrier.getShipments().remove(existingShipment.getId());
            }

            newCarrierShipment = CarrierShipment.Builder.newInstance(
                    Id.create(freightDemandDataRelation.getId().toString(), CarrierShipment.class),
                    fromLink.getId(),
                    toLinkId,
                    volumeWaste).setPickupTimeWindow(timeWindow).setDeliveryTimeWindow(timeWindow).setPickupServiceTime(
                    collectionTimeForBins).setDeliveryServiceTime(deliveryTime).build();
        } else
            throw new RuntimeException("This should not happen");

        existingCarrier.getShipments().put(newCarrierShipment.getId(), newCarrierShipment);
    }
}


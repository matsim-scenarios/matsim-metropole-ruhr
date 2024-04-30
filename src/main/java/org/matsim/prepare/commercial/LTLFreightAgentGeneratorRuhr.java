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

/**
 * Generates LTL freight agents for the Ruhr area.
 *
 * @author Ricardo Ewert
 */
public class LTLFreightAgentGeneratorRuhr {
    private static DepartureTimeCalculator departureTimeCalculator;
    private static DemandPerDayCalculator demandPerDayCalculator;
    private static CommercialVehicleSelector commercialVehicleSelector;
    private static CommercialServiceTimeCalculator commercialServiceTimeCalculator;
    private static double sample;

    public LTLFreightAgentGeneratorRuhr(int workingDays, double sample) {
        departureTimeCalculator = new DefaultDepartureTimeCalculator();
        demandPerDayCalculator = new DefaultDemandPerDayCalculator(workingDays, sample);
        commercialVehicleSelector = new DefaultCommercialVehicleSelector();
        commercialServiceTimeCalculator = new DefaultCommercialServiceTimeCalculator();
        this.sample = sample;
    }

    /**
     * Creates the CarrierVehicle for a carrier.
     *
     * @param scenario          scenario
     * @param newCarrier        carrier
     * @param nearestLinkOrigin link of the depot
     */
    private static void createFreightVehicles(Scenario scenario, Carrier newCarrier, Id<Link> nearestLinkOrigin, Person freightDemandDataRelation) {

        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);

        CarrierCapabilities carrierCapabilities = CarrierCapabilities.Builder.newInstance().setFleetSize(
                CarrierCapabilities.FleetSize.INFINITE).build();

        int earliestVehicleStartTime = (int) departureTimeCalculator.calculateDepartureTime(freightDemandDataRelation);
        int latestVehicleEndTime = earliestVehicleStartTime + 9 * 3600; //assumption that working only max 9hours
        List<String> possibleVehicleTypeIds = commercialVehicleSelector.getPossibleVehicleTypes(freightDemandDataRelation,
                newCarrier.getId().toString());
        possibleVehicleTypeIds.forEach(vehicleTypeId -> {
            VehicleType vehicleType = carrierVehicleTypes.getVehicleTypes().get(Id.create(vehicleTypeId, VehicleType.class));
            // Waste collection and parcel delivery tours we will not sample. That's why we adjust the pcu equivalents
            if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140 || CommercialTrafficUtils.getGoodsType(
                    freightDemandDataRelation) == 150)
                vehicleType.setPcuEquivalents(vehicleType.getPcuEquivalents() * sample);
            CarrierVehicle newCarrierVehicle = CarrierVehicle.Builder.newInstance(
                    Id.create(newCarrier.getId().toString() + "_" + (carrierCapabilities.getCarrierVehicles().size() + 1), Vehicle.class),
                    nearestLinkOrigin, vehicleType).setEarliestStart(earliestVehicleStartTime).setLatestEnd(latestVehicleEndTime).build();
            carrierCapabilities.getCarrierVehicles().put(newCarrierVehicle.getId(), newCarrierVehicle);
            if (!carrierCapabilities.getVehicleTypes().contains(vehicleType)) carrierCapabilities.getVehicleTypes().add(vehicleType);
        });
        newCarrier.setCarrierCapabilities(carrierCapabilities);
    }

    /**
     * Creates a population including the plans based on the scheduled tours of the carriers.
     * If a tour has multiple similar activities (e.g., multiple pickups at the same location), the activities are merged tp one activity.
     */
    static void createPlansBasedOnCarrierPlans(Scenario scenario, Population outputPopulation) {

        Population population = scenario.getPopulation();
        PopulationFactory popFactory = population.getFactory();

        Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);

        carriers.getCarriers().values().forEach(carrier -> {
            carrier.getSelectedPlan().getScheduledTours().forEach(scheduledTour -> {
                Plan plan = PopulationUtils.createPlan();
                String subpopulation = "LTL_trips";
                String mode = scheduledTour.getVehicle().getType().getNetworkMode();
                List<Tour.TourElement> carrierScheduledPlanElements = scheduledTour.getTour().getTourElements();

                PopulationUtils.createAndAddActivityFromLinkId(plan, "start", scheduledTour.getTour().getStart().getLocation()).setEndTime(
                        scheduledTour.getDeparture());
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

                Person newPerson = popFactory.createPerson(Id.createPersonId(key + "_" + scheduledTour.getTour().getId().toString()));

                newPerson.addPlan(plan);
                PopulationUtils.putSubpopulation(newPerson, subpopulation);
                newPerson.getAttributes().putAttribute("goodsType", carrier.getAttributes().getAttribute("goodsType"));

                Id<Vehicle> vehicleId = scheduledTour.getVehicle().getId();

                VehicleUtils.insertVehicleIdsIntoPersonAttributes(newPerson, Map.of(mode, vehicleId));
                Id<VehicleType> vehicleTypeId = scheduledTour.getVehicle().getType().getId();
                VehicleUtils.insertVehicleTypesIntoPersonAttributes(newPerson, Map.of(mode, vehicleTypeId));

                outputPopulation.addPerson(newPerson);

            });
        });
    }

    /**
     * Creates a carrier id based on the freight demand data.
     *
     * @return Creates a carrier id based on the freight demand data.
     */
    private static Id<Carrier> createCarrierId(Person freightDemandDataRelation) {

        int goodsType = CommercialTrafficUtils.getGoodsType(freightDemandDataRelation);

        // waste collection; assuming that the collection teams have service areas based on the vpp2040 cells -> per collection cell a separate carrier
        if (goodsType == 140) {
            String collectionZone = CommercialTrafficUtils.getOriginCell(freightDemandDataRelation);
            return Id.create(
                    "WasteCollection_Zone_" + collectionZone + "_depot_" + CommercialTrafficUtils.getDestinationLocationId(freightDemandDataRelation),
                    Carrier.class);
        }
        // parcel delivery; assuming that the delivery teams have service areas based on the vpp2040 cells -> per delivery cell a separate carrier
        if (goodsType == 150) {
            String deliveryZone = CommercialTrafficUtils.getDestinationCell(freightDemandDataRelation);
            String key = "ParcelDelivery_" + CommercialTrafficUtils.getParcelOperator(
                    freightDemandDataRelation) + "_Hub_" + CommercialTrafficUtils.getParcelHubId(freightDemandDataRelation);
            // here I assume that locations (100x100) with a demand of 200 parcels per day or more are served by a 26t truck, because they are commercial costumers
            if (demandPerDayCalculator.calculateParcelsPerDay(CommercialTrafficUtils.getParcelsPerYear(freightDemandDataRelation)) >= 200)
                return Id.create(key + "_truck18t", Carrier.class);
            else
                return Id.create(key + "_zone_" + deliveryZone, Carrier.class);
        }
        return Id.create("GoodsType_" + goodsType + "_facility_" + CommercialTrafficUtils.getOriginLocationId(freightDemandDataRelation),
                Carrier.class);
    }

    /**
     * Creates all carriers for LTL freight agents based on the freight demand data.
     *
     * @param inputFreightDemandData freight demand data
     * @param scenario               scenario
     * @param jspritIterationsForLTL number of iterations for the jsprit algorithm to solve the LTL carriers
     */
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
                if (carriers.getCarriers().containsKey(carrierId)) {
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
                    newCarrier.getAttributes().putAttribute("goodsType", CommercialTrafficUtils.getGoodsType(freightDemandDataRelation));
                    Link vehicleLocation;
                    if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) != 140) {
                        vehicleLocation = NetworkUtils.getNearestLink(filteredNetwork,
                                new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation),
                                        CommercialTrafficUtils.getOriginY(freightDemandDataRelation)));
                        createFreightVehicles(scenario, newCarrier, vehicleLocation.getId(), freightDemandDataRelation);
                        addShipment(filteredNetwork, newCarrier, freightDemandDataRelation, vehicleLocation.getId(), null);
                    } else { //waste collection
                        vehicleLocation = NetworkUtils.getNearestLink(filteredNetwork,
                                new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                                        CommercialTrafficUtils.getDestinationY(freightDemandDataRelation)));
                        createFreightVehicles(scenario, newCarrier, vehicleLocation.getId(), freightDemandDataRelation);
                        addShipment(filteredNetwork, newCarrier, freightDemandDataRelation, null, vehicleLocation.getId());
                    }
                    if (!newCarrier.getShipments().isEmpty())
                        carriers.addCarrier(newCarrier);
                }
            }
        }
    }

    /**
     * Creates all shipments for a given freight demand data relation.
     *
     * @param fromLinkId if null, we assume a waste collection, because we know the delivery location (dump), but we have different pickup locations
     * @param toLinkId   if null, we assume a delivery, because we know the pickup location (hub), but we have different delivery locations
     */
    private void addShipment(Network filteredNetwork, Carrier existingCarrier, Person freightDemandDataRelation, Id<Link> fromLinkId,
                             Id<Link> toLinkId) {
        CarrierShipment newCarrierShipment;

        if (toLinkId == null) {
            Link toLink = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getDestinationX(freightDemandDataRelation),
                    CommercialTrafficUtils.getDestinationY(freightDemandDataRelation)));
            int demand;
            if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) //parcel delivery
                demand = demandPerDayCalculator.calculateParcelsPerDay(CommercialTrafficUtils.getParcelsPerYear(freightDemandDataRelation));
            else
                demand = demandPerDayCalculator.calculateKilogramsPerDay(CommercialTrafficUtils.getTonsPerYear(freightDemandDataRelation));
            if (demand == 0) return;
            int numberOfJobsForDemand = demandPerDayCalculator.calculateNumberOfJobsForDemand(existingCarrier, demand);

            for (int i = 0; i < numberOfJobsForDemand; i++) {
                int demandThisJob = demand / numberOfJobsForDemand;
                int pickupTime = commercialServiceTimeCalculator.calculatePickupTime(freightDemandDataRelation, demandThisJob);
                int deliveryTime = commercialServiceTimeCalculator.calculateDeliveryTime(freightDemandDataRelation, demandThisJob);
                TimeWindow pickupTimeWindow = TimeWindow.newInstance(8 * 3600, 18 * 3600); //TODO
                TimeWindow deliveryTimeWindow = TimeWindow.newInstance(8 * 3600, 18 * 3600); //TODO

                newCarrierShipment = CarrierShipment.Builder.newInstance(
                        Id.create(freightDemandDataRelation.getId().toString(), CarrierShipment.class),
                        fromLinkId,
                        toLink.getId(),
                        demandThisJob).setPickupTimeWindow(pickupTimeWindow).setDeliveryTimeWindow(deliveryTimeWindow).setPickupServiceTime(
                        pickupTime).setDeliveryServiceTime(deliveryTime).build();
                existingCarrier.getShipments().put(newCarrierShipment.getId(), newCarrierShipment);
            }
        } else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) { //waste collection
            Link fromLink = NetworkUtils.getNearestLink(filteredNetwork, new Coord(CommercialTrafficUtils.getOriginX(freightDemandDataRelation),
                    CommercialTrafficUtils.getOriginY(freightDemandDataRelation)));

            int volumeWaste = demandPerDayCalculator.calculateWasteDemandPerDay(freightDemandDataRelation);
            if (volumeWaste == 0) return;
            int numberOfJobsForDemand = demandPerDayCalculator.calculateNumberOfJobsForDemand(existingCarrier, volumeWaste);

            for (int i = 0; i < numberOfJobsForDemand; i++) {
                int wasteThisJob = volumeWaste / numberOfJobsForDemand;
                double collectionTimeForBins = commercialServiceTimeCalculator.calculatePickupTime(freightDemandDataRelation, wasteThisJob);
                int deliveryTime = commercialServiceTimeCalculator.calculateDeliveryTime(freightDemandDataRelation, wasteThisJob);
                TimeWindow timeWindow = TimeWindow.newInstance(6 * 3600, 16 * 3600);

                // combines waste collections from/to the same locations to one shipment
                List<CarrierShipment> existingShipments = existingCarrier.getShipments().values().stream().filter(
                        carrierShipment -> carrierShipment.getTo().equals(toLinkId) && carrierShipment.getFrom().equals(fromLink.getId())).toList();
                if (!existingShipments.isEmpty()) {
                    CarrierShipment existingShipment = existingCarrier.getShipments().get(existingShipments.get(0).getId());
                    // checks if the waste collection can be combined with the existing shipment and will not exceed the vehicle capacity
                    if (demandPerDayCalculator.calculateNumberOfJobsForDemand(existingCarrier, wasteThisJob + existingShipment.getSize()) == 1) {
                        collectionTimeForBins = collectionTimeForBins + existingShipment.getPickupServiceTime();
                        wasteThisJob = wasteThisJob + existingShipment.getSize();
                        deliveryTime = deliveryTime + (int) existingShipment.getDeliveryServiceTime();
                        existingCarrier.getShipments().remove(existingShipment.getId());
                    }
                }

                newCarrierShipment = CarrierShipment.Builder.newInstance(
                        Id.create(freightDemandDataRelation.getId().toString() + "_" + i, CarrierShipment.class),
                        fromLink.getId(),
                        toLinkId,
                        wasteThisJob).setPickupTimeWindow(timeWindow).setDeliveryTimeWindow(timeWindow).setPickupServiceTime(
                        collectionTimeForBins).setDeliveryServiceTime(deliveryTime).build();
                existingCarrier.getShipments().put(newCarrierShipment.getId(), newCarrierShipment);
            }
        } else
            throw new RuntimeException("This should not happen");

    }
}


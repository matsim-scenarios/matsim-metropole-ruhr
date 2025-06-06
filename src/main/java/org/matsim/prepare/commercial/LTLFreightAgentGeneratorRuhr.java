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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates LTL freight agents for the Ruhr area.
 *
 * @author Ricardo Ewert
 */
public class LTLFreightAgentGeneratorRuhr {
    private final DepartureTimeCalculator departureTimeCalculator;
    private final DemandPerDayCalculator demandPerDayCalculator;
    private final CommercialVehicleSelector commercialVehicleSelector;
    private final CommercialServiceTimeCalculator commercialServiceTimeCalculator;
    private static double sample;

	public LTLFreightAgentGeneratorRuhr(int workingDays, double sample, DepartureTimeCalculator departureTimeCalculator,
										DemandPerDayCalculator demandPerDayCalculator, CommercialVehicleSelector commercialVehicleSelector,
										CommercialServiceTimeCalculator commercialServiceTimeCalculator) {
		this.departureTimeCalculator = Objects.requireNonNullElseGet(departureTimeCalculator, DefaultDepartureTimeCalculator::new);
		this.commercialVehicleSelector = Objects.requireNonNullElseGet(commercialVehicleSelector, DefaultCommercialVehicleSelector::new);
		this.commercialServiceTimeCalculator = Objects.requireNonNullElseGet(commercialServiceTimeCalculator, DefaultCommercialServiceTimeCalculator::new);
		this.demandPerDayCalculator = Objects.requireNonNullElseGet(demandPerDayCalculator, () -> new DefaultDemandPerDayCalculator(workingDays, sample));
		this.sample = sample;
	}

    /**
     * Creates the CarrierVehicle for a carrier.
     *
     * @param scenario          scenario
     * @param newCarrier        carrier
     * @param nearestLinkOrigin link of the depot
     */
    private void createFreightVehicles(Scenario scenario, Carrier newCarrier, Id<Link> nearestLinkOrigin, Person freightDemandDataRelation) {

        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);

        CarrierCapabilities carrierCapabilities = CarrierCapabilities.Builder.newInstance().setFleetSize(
                CarrierCapabilities.FleetSize.INFINITE).build();

        int earliestVehicleStartTime = (int) departureTimeCalculator.calculateDepartureTime(freightDemandDataRelation);
        int latestVehicleEndTime = earliestVehicleStartTime + 9 * 3600; //assumption that working only max 9hours
        List<String> possibleVehicleTypeIds = commercialVehicleSelector.getPossibleVehicleTypes(freightDemandDataRelation,
                newCarrier.getId().toString(), carrierCapabilities.getFleetSize());
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

        // counting the total number of tours for waste collections and parcel delivery, because the carrier is for both goods types a 100% demand
        int totalToursForWasteCollections = carriers.getCarriers().values().stream().mapToInt(carrier -> {
            if ((int) carrier.getAttributes().getAttribute("goodsType") == 140)
                return carrier.getSelectedPlan().getScheduledTours().size();
            return 0;
        }).sum();
        int totalToursForParcelDelivery = carriers.getCarriers().values().stream().mapToInt(carrier -> {
            if ((int) carrier.getAttributes().getAttribute("goodsType") == 150)
                return carrier.getSelectedPlan().getScheduledTours().size();
            return 0;
        }).sum();

        int sampledToursForWasteCollections = (int) Math.round(totalToursForWasteCollections * sample);
        int sampledToursForParcelDelivery = (int) Math.round(totalToursForParcelDelivery * sample);
        AtomicReference<Double> wasteCollectionRoundingError = new AtomicReference<>((double) 0);
        AtomicReference<Double> parcelDeliveryRoundingError = new AtomicReference<>((double) 0);
        AtomicInteger integratedToursForWasteCollections = new AtomicInteger();
        AtomicInteger integratedToursForParcelDelivery = new AtomicInteger();

		Network network = scenario.getNetwork();
        // Sample tours of waste collections and parcel delivery to the scenario sample size.
        // Therefore, use an error to add a tour if the error is >1
        carriers.getCarriers().values().forEach(carrier -> {
            AtomicInteger sampledToursForThisCarrier = new AtomicInteger();
            AtomicInteger integratedToursForThisCarrier = new AtomicInteger();
            int goodsType = (int) carrier.getAttributes().getAttribute("goodsType");
            if ( goodsType == 140 || goodsType == 150) {
                sampledToursForThisCarrier.set((int) Math.round(carrier.getSelectedPlan().getScheduledTours().size() * sample));
                if (goodsType == 140)
                    wasteCollectionRoundingError.getAndAccumulate((double) sampledToursForThisCarrier.get() - (carrier.getSelectedPlan().getScheduledTours().size() * sample),
                            Double::sum);
                if (goodsType == 150)
                    parcelDeliveryRoundingError.getAndAccumulate((double) sampledToursForThisCarrier.get() - (carrier.getSelectedPlan().getScheduledTours().size() * sample),
                            Double::sum);
            } else {
                // if the carrier is not for waste collections or parcel delivery, we use all tours
                sampledToursForThisCarrier.set(Integer.MIN_VALUE);
            }
            carrier.getSelectedPlan().getScheduledTours().forEach(scheduledTour -> {
                // if the rounding error is >1, we add this tour
                if (Math.abs(wasteCollectionRoundingError.get()) >= 1.) {
                    if (wasteCollectionRoundingError.get() > 0) {
                        wasteCollectionRoundingError.getAndAccumulate(-1., Double::sum);
                        sampledToursForThisCarrier.getAndDecrement();
                    } else {
                        wasteCollectionRoundingError.getAndAccumulate(1., Double::sum);
                        sampledToursForThisCarrier.getAndIncrement();
                    }
                }
                else if (Math.abs(parcelDeliveryRoundingError.get()) >= 1.) {
                    if (parcelDeliveryRoundingError.get() > 0) {
                        parcelDeliveryRoundingError.getAndAccumulate(-1., Double::sum);
                        sampledToursForThisCarrier.getAndDecrement();
                    } else {
                        parcelDeliveryRoundingError.getAndAccumulate(1., Double::sum);
                        sampledToursForThisCarrier.getAndIncrement();
                    }
                }
                // is the number of sampled tours for this carrier reached, we ignore the rest. If the carrier is not for waste collections or parcel delivery, we use all tours
                if (sampledToursForThisCarrier.get() == integratedToursForThisCarrier.get())
                    return;

                integratedToursForThisCarrier.getAndIncrement();
                if (goodsType == 140) {
                    integratedToursForWasteCollections.getAndIncrement();
                }
                if (goodsType == 150) {
                    integratedToursForParcelDelivery.getAndIncrement();
                }
                Plan plan = PopulationUtils.createPlan();
                String subpopulation = "LTL_trips";
                String mode = scheduledTour.getVehicle().getType().getNetworkMode();
                List<Tour.TourElement> carrierScheduledPlanElements = scheduledTour.getTour().getTourElements();

                PopulationUtils.createAndAddActivityFromCoord(plan, "start", network.getLinks().get(scheduledTour.getTour().getStart().getLocation()).getCoord()).setEndTime(
                        scheduledTour.getDeparture());
                Id<Link> previousLocation = scheduledTour.getTour().getStart().getLocation();
                Id<Link> lastLocationOfTour = scheduledTour.getTour().getEnd().getLocation();

                for (int i = 0; i < carrierScheduledPlanElements.size(); i++) {

                    Tour.TourElement tourElement = carrierScheduledPlanElements.get(i);
                    if (tourElement instanceof Tour.Pickup pickup) {
                        Id<Link> linkID = pickup.getLocation();
                        Activity lastActivity = plan.getPlanElements().getLast(
						) instanceof Activity activity ? activity : null;
                        if (lastActivity != null && lastActivity.getType().equals("pickup") && lastActivity.getLinkId().equals(linkID)) {
                            lastActivity.setMaximumDuration(lastActivity.getMaximumDuration().seconds() + pickup.getDuration());
                        } else {
                            PopulationUtils.createAndAddActivityFromCoord(plan, "pickup", network.getLinks().get(linkID).getCoord()).setMaximumDuration(
                                    pickup.getDuration());
                            previousLocation = linkID;
                        }
                    }
                    if (tourElement instanceof Tour.Delivery delivery) {
                        Id<Link> linkID = delivery.getLocation();
                        Activity lastActivity = plan.getPlanElements().getLast(
						) instanceof Activity activity ? activity : null;
                        if (lastActivity != null && lastActivity.getType().equals("delivery") && lastActivity.getLinkId().equals(linkID)) {
                            lastActivity.setMaximumDuration(
                                    lastActivity.getMaximumDuration().seconds() + delivery.getDuration());
                        } else {
                            PopulationUtils.createAndAddActivityFromCoord(plan, "delivery", network.getLinks().get(linkID).getCoord()).setMaximumDuration(
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
                PopulationUtils.createAndAddActivityFromCoord(plan, "end", network.getLinks().get(lastLocationOfTour).getCoord()).setMaximumDuration(
                        scheduledTour.getTour().getEnd().getDuration());

                String key = carrier.getId().toString();

                Person newPerson = popFactory.createPerson(Id.createPersonId(key + "_" + scheduledTour.getTour().getId().toString()));

                newPerson.addPlan(plan);
                PopulationUtils.putSubpopulation(newPerson, subpopulation);
                newPerson.getAttributes().putAttribute("goodsType", goodsType);

                Id<Vehicle> vehicleId = scheduledTour.getVehicle().getId();

                VehicleUtils.insertVehicleIdsIntoPersonAttributes(newPerson, Map.of(mode, vehicleId));
                Id<VehicleType> vehicleTypeId = scheduledTour.getVehicle().getType().getId();
                VehicleUtils.insertVehicleTypesIntoPersonAttributes(newPerson, Map.of(mode, vehicleTypeId));

                outputPopulation.addPerson(newPerson);

            });
        });
		// because of rounding errors of each carrier, the number of integrated tours for waste collections and parcel delivery should differ at maximum by 1
        if (Math.abs(sampledToursForWasteCollections - integratedToursForWasteCollections.get()) > 1)
            throw new RuntimeException("The number of integrated tours for waste collections does not match the sampled tours");
        if (Math.abs(sampledToursForParcelDelivery - integratedToursForParcelDelivery.get()) > 1)
            throw new RuntimeException("The number of integrated tours for parcel delivery does not match the sampled tours");
    }

    /**
     * Creates a carrier id based on the freight demand data.
     *
     * @return Creates a carrier id based on the freight demand data.
     */
    private Id<Carrier> createCarrierId(Person freightDemandDataRelation) {

        int goodsType = CommercialTrafficUtils.getGoodsType(freightDemandDataRelation);

        // waste collection, assuming that the collection teams have service areas based on the vpp2040 cells -> per collection cell a separate carrier
        if (goodsType == 140) {
            String collectionZone = CommercialTrafficUtils.getOriginCell(freightDemandDataRelation);
            return Id.create(
                    "WasteCollection_Zone_" + collectionZone + "_depot_" + CommercialTrafficUtils.getDestinationLocationId(freightDemandDataRelation),
                    Carrier.class);
        }
        // parcel delivery, assuming that the delivery teams have service areas based on the vpp2040 cells -> per delivery cell a separate carrier
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
	 * @param carrierGoodsType	   the goods type of the carrier
	 */
    public void createCarriersForLTL(Population inputFreightDemandData, Scenario scenario, int jspritIterationsForLTL, int carrierGoodsType) {
        Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);

        TransportModeNetworkFilter filter = new TransportModeNetworkFilter(scenario.getNetwork());
        Set<String> modes = new HashSet<>();
        modes.add("car"); // TODO adjust for other modes
        Network filteredNetwork = NetworkUtils.createNetwork(scenario.getConfig().network());
        filter.filter(filteredNetwork, modes);

        for (Person freightDemandDataRelation : inputFreightDemandData.getPersons().values()) {
			int thisGoodsType = CommercialTrafficUtils.getGoodsType(freightDemandDataRelation);
			// Filter only the goodsTypes of the carrier. If the carrierGoodsType is Integer.MIN_VALUE, we assume that the carrier is for all goods types, except for waste collection and parcel delivery
			if ((thisGoodsType != carrierGoodsType && carrierGoodsType != Integer.MIN_VALUE) || (carrierGoodsType == Integer.MIN_VALUE && (thisGoodsType == 140 || thisGoodsType == 150)))
				continue;
			if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(CommercialTrafficUtils.TransportType.LTL.toString())) {
                Id<Carrier> carrierId = createCarrierId(freightDemandDataRelation);
				if (carriers.getCarriers().containsKey(carrierId)) {
                    Carrier existingCarrier = carriers.getCarriers().get(carrierId);
                    if (thisGoodsType != 140) {
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
                    newCarrier.getAttributes().putAttribute("goodsType", thisGoodsType);
                    Link vehicleLocation;
                    if (thisGoodsType != 140) {
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

        // parcel delivery or general goods
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
                    CarrierShipment existingShipment = existingCarrier.getShipments().get(existingShipments.getFirst().getId());
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


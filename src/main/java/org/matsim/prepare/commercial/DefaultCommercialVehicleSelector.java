package org.matsim.prepare.commercial;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.Pair;
import org.matsim.api.core.v01.population.Person;

import java.util.ArrayList;
import java.util.List;

public class DefaultCommercialVehicleSelector implements CommercialVehicleSelector {
	private final RandomGenerator rnd = new MersenneTwister(4711);
	EnumeratedDistribution<VehicleSelection> vehicleDistributionFTL;
	EnumeratedDistribution<VehicleSelection> vehicleDistributionWaste;
	EnumeratedDistribution<VehicleSelection> vehicleDistributionParcel;
	EnumeratedDistribution<VehicleSelection> vehicleDistributionParcel_truck;
	EnumeratedDistribution<VehicleSelection> vehicleDistributionRest;

	public DefaultCommercialVehicleSelector() {
		createVehicleTypeDistribution();
	}

	/**
	 * Creates the vehicle type distribution for the different transport types.
	 */
	private void createVehicleTypeDistribution() {
		List<Pair<VehicleSelection, Double>> vehicleSelectionProbabilityDistributionFTL = new ArrayList<>();
		vehicleSelectionProbabilityDistributionFTL.add(new Pair<>(new VehicleSelection("heavy40t"), 1.0));
		vehicleDistributionFTL = new EnumeratedDistribution<>(rnd, vehicleSelectionProbabilityDistributionFTL);

		List<Pair<VehicleSelection, Double>> vehicleSelectionProbabilityDistributionWaste = new ArrayList<>();
		vehicleSelectionProbabilityDistributionWaste.add(new Pair<>(new VehicleSelection("waste_collection_diesel"), 1.0));
		vehicleDistributionWaste = new EnumeratedDistribution<>(rnd, vehicleSelectionProbabilityDistributionWaste);

		List<Pair<VehicleSelection, Double>> vehicleSelectionProbabilityDistributionParcel = new ArrayList<>();
		vehicleSelectionProbabilityDistributionParcel.add(new Pair<>(new VehicleSelection("mercedes313_parcel"), 1.0));
		vehicleDistributionParcel = new EnumeratedDistribution<>(rnd, vehicleSelectionProbabilityDistributionParcel);

		List<Pair<VehicleSelection, Double>> vehicleSelectionProbabilityDistributionParcel_truck = new ArrayList<>();
		vehicleSelectionProbabilityDistributionParcel_truck.add(new Pair<>(new VehicleSelection("medium18t_parcel"), 1.0));
		vehicleDistributionParcel_truck = new EnumeratedDistribution<>(rnd, vehicleSelectionProbabilityDistributionParcel_truck);

		List<Pair<VehicleSelection, Double>> vehicleSelectionProbabilityDistributionRest = new ArrayList<>();
		vehicleSelectionProbabilityDistributionRest.add(new Pair<>(new VehicleSelection("medium18t"), 1.0));
		vehicleDistributionRest = new EnumeratedDistribution<>(rnd, vehicleSelectionProbabilityDistributionRest);
	}

	/**
	 * Gets the possible vehicle type for the given freight demand data relation.
	 *
	 * @param freightDemandDataRelation the freight demand data relation
	 * @param carrierId                 the carrier id
	 * @return the possible vehicle type
	 */
	@Override
    public String getVehicleTypeForPlan(Person freightDemandDataRelation, String carrierId) {

        if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL") || CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(CommercialTrafficUtils.TransportType.FTL_kv.toString()))
			return vehicleDistributionFTL.sample().vehicleType;
        else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
            return vehicleDistributionWaste.sample().vehicleType;
        if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) // parcel delivery
            if (carrierId.contains("_truck18t"))
                return vehicleDistributionParcel_truck.sample().vehicleType;
            else
                return vehicleDistributionParcel.sample().vehicleType;
        return vehicleDistributionRest.sample().vehicleType;
    }

	/**
	 * Gets the possible vehicle types for the given carrier.
	 * TODO perhaps add vehicleTypes file to this implementation to get the mode from the selected vehicle type
	 *
	 * @param freightDemandDataRelation the freight demand data relation
	 * @param carrierId                 the carrier id
	 * @return the possible vehicle types for this carrier
	 */
	@Override
	public List<String> getPossibleVehicleTypes(Person freightDemandDataRelation, String carrierId) {
		List<String> allVehicleTypes = new ArrayList<>();
		if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL"))
			allVehicleTypes.addAll(vehicleDistributionFTL.getPmf().stream().map(Pair::getFirst).map(VehicleSelection::vehicleType).toList());
		else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 140) // waste collection
			allVehicleTypes.addAll(vehicleDistributionWaste.getPmf().stream().map(Pair::getFirst).map(VehicleSelection::vehicleType).toList());
		else if (CommercialTrafficUtils.getGoodsType(freightDemandDataRelation) == 150) // parcel delivery
			if (carrierId.contains("_truck18t"))
				allVehicleTypes.addAll(
					vehicleDistributionParcel_truck.getPmf().stream().map(Pair::getFirst).map(VehicleSelection::vehicleType).toList());
			else
				allVehicleTypes.addAll(vehicleDistributionParcel.getPmf().stream().map(Pair::getFirst).map(VehicleSelection::vehicleType).toList());
		else allVehicleTypes.addAll(vehicleDistributionRest.getPmf().stream().map(Pair::getFirst).map(VehicleSelection::vehicleType).toList());

		return allVehicleTypes;
	}

	@Override
	public String getModeForFTLTrip(Person freightDemandDataRelation) {
		// the current assumption is that all FTL trips are done by 40 tones trucks
		if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals("FTL") || CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(
			CommercialTrafficUtils.TransportType.FTL_kv.toString()))
			return "truck40t";
		return null;
	}

	private record VehicleSelection(String vehicleType) {}

}

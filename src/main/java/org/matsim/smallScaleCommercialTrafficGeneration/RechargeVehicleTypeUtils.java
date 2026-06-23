package org.matsim.smallScaleCommercialTrafficGeneration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RechargeVehicleTypeUtils {

	private static final String RECHARGE_TYPE_SUFFIX = "Recharge";
	private static final Logger log = LogManager.getLogger(RechargeVehicleTypeUtils.class);

	private RechargeVehicleTypeUtils() {
	}

	public static boolean isRechargeVehicleType(Id<VehicleType> vehicleTypeId) {
		return vehicleTypeId.toString().endsWith(RECHARGE_TYPE_SUFFIX);
	}

	public static Optional<Id<VehicleType>> getReferenceVehicleTypeId(Id<VehicleType> rechargeTypeId) {
		String typeId = rechargeTypeId.toString();
		if (!typeId.endsWith(RECHARGE_TYPE_SUFFIX)) {
			return Optional.empty();
		}
		int separatorIndex = typeId.lastIndexOf('_');
		if (separatorIndex <= 0) {
			return Optional.empty();
		}
		return Optional.of(Id.create(typeId.substring(0, separatorIndex), VehicleType.class));
	}

	/**
	 * Merges the vehicle types actually written by KWM into the main-run vehicle types.
	 * Recharge types keep their IDs, but get costs and capacities from the matching base type.
	 */
	public static int writeMainRunVehicleTypesMergedWithCarrierVehicleTypes(Path inputVehicleTypesFile, Collection<Path> carrierVehicleTypesFiles,
	                                                                        Path outputVehicleTypesFile) throws IOException {

		CarrierVehicleTypes vehicleTypes = new CarrierVehicleTypes();
		new CarrierVehicleTypeReader(vehicleTypes).readFile(inputVehicleTypesFile.toString());
		List<Id<VehicleType>> addedVehicleTypeIds = new ArrayList<>();
		int addedTypes = 0;
		for (Path carrierVehicleTypesFile : carrierVehicleTypesFiles) {
			CarrierVehicleTypes newCarrierVehicleTypes = new CarrierVehicleTypes();
			new CarrierVehicleTypeReader(newCarrierVehicleTypes).readFile(carrierVehicleTypesFile.toString());
			for (VehicleType newCarrierVehicleType : newCarrierVehicleTypes.getVehicleTypes().values()) {
				if (vehicleTypes.getVehicleTypes().containsKey(newCarrierVehicleType.getId())) {
					continue;
				}
				vehicleTypes.getVehicleTypes().put(newCarrierVehicleType.getId(), newCarrierVehicleType);
				addedVehicleTypeIds.add(newCarrierVehicleType.getId());
				addedTypes++;
			}
		}
		for (Id<VehicleType> addedVehicleTypeId : addedVehicleTypeIds) {
			log.info("Added KWM vehicle type {} from carrier vehicle-type files.", addedVehicleTypeId);
		}

		int restoredTypes = restoreRechargeCostsAndCapacity(vehicleTypes.getVehicleTypes());

		Path parent = outputVehicleTypesFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		CarriersUtils.writeCarrierVehicleTypes(vehicleTypes, outputVehicleTypesFile.toString());
		log.info(
			"Wrote main-run vehicle types to {}. Added {} KWM vehicle types from {} carrier vehicle-type files and normalized {} Recharge types.",
			outputVehicleTypesFile, addedTypes, carrierVehicleTypesFiles.size(), restoredTypes);
		return addedTypes;
	}

	static int restoreRechargeCostsAndCapacity(Map<Id<VehicleType>, VehicleType> vehicleTypes) {
		int restoredTypes = 0;
		for (VehicleType vehicleType : vehicleTypes.values()) {
			Optional<Id<VehicleType>> referenceTypeId = getReferenceVehicleTypeId(vehicleType.getId());
			if (referenceTypeId.isEmpty()) {
				continue;
			}
			VehicleType referenceType = vehicleTypes.get(referenceTypeId.get());
			if (referenceType == null) {
				log.warn("Could not normalize Recharge vehicle type {} because reference type {} is missing.", vehicleType.getId(),
					referenceTypeId.get());
				continue;
			}
			vehicleType.setDescription(referenceType.getDescription() + " " + RECHARGE_TYPE_SUFFIX);
			vehicleType.getCostInformation().setFixedCost(referenceType.getCostInformation().getFixedCosts());
			VehicleUtils.setEnergyCapacity(vehicleType.getEngineInformation(), VehicleUtils.getEnergyCapacity(referenceType.getEngineInformation()));
			restoredTypes++;
		}
		return restoredTypes;
	}
}

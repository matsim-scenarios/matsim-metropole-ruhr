package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimAppCommand;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlanXmlReader;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

/**
 * Creates policy-case carrier files from one identical unsolved carrier file by changing only the available fleet.
 */
public class AdjustCarrierFleetForPolicyCases implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(AdjustCarrierFleetForPolicyCases.class);

	@CommandLine.Option(names = "--carriersFile", description = "Input unsolved carrier file.", required = true)
	private Path carriersFile;

	@CommandLine.Option(names = "--carrierVehicleTypesFile", description = "Input carrier vehicle types file.", required = true)
	private Path carrierVehicleTypesFile;

	@CommandLine.Option(names = "--outputCarriersFile", description = "Output carrier file with adjusted fleet.", required = true)
	private Path outputCarriersFile;

	@CommandLine.Option(names = "--outputCarrierVehicleTypesFile", description = "Output carrier vehicle types file. If omitted, the input vehicle types file is filtered next to the output carrier file.")
	private Path outputCarrierVehicleTypesFile;

	@CommandLine.Option(names = "--vehicleTypeMapping", description = "Optional mapping diesel=electric or diesel=electric1|electric2. Can be repeated.")
	private List<String> vehicleTypeMappings = new ArrayList<>();

	public static void main(String[] args) {
		System.exit(new CommandLine(new AdjustCarrierFleetForPolicyCases()).execute(args));
	}

	@Override
	public Integer call() {

		CarrierVehicleTypes inputVehicleTypes = new CarrierVehicleTypes();
		new CarrierVehicleTypeReader(inputVehicleTypes).readFile(carrierVehicleTypesFile.toString());

		CarrierVehicleTypes newVehicleTypes = new CarrierVehicleTypes();
		new CarrierVehicleTypeReader(newVehicleTypes).readFile(outputCarrierVehicleTypesFile.toString());

		Carriers inputCarriers = new Carriers();
		new CarrierPlanXmlReader(inputCarriers, inputVehicleTypes).readFile(carriersFile.toString());

		FleetMapping fleetMapping = FleetMapping.withDefaults(vehicleTypeMappings);

		Set<Id<Vehicle>> vehiclesToRemove = new HashSet<>();
		Set<CarrierVehicle> vehiclesToAdd = new HashSet<>();
		inputCarriers.getCarriers().values().forEach(
			carrier -> {
				carrier.getCarrierCapabilities().getCarrierVehicles().values().forEach(vehicle -> {
					VehicleFamily family = fleetMapping.familyFor(vehicle.getType().getId().toString());
					if (family == null) {
						throw new IllegalArgumentException("Vehicle type " + vehicle.getType().getId() + " of carrier " + carrier.getId()
							+ " is not mapped to any vehicle family. Add a mapping for this vehicle type or remove it from the carrier file.");
					}
					if (family.dieselType.equals(vehicle.getType().getId().toString())) {
						family.electricTypes.forEach(electricType -> {
							CarrierVehicle newVehicle = CarrierVehicle.Builder.newInstance(
									Id.createVehicleId(vehicle.getId().toString() + "_" + electricType), vehicle.getLinkId(),
									newVehicleTypes.getVehicleTypes().get(Id.create(electricType, VehicleType.class)))
								.setEarliestStart(vehicle.getEarliestStartTime())
								.setLatestEnd(vehicle.getLatestEndTime())
								.build();
							vehiclesToAdd.add(newVehicle);
						});
					}
					vehiclesToRemove.add(vehicle.getId());
				});
				vehiclesToAdd.forEach(newVehicle -> carrier.getCarrierCapabilities().getCarrierVehicles().put(newVehicle.getId(), newVehicle));
				carrier.getCarrierCapabilities().getCarrierVehicles().keySet().removeAll(vehiclesToRemove);
				vehiclesToRemove.clear();
				vehiclesToAdd.clear();
			}
		);
		CarriersUtils.writeCarriers(inputCarriers, outputCarriersFile.toString());
		return 0;
	}

	private record FleetMapping(Map<String, VehicleFamily> familiesByVehicleType) {

		static FleetMapping withDefaults(List<String> explicitMappings) {
			Map<String, VehicleFamily> familiesByVehicleType = new HashMap<>();
			addFamily(familiesByVehicleType, new VehicleFamily("golf1.0", List.of("ID.3")));
			addFamily(familiesByVehicleType, new VehicleFamily("VW_T6", List.of("ID.Buzz")));
			addFamily(familiesByVehicleType, new VehicleFamily("mercedes316", List.of("mercedesESprinter")));
			addFamily(familiesByVehicleType, new VehicleFamily("light8t", List.of("light8t_EV")));
			addFamily(familiesByVehicleType, new VehicleFamily("medium18t", List.of("medium18t_EV")));
			addFamily(familiesByVehicleType, new VehicleFamily("medium18t_parcel", List.of("medium18t_parcel_EV")));
			addFamily(familiesByVehicleType, new VehicleFamily("waste_collection_diesel", List.of("waste_collection_EV1", "waste_collection_EV2")));

			explicitMappings.forEach(mapping -> addFamily(familiesByVehicleType, parseVehicleFamily(mapping)));
			return new FleetMapping(familiesByVehicleType);
		}

		private VehicleFamily familyFor(String vehicleType) {
			return familiesByVehicleType.get(vehicleType);
		}

		private static VehicleFamily parseVehicleFamily(String mapping) {
			String[] mappingParts = mapping.split("[=:]", 2);
			if (mappingParts.length != 2 || mappingParts[0].isBlank() || mappingParts[1].isBlank()) {
				throw new IllegalArgumentException("Invalid --vehicleTypeMapping '" + mapping
					+ "'. Expected diesel=electric or diesel=electric1|electric2.");
			}
			return new VehicleFamily(mappingParts[0].trim(), List.of(mappingParts[1].trim().split("\\|")));
		}

		private static void addFamily(Map<String, VehicleFamily> familiesByVehicleType, VehicleFamily family) {
			familiesByVehicleType.put(family.dieselType(), family);
			family.electricTypes().forEach(electricType -> familiesByVehicleType.put(electricType, family));
		}
	}

	record VehicleFamily(String dieselType, List<String> electricTypes) {

		VehicleFamily {
			electricTypes = electricTypes.stream().map(String::trim).filter(type -> !type.isEmpty()).toList();
			if (dieselType == null || dieselType.isBlank() || electricTypes.isEmpty()) {
				throw new IllegalArgumentException("Vehicle family needs one diesel type and at least one electric type.");
			}
		}

		private String vehicleType(boolean electric, int electricIndex) {
			if (!electric) {
				return dieselType;
			}
			return electricTypes.get(electricIndex % electricTypes.size());
		}

		private boolean isElectric(String vehicleType) {
			return electricTypes.contains(vehicleType);
		}
	}
}

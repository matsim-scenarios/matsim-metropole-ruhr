package org.matsim.prepare;

import org.apache.commons.math3.util.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import picocli.CommandLine;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdjustNetworkCapacities implements MATSimAppCommand {
	private static final double PCU_CAR = 1;
	private static final double PCU_TRUCK = 3.5;

	private static final Logger log = LogManager.getLogger(AdjustNetworkCapacities.class);

	@CommandLine.Option(names = "--adjust-capacities-to-dtv-counts", defaultValue = "false", description = "Enable or disable adjustment of capacities according to dtv", negatable = true)
	private boolean adjustCapacitiesToDtvCounts;

	@CommandLine.Option(names = "--adjust-capacities-to-bast-counts", defaultValue = "false", description = "Enable or disable adjustment of capacities according to bast", negatable = true)
	private boolean adjustCapacitiesToBastCounts;

	@CommandLine.Option(names = "--bast-car-counts", description = "Path to the BAST car counts CSV file")
	private Path bastCarCounts;

	@CommandLine.Option(names = "--bast-truck-counts", description = "Path to the BAST truck counts CSV file")
	private Path bastTruckCounts;

	@CommandLine.Option(names = "--dtv-counts", description = "Path to the DTV counts CSV file")
	private Path dtvCounts;

	@CommandLine.Option(names = "--network", description = "Path to network file")
	private Path networkPath;

	public static void main(String[] args) {
		new AdjustNetworkCapacities().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		List<CapacityChange> capacityChanges = new ArrayList<>();

		Network network = NetworkUtils.readNetwork(networkPath.toString());

		if (adjustCapacitiesToDtvCounts) {
			log.info("Adjusting network capacities to DTV counts...");
			adjustNetworkCapacitiesToDtvCounts(network, capacityChanges);
		}

		if (adjustCapacitiesToBastCounts) {
			log.info("Adjusting network capacities to BAST counts...");
			adjustNetworkCapacitiesToBastCounts(network, capacityChanges);
		}

		if (adjustCapacitiesToBastCounts || adjustCapacitiesToDtvCounts) {
			log.info("Adjusting network capacities to BAST and DTV counts...");
			//write capacity changes to file
			try (BufferedWriter writer = new BufferedWriter(new FileWriter("adjustedCapacities.csv"))) {
				// Write header
				writer.write("source,link_id,simulated_traffic,observed_traffic,old_capacity,new_capacity");
				writer.newLine();

				// Write each record
				for (CapacityChange change : capacityChanges) {
					writer.write(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f",
						change.source(),
						change.linkId().toString(),
						change.simulatedTraffic(),
						change.observedTraffic(),
						change.oldCapacity(),
						change.newCapacity()));
					writer.newLine();
				}

				System.out.println("CSV written successfully");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		NetworkUtils.writeNetwork(network, "adjustedNetwork_forValidation.xml.gz");

		return 0;
	}

	private void adjustNetworkCapacitiesToBastCounts(Network network, List<CapacityChange> capacityChanges) {
		// read counts from calibrated scenario
		Map<Id<Link>, List<BastCountEntry>> carCsvCountEntries = readBastCountsCsvFile(bastCarCounts);
		Map<Id<Link>, List<BastCountEntry>> truckCsvCountEntries = readBastCountsCsvFile(bastTruckCounts);

		Map<Id<Link>, Pair<Double, Double>> linkMaxHourlyObservedVolumes = new HashMap<>();
		for (Map.Entry<Id<Link>, List<BastCountEntry>> entry : carCsvCountEntries.entrySet()) {
			Id<Link> linkId = entry.getKey();
			List<BastCountEntry> countEntries = entry.getValue();

			// find the maximum observed traffic for this link
			double maxCarObservedTraffic = countEntries.stream()
				.mapToDouble(BastCountEntry::observedTraffic)
				.max()
				.orElse(0.0);

			double maxTruckObservedTraffic = truckCsvCountEntries.get(linkId).stream()
				.mapToDouble(BastCountEntry::observedTraffic)
				.max()
				.orElse(0.0);

			double maxCarSimulatedTraffic = countEntries.stream()
				.mapToDouble(BastCountEntry::simulatedTraffic)
				.max()
				.orElse(0.0);

			double maxTruckSimulatedTraffic = truckCsvCountEntries.get(linkId).stream()
				.mapToDouble(BastCountEntry::simulatedTraffic)
				.max()
				.orElse(0.0);

			linkMaxHourlyObservedVolumes.put(linkId, Pair.create(maxCarObservedTraffic * PCU_CAR + maxTruckObservedTraffic * PCU_TRUCK,
				maxCarSimulatedTraffic * PCU_CAR + maxTruckSimulatedTraffic * PCU_TRUCK));
		}

		for (Map.Entry<Id<Link>, Pair<Double, Double>> entry : linkMaxHourlyObservedVolumes.entrySet()) {
			Link link = network.getLinks().get(entry.getKey());
			Double simulated = entry.getValue().getSecond();
			Double observed = entry.getValue().getFirst();
			if (simulated > observed) {
				// If the simulated traffic is higher than the observed traffic, we adjust the capacity.
				// We do this because the simulated traffic should not exceed the observed traffic in order to match the counts (better).
				log.info("Adjusting capacity for link {}: simulated traffic ({}) > observed traffic ({}). Setting capacity to observed traffic.", link.getId(), simulated, observed);
				log.info("Link {}: old capacity = {}, new capacity = {}", link.getId(), link.getCapacity(), observed);

				if (observed > link.getCapacity()) {
					log.info("This seems unplausible, as the observed traffic is higher than the current capacity. NOT adjusting capacity to observed traffic.");
					capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), link.getCapacity()));
					continue;
				}
				capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), observed));
				link.setCapacity(observed);
			} else {
				// If the simulated traffic is lower than the observed traffic, we do not adjust the capacity.
				capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), link.getCapacity()));
			}
		}
	}

	private void adjustNetworkCapacitiesToDtvCounts(Network network, List<CapacityChange> capacityChanges) {
		Map<Id<Link>, DtvCountEntry> idDtvCountEntryMap = readDtvCountsCsvFile(dtvCounts);

		Map<Id<Link>, List<BastCountEntry>> carBastCounts = readBastCountsCsvFile(bastCarCounts);
		Map<Id<Link>, List<BastCountEntry>> truckBastCounts = readBastCountsCsvFile(bastTruckCounts);

		List<Double> observerdSpitzenstundeFactor = new ArrayList<>();
		for (Map.Entry<Id<Link>, List<BastCountEntry>> entry : carBastCounts.entrySet()) {
			Id<Link> linkId = entry.getKey();

			double maxObservedFlow = -1;
			double sumObservedFlow = 0.;
			for (int i = 0; i < 24; i++) {
				double carObserved = entry.getValue().get(i).observedTraffic;
				double truckObserved = truckBastCounts.get(linkId).get(i).observedTraffic;
				double scaledObserved = carObserved * PCU_CAR + truckObserved * PCU_TRUCK;
				if (scaledObserved > maxObservedFlow) {
					maxObservedFlow = scaledObserved;
				}
				sumObservedFlow += scaledObserved;
			}

			observerdSpitzenstundeFactor.add(maxObservedFlow / sumObservedFlow);
		}

		double factor = observerdSpitzenstundeFactor.stream()
			.mapToDouble(Double::doubleValue)
			.average()
			.orElseThrow();

		log.info("Average factor for observed peak hour traffic: {}", factor);

		for (Map.Entry<Id<Link>, DtvCountEntry> entry : idDtvCountEntryMap.entrySet()) {
			Link link = network.getLinks().get(entry.getKey());
			double dailySimulated = entry.getValue().simulatedLkw * PCU_TRUCK + entry.getValue().simulatedPkw * PCU_CAR;
			double dailyObserved = entry.getValue().observedLkw * PCU_TRUCK + entry.getValue().observedPkw * PCU_CAR;
			if (dailySimulated > dailyObserved) {
				// If the dailySimulated traffic is higher than the dailyObserved traffic, we adjust the capacity.
				// We do this because the dailySimulated traffic should not exceed the dailyObserved traffic in order to match the counts (better).
				log.info("Adjusting capacity for link {}: dailySimulated traffic ({}) > dailyObserved traffic ({}). Setting capacity to dailyObserved traffic.", link.getId(), dailySimulated, dailyObserved);
				log.info("Link {}: old capacity = {}, new capacity = {}", link.getId(), link.getCapacity(), dailyObserved * factor);

				if (dailyObserved * factor > link.getCapacity()) {
					log.info("This seems unplausible, as the observed traffic is higher than the current capacity. NOT adjusting capacity to observed traffic.");
					capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), link.getCapacity()));
					continue;
				}
				capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), dailyObserved * factor));
				link.setCapacity(dailyObserved * factor);
			} else {
				capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), link.getCapacity()));
			}
		}
	}

	private static Map<Id<Link>, DtvCountEntry> readDtvCountsCsvFile(Path csvFile) {
		String line;
		String csvSplitBy = ";";

		Map<Id<Link>, DtvCountEntry> result = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new FileReader(String.valueOf(csvFile)))) {
			br.readLine();
			while ((line = br.readLine()) != null) {
				// Zeile splitten
				String[] fields = line.split(csvSplitBy, -1); // -1 = alle Felder (auch leere)

				// Id hin
				Id<Link> linkId_forward = Id.createLinkId(fields[0]);
				// Id zurück
				Id<Link> linkId_backward = Id.createLinkId(fields[1]);

				double observedPkw = Double.parseDouble(fields[2].replace("\"", ""));
				double observedLkw = Double.parseDouble(fields[3].replace("\"", ""));

				double simulatedPkw = Double.parseDouble(fields[6].replace("\"", "")); // (6!!)
				double simulatedLkw = Double.parseDouble(fields[5].replace("\"", "")); // (5!!)

				// We assume that the observed traffic is split evenly between forward and backward directions
				DtvCountEntry forwardEntry = new DtvCountEntry(observedPkw / 2, observedLkw / 2, simulatedPkw / 2, simulatedLkw / 2);
				result.put(linkId_forward, forwardEntry);

				DtvCountEntry backwardEntry = new DtvCountEntry(observedPkw / 2, observedLkw / 2, simulatedPkw / 2, simulatedLkw / 2);
				result.put(linkId_backward, backwardEntry);
			}
			return result;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (NumberFormatException e) {
			System.err.println("Fehler beim Parsen von numerischen Werten.");
			e.printStackTrace();
		}
		return result;
	}

	private static Map<Id<Link>, List<BastCountEntry>> readBastCountsCsvFile(Path csvFile) {
		String line;
		String csvSplitBy = ",";

		Map<Id<Link>, List<BastCountEntry>> csvCountEntries = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new FileReader(String.valueOf(csvFile)))) {
			br.readLine();
			while ((line = br.readLine()) != null) {
				// Zeile splitten
				String[] fields = line.split(csvSplitBy, -1); // -1 = alle Felder (auch leere)

				// Felder zuweisen (bei Bedarf parsen)
				Id<Link> linkId = Id.createLinkId(fields[0]);
				String name = fields[1];
				int hour = Integer.parseInt(fields[3]);
				double observedTraffic = Double.parseDouble(fields[4]);
				double simulatedTraffic = Double.parseDouble(fields[5]);

				csvCountEntries.computeIfAbsent(linkId, k -> new ArrayList<>()).add(new BastCountEntry(name, hour, observedTraffic, simulatedTraffic));
			}

			return csvCountEntries;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (NumberFormatException e) {
			System.err.println("Fehler beim Parsen von numerischen Werten.");
			e.printStackTrace();
		}
		return csvCountEntries;
	}

	private record BastCountEntry(String name, int hour, double observedTraffic, double simulatedTraffic) {
	}

	private record DtvCountEntry(double observedPkw, double observedLkw, double simulatedPkw, double simulatedLkw) {
	}

	private record CapacityChange(String source, Id<Link> linkId, double simulatedTraffic, double observedTraffic, double oldCapacity,
								  double newCapacity) {
	}
}

package org.matsim.prepare;

import org.apache.commons.logging.Log;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.counts.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.OptionalDouble;

public class AdjustCounts implements MATSimAppCommand {
	Logger log = LogManager.getLogger(AdjustCounts.class);

	@CommandLine.Option(names = "--counts", description = "Path to counts", required = true)
	private String counts;

	public static void main(String[] args) {
		new AdjustCounts().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		fixMismatchedCountStations(counts);
		return 0;
	}

	private void fixMismatchedCountStations(String countsPath) {
		log.info("Fixing mismatched count stations...");

//		Counts<Link> c = (Counts<Link>) scenario.getScenarioElement(Counts.ELEMENT_NAME);
		Counts<Link> c = new Counts<>();
		new MatsimCountsReader(c).readFile(countsPath);

		// Gelesenkirchen Polsum Ost Fix
		fixCountStation(c, Id.createLinkId("231796360004f-231796380000f-231796370002f"), Id.createLinkId("2850949020015f-1844830790000f"));

		// Gelsenkirchen Polsum West Fix
		fixCountStation(c, Id.createLinkId("231796370002r-231796380000r-231796360004r"), Id.createLinkId("2939789940010f"));

		//Recklinghausen Ost Ost Fix
		fixCountStation(c, Id.createLinkId("232517830000f-232517840007f"), Id.createLinkId("2708756010013f"));

		//Recklinghausen Ost West Fix
		fixCountStation(c, Id.createLinkId("232517840007r-232517830000r"), Id.createLinkId("2708713900009f"));

		// Hengsen Süd Fix
		fixCountStation(c, Id.createLinkId("701857000007f-117858730002f"), Id.createLinkId("2939815590027f-602840750021f-1458140240000f-1458140150012f"));

		// Hengsen Nord Fix
		fixCountStation(c, Id.createLinkId("117858730002r-701857000007r"), Id.createLinkId("2641839640015f-1458140210000f-1458140550046f"));

		new CountsWriter(c).write("adjustedCounts.xml.gz");
	}

	private static void fixCountStation(Counts<Link> c, Id<Link> oldLink, Id<Link> newLink) {
		MeasurementLocation<Link> oldLocation = c.getMeasureLocations().remove(oldLink);

		MeasurementLocation<Link> newLocation = c.createAndAddMeasureLocation(newLink, oldLocation.getStationName());

		Measurable measuredCar = oldLocation.getMeasurableForMode(Measurable.VOLUMES, TransportMode.car);
		Measurable measuredTruck = oldLocation.getMeasurableForMode(Measurable.VOLUMES, TransportMode.truck);

		Measurable newCarCount = newLocation.createPassengerCounts(TransportMode.car, 60 * 60);
		Measurable newTruckCount = newLocation.createPassengerCounts(TransportMode.truck, 60 * 60);

		for (int i = 1; i < 25; i++) {
			OptionalDouble carAtHour = measuredCar.getAtHour(i);
			newCarCount.setAtHour(i, carAtHour.getAsDouble());
			newTruckCount.setAtHour(i, measuredTruck.getAtHour(i).getAsDouble());
		}
	}
}

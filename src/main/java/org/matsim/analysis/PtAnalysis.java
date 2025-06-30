package org.matsim.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import picocli.CommandLine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class PtAnalysis implements MATSimAppCommand {


	@CommandLine.Option(names = "--path", description = "Path to output csb", required = true)
	private String outputCsvFile;

	public static void main(String[] args) {
		new PtAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.transit().setTransitScheduleFile("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v2024.1/no-intermodal/002.output_transitSchedule.xml.gz");

		Scenario scenario = ScenarioUtils.loadScenario(config);

		TransitSchedule transitSchedule = scenario.getTransitSchedule();

		// === Write stops to CSV ===
		//writeOutTransitStops(transitSchedule);


		// Collect modes
		Set<String> modes = new HashSet<>();

		for (TransitLine line : transitSchedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				modes.add(route.getTransportMode());
			}
		}

		// Print the modes
		System.out.println("Transport modes used in the transit schedule:");
		for (String mode : modes) {
			System.out.println("- " + mode);
		}


		return 0;
	}

	private void writeOutTransitStops(TransitSchedule transitSchedule) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsvFile))) {
			writer.write("id,name,x,y,linkRefId\n"); // Header

			for (TransitStopFacility stop : transitSchedule.getFacilities().values()) {
				Id<TransitStopFacility> id = stop.getId();
				String name = stop.getName() != null ? stop.getName() : "";
				Coord coord = stop.getCoord();
				Id<Link> linkId = stop.getLinkId();

				writer.write(String.format("%s,\"%s\",%.3f,%.3f,%s\n",
					id.toString(),
					name.replace("\"", "\"\""), // escape double quotes
					coord.getX(),
					coord.getY(),
					linkId != null ? linkId.toString() : ""));
			}

			System.out.println("Transit stops written to: " + outputCsvFile);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

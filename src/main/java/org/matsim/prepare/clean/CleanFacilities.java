package org.matsim.prepare.clean;

import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.facilities.MatsimFacilitiesReader;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "clean-facilities", description = "Remove facilities with ids that are not in the network.")
public class CleanFacilities implements MATSimAppCommand {

	@CommandLine.Option(names = "--network", description = "Network file", required = true)
	private String networkPath;

	@CommandLine.Option(names = "--facilities", description = "Facilities file", required = true)
	private String facilitiesPath;

	@CommandLine.Option(names = "--output", description = "Output facilities", required = true)
	private Path output;

	@Override
	public Integer call() throws Exception {

		Network network = NetworkUtils.readNetwork(networkPath);

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		new MatsimFacilitiesReader("EPSG:25832", "EPSG:25832", facilities).readFile(facilitiesPath);

		facilities.getFacilities().values().removeIf(
			f -> f.getLinkId() != null && !network.getLinks().containsKey(f.getLinkId())
		);

		new FacilitiesWriter(facilities).write(output.toString());

		return 0;
	}
}

package org.matsim.analysis;

import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import picocli.CommandLine;

import java.nio.file.Path;

public class CountDataAnalysis implements MATSimAppCommand {

	@picocli.CommandLine.Option(names = "--network", description = "path to MATSim network", required = true)
	private String network;
	@picocli.CommandLine.Option(names = "--primary-data", description = "path to BASt Bundesstraßen-'Stundenwerte'-.txt file", required = true)
	private Path primaryData;
	@picocli.CommandLine.Option(names = "--motorway-data", description = "path to BASt Bundesautobahnen-'Stundenwerte'-.txt file", required = true)
	private Path motorwayData;
	@picocli.CommandLine.Option(names = "--station-data", description = "path to default BASt count station .csv", required = true)
	private Path stationData;
	@picocli.CommandLine.Option(names = "--year", description = "Year of counts", required = true)
	private int year;
	@CommandLine.Option(names = "--output", description = "Output counts path", defaultValue = "counts-from-bast.xml.gz")
	private Path output;


	public static void main(String[] args) throws Exception {
		new CountDataAnalysis().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		new CreateCountsFromBAStData().execute(
			"--network", network,
			"--primary-data", primaryData.toString(),
			"--motorway-data", motorwayData.toString(),
			"--station-data", stationData.toString(),
			"--year", String.valueOf(year),
			"--output", output.toString()
		);

		return 0;
	}
}






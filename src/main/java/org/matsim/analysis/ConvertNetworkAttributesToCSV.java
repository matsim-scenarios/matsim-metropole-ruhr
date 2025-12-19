package org.matsim.analysis;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.io.IOUtils;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.Set;
import java.util.TreeSet;

public class ConvertNetworkAttributesToCSV implements MATSimAppCommand {

	@CommandLine.Option(names = "--network", description = "Path to the network attributes file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--output", description = "Path to output CSV file", required = true)
	private String outputCsv;

	@Override
	public Integer call() throws Exception {
		// Collect all attribute keys used across all links
		Set<String> allAttributeKeys = new TreeSet<>(); // Sorted for consistent CSV columns
		Network network =  NetworkUtils.readNetwork(networkFile);

		for (Link link: network.getLinks().values()) {
			allAttributeKeys.addAll(link.getAttributes().getAsMap().keySet());
		}

		try (PrintWriter writer = new PrintWriter(IOUtils.getBufferedWriter(outputCsv))) {
			// Write header
			writer.print("linkId,fromNodeId,toNodeId,length,capacity,freespeed,lanes");
			for (String attrKey : allAttributeKeys) {
				writer.print("," + attrKey);
			}
			writer.println();

			// Write each link's data
			for (Link link : network.getLinks().values()) {
				writer.print(link.getId());
				writer.print("," + link.getFromNode().getId());
				writer.print("," + link.getToNode().getId());
				writer.print("," + link.getLength());
				writer.print("," + link.getCapacity());
				writer.print("," + link.getFreespeed());
				writer.print("," + link.getNumberOfLanes());

				for (String attrKey : allAttributeKeys) {
					Object attrValue = link.getAttributes().getAttribute(attrKey);
					writer.print("," + (attrValue != null ? attrValue.toString() : ""));
				}
				writer.println();
			}
		}

		System.out.println("CSV written to " + outputCsv);

		return 0;
	}



	public static void main(String[] args) throws Exception {
		ConvertNetworkAttributesToCSV convertNetworkAttributesToCSV = new ConvertNetworkAttributesToCSV();
		convertNetworkAttributesToCSV.execute(args);

	}
}

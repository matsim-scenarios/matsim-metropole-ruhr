package org.matsim.prepare;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

@CommandLine.Command(name = "convert-link-ids-to-coords")
public class ConvertLinkIdsToCoords implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-network", description = "Path to input network", required = true)
	private String inputNetwork;

	@CommandLine.Option(names = "--input-plans", description = "Path to input plans", required = true)
	private String inputPlans;

	@CommandLine.Option(names = "--output", description = "Path to output network", required = true)
	private String output;

	@Override
	public Integer call() throws Exception {
		var pop = PopulationUtils.readPopulation(inputPlans);
		var net = NetworkUtils.readNetwork(inputNetwork);

		pop.getPersons().values().stream()
		   .flatMap(p -> p.getPlans().stream())
		   .flatMap(p -> p.getPlanElements().stream())
		   .filter(e -> e instanceof Activity)
		   .forEach(e -> {
			   var act = (Activity) e;
			   if(act.getLinkId() == null) return;

			   var link = net.getLinks().get(act.getLinkId());
			   if(link == null) throw new RuntimeException("Link not found: " + act.getLinkId());
			   act.setCoord(link.getCoord());
			   act.setLinkId(null);
		   });

		PopulationUtils.writePopulation(pop, output);

		return 0;
	}

	public static void main(String[] args) {
		new ConvertLinkIdsToCoords().execute(args);
	}
}

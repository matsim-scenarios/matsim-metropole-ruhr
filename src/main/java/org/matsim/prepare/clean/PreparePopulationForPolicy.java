package org.matsim.prepare.clean;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.prepare.population.PersonNetworkLinkCheck;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesFromPopulation;
import org.matsim.facilities.FacilitiesWriter;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@CommandLine.Command(
		name = "prepare-population-for-policy",
		description = "Prepare population for policy run by removing invalid links and facilities.",
		mixinStandardHelpOptions = true,
		showDefaultValues = true
)
public class PreparePopulationForPolicy implements MATSimAppCommand {

	@CommandLine.Option(names = "--population", description = "Path to population", required = true)
	private String populationPath;

	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private String networkPath;

	@CommandLine.Option(names = "--output-population", description = "Path to output population", required = true)
	private Path outputPopulationPath;

	@CommandLine.Option(names = "--output-facilities", description = "Path to output facilities", required = true)
	private Path outputFacilitiesPath;

	public static void main(String[] args) throws Exception {
		new PreparePopulationForPolicy().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		Population population = PopulationUtils.readPopulation(populationPath);
		Network network = NetworkUtils.readNetwork(networkPath);

		// Delete link ids, that are now invalid
		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), PersonNetworkLinkCheck.createPersonAlgorithm(network));

		// Clean facilities and old pt routes
		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), new Cleaner());

		// Create the missing facilities once like in PrepareSim
		scenario.getConfig().facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.onePerActivityLinkInPlansFile);

		FacilitiesFromPopulation f = new FacilitiesFromPopulation(scenario);
		f.setAssignLinksToFacilitiesIfMissing(network);
		f.run(population);

		//XY2LinksForFacilities.run(carOnlyNetwork, this.activityFacilities);

		// Write the resulting population and generated facility file
		PopulationUtils.writePopulation(population, outputPopulationPath.toString());
		new FacilitiesWriter(scenario.getActivityFacilities()).write(outputFacilitiesPath.toString());

		return 0;
	}

	/**
	 * Delete all facility ids and routing information of pt trips.
	 */
	private static final class Cleaner implements PersonAlgorithm {

		@Override
		public void run(Person person) {

			for (Plan plan : person.getPlans()) {

				final List<PlanElement> planElements = plan.getPlanElements();

				// Remove all pt trips
				for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {

					boolean hasPT = trip.getLegsOnly().stream().anyMatch(l -> Objects.equals(l.getMode(), TransportMode.pt));

					if (!hasPT)
						continue;

					// Replaces all trip elements and inserts single leg
					final List<PlanElement> fullTrip =
						planElements.subList(
							planElements.indexOf(trip.getOriginActivity()) + 1,
							planElements.indexOf(trip.getDestinationActivity()));

					fullTrip.clear();
					Leg leg = PopulationUtils.createLeg(TransportMode.pt);
					TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
					fullTrip.add(leg);
				}

				// Delete all facility information, will be set later
				for (PlanElement el : plan.getPlanElements()) {
					if (el instanceof Activity act) {
						act.setFacilityId(null);
					}
				}
			}

		}
	}
}

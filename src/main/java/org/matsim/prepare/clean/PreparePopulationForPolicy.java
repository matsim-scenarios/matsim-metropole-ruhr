package org.matsim.prepare.clean;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
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
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesFromPopulation;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import picocli.CommandLine;

import java.nio.file.Path;

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

	@CommandLine.Option(names = "--facilities", description = "Path to facilities", required = true)
	private String facilitiesPath;

	@CommandLine.Option(names = "--transit-schedule", description = "Path to transit schedule", required = true)
	private String transitSchedulePath;

	@CommandLine.Option(names = "--output-population", description = "Path to output population", required = true)
	private Path outputPopulationPath;

	@CommandLine.Option(names = "--output-facilities", description = "Path to output facilities", required = true)
	private Path outputFacilitiesPath;

	@Override
	public Integer call() throws Exception {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		Population population = PopulationUtils.readPopulation(populationPath);
		Network network = NetworkUtils.readNetwork(networkPath);

		new TransitScheduleReader(scenario).readFile(transitSchedulePath);

		// Delete link ids, that are now invalid
		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), PersonNetworkLinkCheck.createPersonAlgorithm(network));

		// Delete activity facilities that are not present (in cleaned facilities)
		// Delete PT routes with transit routes or transit lines that are not present in the transitSchedule
		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), new Cleaner(scenario.getActivityFacilities(), scenario.getTransitSchedule()));

		// Create the missing facilities once like in PrepareSim
		scenario.getConfig().facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.onePerActivityLinkInPlansFile);

		FacilitiesFromPopulation f = new FacilitiesFromPopulation(scenario);
		f.setAssignLinksToFacilitiesIfMissing(network);
		f.run(population);

		// Write the resulting population and generated facility file
		PopulationUtils.writePopulation(population, outputPopulationPath.toString());
		new FacilitiesWriter(scenario.getActivityFacilities()).write(outputFacilitiesPath.toString());

		return 0;
	}

	private static final class Cleaner implements PersonAlgorithm {

		private final ActivityFacilities facilities;
		private final TransitSchedule transitSchedule;

		private Cleaner(ActivityFacilities facilities, TransitSchedule transitSchedule) {
			this.facilities = facilities;
			this.transitSchedule = transitSchedule;
		}

		@Override
		public void run(Person person) {

			for (Plan plan : person.getPlans()) {
				for (PlanElement el : plan.getPlanElements()) {
					if (el instanceof Activity act) {
						if (act.getFacilityId() != null) {
							if (!facilities.getFacilities().containsKey(act.getFacilityId())) {
								act.setFacilityId(null);
							}
						}
					} else if (el instanceof Leg leg) {

						if (leg.getRoute() instanceof DefaultTransitPassengerRoute ptRoute) {
							Id<TransitLine> lineId = ptRoute.getLineId();
							if (!transitSchedule.getTransitLines().containsKey(lineId)) {
								leg.setRoute(null);
							} else {
								Id<TransitRoute> routeId = ptRoute.getRouteId();
								TransitLine line = transitSchedule.getTransitLines().get(lineId);
								if (!line.getRoutes().containsKey(routeId)) {
									leg.setRoute(null);
								}
							}
						}
					}
				}
			}

		}
	}
}

package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CommandLine.Command(
	name = "filter-plans-by-subpopulation",
	description = "Filters a MATSim plans file by person subpopulation.",
	showDefaultValues = true
)
public class FilterPlansBySubpopulation implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(FilterPlansBySubpopulation.class);

	private static final String DEFAULT_COMMERCIAL_SUBPOPULATIONS_ARGUMENT = "LTL_trip,LTL_trips,commercialPersonTraffic,commercialPersonTraffic_service,longDistanceFreight,FTL_trip,FTL_kv_trip,goodsTraffic";

	public static final Set<String> DEFAULT_COMMERCIAL_SUBPOPULATIONS = Set.of(
		"LTL_trip",
		"LTL_trips",
		"commercialPersonTraffic",
		"commercialPersonTraffic_service",
		"longDistanceFreight",
		"FTL_trip",
		"FTL_kv_trip",
		"goodsTraffic"
	);

	@CommandLine.Option(names = "--input", description = "Path to the input plans file.", required = true)
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to the filtered output plans file.", required = true)
	private Path output;

	@CommandLine.Option(names = "--remove-subpopulations", description = "Comma-separated subpopulations to remove. Defaults to known commercial traffic subpopulations.", split = ",")
	private Set<String> removeSubpopulations = new LinkedHashSet<>();

	@CommandLine.Option(names = "--keep-subpopulations", description = "Optional comma-separated subpopulations to keep. If set, all other subpopulations are removed.", split = ",")
	private Set<String> keepSubpopulations = new LinkedHashSet<>();

	@CommandLine.Option(names = "--keep-persons-without-subpopulation", description = "Keep persons where no subpopulation attribute is set.")
	private boolean keepPersonsWithoutSubpopulation = true;

	public static void main(String[] args) {
		System.exit(new CommandLine(new FilterPlansBySubpopulation()).execute(args));
	}

	@Override
	public Integer call() throws IOException {
		if (!keepSubpopulations.isEmpty() && !removeSubpopulations.isEmpty() && !removeSubpopulations.equals(DEFAULT_COMMERCIAL_SUBPOPULATIONS)) {
			throw new IllegalArgumentException("Use either --keep-subpopulations or --remove-subpopulations, not both.");
		}

		log.info("Reading population from {}", input);
		Population population = PopulationUtils.readPopulation(input.toString());

		log.info("Persons before filtering: {}", population.getPersons().size());
		logSubpopulationCounts("before", population);

		FilterResult result = filter(population, keepSubpopulations, removeSubpopulations, keepPersonsWithoutSubpopulation);

		log.info("Removed {} persons.", result.removedPersons());
		log.info("Persons after filtering: {}", population.getPersons().size());
		logSubpopulationCounts("after", population);

		if (output.getParent() != null) {
			Files.createDirectories(output.getParent());
		}

		log.info("Writing filtered population to {}", output);
		new PopulationWriter(population).write(output.toString());
		return 0;
	}

	static FilterResult filter(Population population, Collection<String> keepSubpopulations, Collection<String> removeSubpopulations,
							   boolean keepPersonsWithoutSubpopulation) {
		Set<String> keep = new LinkedHashSet<>(keepSubpopulations);
		Set<String> remove = new LinkedHashSet<>(removeSubpopulations);

		List<Id<Person>> personsToRemove = population.getPersons().values().stream()
			.filter(person -> shouldRemove(PopulationUtils.getSubpopulation(person), keep, remove, keepPersonsWithoutSubpopulation))
			.map(Person::getId)
			.toList();

		personsToRemove.forEach(population::removePerson);
		return new FilterResult(personsToRemove.size());
	}

	private static boolean shouldRemove(String subpopulation, Set<String> keepSubpopulations, Set<String> removeSubpopulations,
										boolean keepPersonsWithoutSubpopulation) {
		if (subpopulation == null) {
			return !keepPersonsWithoutSubpopulation;
		}

		if (!keepSubpopulations.isEmpty()) {
			return !keepSubpopulations.contains(subpopulation);
		}

		return removeSubpopulations.contains(subpopulation);
	}

	private static void logSubpopulationCounts(String label, Population population) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		population.getPersons().values().forEach(person -> {
			String subpopulation = PopulationUtils.getSubpopulation(person);
			counts.merge(subpopulation == null ? "<none>" : subpopulation, 1, Integer::sum);
		});
		counts.forEach((subpopulation, count) -> log.info("Subpopulation {} {}: {}", label, subpopulation, count));
	}

	record FilterResult(int removedPersons) {
	}
}

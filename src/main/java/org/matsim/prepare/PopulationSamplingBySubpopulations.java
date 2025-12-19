package org.matsim.prepare;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Random;

/**
 * Class to sample the different subpopulations by different factors.
 * The values of the percentages are given as remaining share of the population.
 * So if the value is 0.75, 75% of the population will be kept and 25% will be removed.
 */
public class PopulationSamplingBySubpopulations implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PopulationSamplingBySubpopulations.class);

	@CommandLine.Option(names = "--inputPopulation", description = "Path to the input population file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-10pct.plans-initial.xml.gz")
	private Path inputPopulation;

	@CommandLine.Option(names = "--outputPopulation", description = "Path to the output population file", required = true, defaultValue = "scenarios/metropole-ruhr-v2024.0/input/metropole-ruhr-v2024.0-10pct.plans-initial_adjusted.xml.gz")
	private Path outputPopulation;

	@CommandLine.Option(names = "--remainingShare_person", description = "Remaining share of persons to be sampled", defaultValue = "1.0")
	private double remainingSharePersons;

	@CommandLine.Option(names = "--remainingShare_LongDistanceFreight", description = "Remaining share of the long distance freight population containing, LTL, FTL and transit freight", defaultValue = "1.0")
	private double remainingShareLongDistanceFreight;

	@CommandLine.Option(names = "--remainingShare_smallScale_personTraffic", description = "Remaining share of the small scale person traffic population", defaultValue = "0.0")
	private double remainingShareSmallScalePersonTraffic;

	@CommandLine.Option(names = "--remainingShare_smallScale_goodsTraffic", description = "Remaining share of the small scale goods traffic population", defaultValue = "0.0")
	private double remainingShareSmallScaleGoodsTraffic;

	private final Random rnd = MatsimRandom.getRandom();

	public static void main(String[] args) {
		System.exit(new CommandLine(new PopulationSamplingBySubpopulations()).execute(args));
	}

	@Override
	public Integer call() {

		Population originalPopulation = PopulationUtils.readPopulation(inputPopulation.toString());

		Object2DoubleOpenHashMap<String> countedOriginalAgentsPerSubpopulation = new Object2DoubleOpenHashMap<>();

		originalPopulation.getPersons().values().stream().
			filter(person -> PopulationUtils.getSubpopulation(person) != null).
			forEach(person -> {
				String subpopulation = PopulationUtils.getSubpopulation(person);
				countedOriginalAgentsPerSubpopulation.addTo(subpopulation, 1.0);
			});

		int numberOfAgentsOriginalPopulation = originalPopulation.getPersons().size();

		for (String subpopulation : countedOriginalAgentsPerSubpopulation.keySet()) {
			if (subpopulation.equals("commercialPersonTraffic")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareSmallScalePersonTraffic, numberOfAgentsOriginalPopulation);
			} else if (subpopulation.equals("commercialPersonTraffic_service")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareSmallScalePersonTraffic,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.equals("goodsTraffic")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareSmallScaleGoodsTraffic,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.equals("longDistanceFreight")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareLongDistanceFreight,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.equals("FTL_trip")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareLongDistanceFreight,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.equals("FTL_kv_trip")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareLongDistanceFreight,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.contains("LTL_trips")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingShareLongDistanceFreight,
					numberOfAgentsOriginalPopulation);
			} else if (subpopulation.contains("person")) {
				sampleSubpopulation(originalPopulation, countedOriginalAgentsPerSubpopulation, subpopulation, remainingSharePersons,
					numberOfAgentsOriginalPopulation);
			} else
				log.error("Unknown subpopulation: {}", subpopulation);

		}

		int numberOfAgentsAfterReduction = originalPopulation.getPersons().size();
		double percentageOfPopulationReduction = 1 - (double) numberOfAgentsAfterReduction / numberOfAgentsOriginalPopulation;
		log.info("Percentage of the population reduction: {}", percentageOfPopulationReduction);
		String newOutputPopulationPath = outputPopulation.toString().replace("adjusted",
			"remainingPercentages_person" + remainingSharePersons + "_smallScalePersonTraffic" + remainingShareSmallScalePersonTraffic + "_smallScaleGoodsTraffic" + remainingShareSmallScaleGoodsTraffic + "_longDistanceFreight" + remainingShareLongDistanceFreight);

		PopulationUtils.writePopulation(originalPopulation, newOutputPopulationPath);
		return 0;
	}

	private void sampleSubpopulation(Population originalPopulation, Object2DoubleOpenHashMap<String> countedOriginalAgentsPerSubpopulation,
									 String subpopulation, double remainingShareOfThisSubpopulation, int numberOfAgentsOriginalPopulation) {
		log.info("Subpopulation: {}, size before sample: {}", subpopulation, countedOriginalAgentsPerSubpopulation.getDouble(subpopulation));
		double shareOfThisPopulation = countedOriginalAgentsPerSubpopulation.getDouble(subpopulation) / numberOfAgentsOriginalPopulation;
		log.info("Share of the subpopulation {} of the hole population: {}", subpopulation, shareOfThisPopulation);
		originalPopulation.getPersons().values().removeIf(
			person -> PopulationUtils.getSubpopulation(person).equals(subpopulation)
				&& rnd.nextDouble() >= remainingShareOfThisSubpopulation
		);
		int sampledPopulationSize = originalPopulation.getPersons().values().stream().filter(
			person -> PopulationUtils.getSubpopulation(person).equals(subpopulation)).toList().size();
		log.info("Subpopulation: {}, size after sample: {}", subpopulation, sampledPopulationSize);
	}

}

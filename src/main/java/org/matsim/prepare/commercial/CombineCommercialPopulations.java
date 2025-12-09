package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

/**
 * because the generation of the two types (commercial person traffic and commercial freight traffic) is separate, we can merge the results for two different runs here.
 * Note: both populations must have the same sampling rate!
 */
public class CombineCommercialPopulations implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CombineCommercialPopulations.class);

	@CommandLine.Option(names = "--populationFor_SmallScaleCommercialPersonTraffic", description = "Path to the population file for small scale commercial person traffic", required = true)
	private String populationFor_SmallScaleCommercialPersonTraffic;

	@CommandLine.Option(names = "--populationFor_SmallScaleCommercialGoodsTraffic", description = "Path to the population file for small scale commercial goods traffic", required = true)
	private String populationFor_SmallScaleCommercialFreightTraffic;

	@CommandLine.Option(names = "--outputPopulation", description = "Path to output the combined population file", required = true)
	private String outputPopulation;

	public static void main(String[] args) {
		new CombineCommercialPopulations().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		log.info("Population for Small Scale Commercial Person Traffic: {}", populationFor_SmallScaleCommercialPersonTraffic);
		Population populationKWM = PopulationUtils.readPopulation(populationFor_SmallScaleCommercialPersonTraffic);

		Population newPopulation = PopulationUtils.createPopulation(new PlansConfigGroup(), null, 0.1);
		// add all person of the small scale commercial person traffic population to the combined population
		populationKWM.getPersons().values().forEach(person -> {
			if (PopulationUtils.getSubpopulation(person).contains("commercialPersonTraffic")) {
				newPopulation.addPerson(person);
			}
		});
		log.info("Population for Small Scale Commercial Freight Traffic: {}", populationFor_SmallScaleCommercialFreightTraffic);
		populationKWM = PopulationUtils.readPopulation(populationFor_SmallScaleCommercialFreightTraffic);

		// add all person of the small scale commercial goods traffic population to the combined population
		populationKWM.getPersons().values().forEach(person -> {
			if (PopulationUtils.getSubpopulation(person).contains("goodsTraffic")) {
				newPopulation.addPerson(person);
			}
		});
		log.info("Writing combined commercial population to: {}", outputPopulation);
		PopulationUtils.writePopulation(newPopulation, outputPopulation);
		return 0;
	}
}

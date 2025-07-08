package org.matsim.prepare.commercial;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 *  Replaces the existing commercial agents in a population with the commercial agents from another population.
 */
public class ReplaceCommercialAgentsInPopulation implements MATSimAppCommand {

		private static final Logger log = LogManager.getLogger(ReplaceCommercialAgentsInPopulation.class);

		@CommandLine.Option(names = "--existingPopulation", description = "Paths of the existing population files to replace commercial agents", required = true)
		private Path existingPopulationPath;

		@CommandLine.Option(names = "--commercialPopulation", description = "Path to the commercial population file", required = true)
		private Path commercialPopulationPath;

		@CommandLine.Option(names = "--output", description = "Path to output", required = true)
		private Path output;

		public static void main(String[] args) {
			new ReplaceCommercialAgentsInPopulation().execute(args);
		}

		@Override
		public Integer call() throws Exception {
			log.info("Existing population file: {}", existingPopulationPath);
			log.info("Commercial population file: {}", commercialPopulationPath);

			log.info("Adding all subpopulations from the existing population to the commercial population which are not already present in the commercial population.");

			Population population = PopulationUtils.readPopulation(commercialPopulationPath.toString());

			List<String> subpopulations = new ArrayList<>(population.getPersons().values().stream()
				.map(PopulationUtils::getSubpopulation)
				.distinct()
				.toList());
			// because this a deprecated subpopulation
			subpopulations.add("LTL_trips");
			Population existingPopulation = PopulationUtils.readPopulation(existingPopulationPath.toString());

			log.info("Subpopulations in commercial population: {}", subpopulations);
			log.info("Number of commercial agents in new commercial population: {}", population.getPersons().size());
			log.info("Number of agents in existing population: {}", existingPopulation.getPersons().size());
			int countNotAdded = 0;

			for(Person person : existingPopulation.getPersons().values()){
				String subpopulation = PopulationUtils.getSubpopulation(person);
				if (subpopulation == null || !subpopulations.contains(subpopulation)) {
					if (population.getPersons().get(person.getId()) != null)
						log.info("Person {} with subpopulation {} already exists in the commercial population, not adding again.", person.getId(), subpopulation);
					population.addPerson(person);
				}
				else countNotAdded++;
			}
			log.info("Number of commercial agents of the existing population not added to the new population: {}", countNotAdded);
			log.info("Number of agents in resulting population: {}", population.getPersons().size());

			log.info("Writing population file to: {}", output);
			PopulationWriter pw = new PopulationWriter(population);
			pw.write(output.toString());
			return 0;
		}
}

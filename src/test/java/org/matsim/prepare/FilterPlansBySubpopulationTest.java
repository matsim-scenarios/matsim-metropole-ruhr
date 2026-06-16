package org.matsim.prepare;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.List;
import java.util.Set;

class FilterPlansBySubpopulationTest {

	@Test
	void removesDefaultCommercialSubpopulations() {
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		addPerson(population, "person-without-subpopulation", null);
		addPerson(population, "person", "person");
		addPerson(population, "commercialPersonTraffic", "commercialPersonTraffic");
		addPerson(population, "commercialPersonTraffic_service", "commercialPersonTraffic_service");
		addPerson(population, "goodsTraffic", "goodsTraffic");
		addPerson(population, "longDistanceFreight", "longDistanceFreight");
		addPerson(population, "FTL_trip", "FTL_trip");
		addPerson(population, "FTL_kv_trip", "FTL_kv_trip");
		addPerson(population, "LTL_trip", "LTL_trip");
		addPerson(population, "LTL_trips", "LTL_trips");

		FilterPlansBySubpopulation.FilterResult result = FilterPlansBySubpopulation.filter(
			population,
			List.of(),
			FilterPlansBySubpopulation.DEFAULT_COMMERCIAL_SUBPOPULATIONS,
			true
		);

		Assertions.assertEquals(8, result.removedPersons());
		Assertions.assertEquals(Set.of(Id.createPersonId("person-without-subpopulation"), Id.createPersonId("person")), population.getPersons().keySet());
	}

	@Test
	void keepsOnlyRequestedSubpopulations() {
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		addPerson(population, "person", "person");
		addPerson(population, "commercialPersonTraffic", "commercialPersonTraffic");
		addPerson(population, "goodsTraffic", "goodsTraffic");

		FilterPlansBySubpopulation.FilterResult result = FilterPlansBySubpopulation.filter(
			population,
			List.of("goodsTraffic"),
			List.of(),
			false
		);

		Assertions.assertEquals(2, result.removedPersons());
		Assertions.assertEquals(Set.of(Id.createPersonId("goodsTraffic")), population.getPersons().keySet());
	}

	private static void addPerson(Population population, String id, String subpopulation) {
		Person person = population.getFactory().createPerson(Id.createPersonId(id));
		if (subpopulation != null) {
			PopulationUtils.putSubpopulation(person, subpopulation);
		}
		population.addPerson(person);
	}
}

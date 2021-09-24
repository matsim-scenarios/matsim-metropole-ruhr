package org.matsim.run.strategy;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;

/**
 * Creates full plans for a person of only a single mode.
 */
public class CreateSingleModePlans implements PersonAlgorithm {

	@Override
	public void run(Person person) {

		for (String mode : List.of("pt", "car")) {

			Plan plan = PopulationUtils.createPlan(person);

			PopulationUtils.copyFromTo(person.getSelectedPlan(), plan);

			List<Leg> legs = TripStructureUtils.getLegs(plan);

			for (Leg leg : legs) {

				// walks are not forced to change (probably short trips)
				if ("walk".equals(TripStructureUtils.getRoutingMode(leg)) || leg.getMode().equals("walk"))
					continue;

				leg.setMode(mode);
				TripStructureUtils.setRoutingMode(leg, mode);
			}

			plan.setType(mode);
			person.addPlan(plan);
		}
	}
}

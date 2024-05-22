package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.Map;

/**
 * Change the mode of the legs of the commercial plans to the corresponding vehicle type.
 *
 * @author Ricardo Ewert
 */
public class ChangeModesForCommercialPlans {
	public static void main(String[] args) {
		String pathPopulation = "scenarios/metropole-ruhr-v2.0/output/rvr/commercial_3pct_V2/ruhr_freightPlans_3pct_merged.plans.xml.gz";
		String pathOutputPopulation = "scenarios/metropole-ruhr-v2.0/output/rvr/commercial_3pct_V2/ruhr_commercial_3pct.plans.xml.gz";
		Population population = PopulationUtils.readPopulation(pathPopulation);

		for (Person person : population.getPersons().values()) {
			String newMode = "";
			if (PopulationUtils.getSubpopulation(person).equals("longDistanceFreight"))
				newMode = "truck40t";
			else if (PopulationUtils.getSubpopulation(person).equals("FTL_kv_trip")){
				newMode = "truck40t";
				Id<VehicleType> vehicleTypeId = Id.create("heavy40t", VehicleType.class);
				VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, Map.of(newMode, vehicleTypeId));
				person.getAttributes().removeAttribute("vehicles");

			}
			else if (PopulationUtils.getSubpopulation(person).equals("goodsTraffic")){
				Id<VehicleType> vehicleTypeId = VehicleUtils.getVehicleTypes(person).values().iterator().next();
				if (vehicleTypeId.toString().contains("mercedes313") || vehicleTypeId.toString().contains("vwCaddy")){
					newMode = "car";
				} else if (vehicleTypeId.toString().contains("heavy40t")){
					newMode = "truck40t";
				} else if (vehicleTypeId.toString().contains("medium18t")){
					newMode = "truck18t";
				} else if (vehicleTypeId.toString().contains("light8t")) {
					newMode = "truck8t";
				}
				person.getAttributes().removeAttribute("vehicleTypes");
				VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, Map.of(newMode, vehicleTypeId));
				person.getAttributes().removeAttribute("vehicles");
			}
			else
				continue;
			changeLegModes(person, newMode);
		}
		PopulationUtils.writePopulation(population, pathOutputPopulation);
	}

	private static void changeLegModes(Person person, String newMode) {
		person.getPlans().forEach( plan -> plan.getPlanElements().forEach(planElement -> {
			if (planElement instanceof Leg leg) {
				leg.setMode(newMode);
			}
		}));
	}
}

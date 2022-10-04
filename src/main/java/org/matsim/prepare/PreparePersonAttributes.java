package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.algorithms.PersonAlgorithm;

import java.util.Random;

/**
 * Sets person attributes of a population
 */
public class PreparePersonAttributes implements PersonAlgorithm {

	private static final Logger log = LogManager.getLogger(PreparePersonAttributes.class);
	private final Random rnd = new Random(1234);

	@Override
	public void run(Person person) {
		// Set car availability to "never" for agents below 18 years old
		// Standardize the attribute "age"
		String avail = "always";
		Object age = person.getAttributes().getAttribute("microm:modeled:age");
		if (age != null) {
			PersonUtils.setAge(person, (int) age);
			person.getAttributes().removeAttribute("microm:modeled:age");
			if ((int) age < 18) {
				avail = "never";
			}
		}
		PersonUtils.setCarAvail(person, avail);
		person.getAttributes().removeAttribute("sim_carAvailability"); // Replace with standardized car availability attribute

		// Standardize the attribute "sex"
		Object sex = person.getAttributes().getAttribute("microm:modeled:sex");
		if (sex != null) {
			PersonUtils.setSex(person, (String) sex);
			person.getAttributes().removeAttribute("microm:modeled:sex");
		}

		// Assign income to person (skip the freight agents)
		if (person.getId().toString().startsWith("freight")) {
			return;
		}

		String incomeGroupString = (String) person.getAttributes().getAttribute("MiD:hheink_gr2");
		String householdSizeString = (String) person.getAttributes().getAttribute("MiD:hhgr_gr");
		int incomeGroup = 0;
		double householdSize = 1;
		if (incomeGroupString != null && householdSizeString != null) {
			incomeGroup = Integer.parseInt(incomeGroupString);
			householdSize = Double.parseDouble(householdSizeString);
		}

		double income = 0;
		switch (incomeGroup) {
			case 1:
				income = 500 / householdSize;
				break;
			case 2:
				income = (rnd.nextInt(400) + 500) / householdSize;
				break;
			case 3:
				income = (rnd.nextInt(600) + 900) / householdSize;
				break;
			case 4:
				income = (rnd.nextInt(500) + 1500) / householdSize;
				break;
			case 5:
				income = (rnd.nextInt(1000) + 2000) / householdSize;
				break;
			case 6:
				income = (rnd.nextInt(1000) + 3000) / householdSize;
				break;
			case 7:
				income = (rnd.nextInt(1000) + 4000) / householdSize;
				break;
			case 8:
				income = (rnd.nextInt(1000) + 5000) / householdSize;
				break;
			case 9:
				income = (rnd.nextInt(1000) + 6000) / householdSize;
				break;
			case 10:
				income = (Math.abs(rnd.nextGaussian()) * 1000 + 7000) / householdSize;
				break;
			default:
				income = 2364; // Average monthly household income per Capita (2021). See comments below for details
				break;
			// Average Gross household income: 4734 Euro
			// Average household size: 83.1M persons /41.5M households = 2.0 persons / household
			// Average household income per capita: 4734/2.0 = 2364 Euro
			// Source (Access date: 21 Sep. 2021):
			// https://www.destatis.de/EN/Themes/Society-Environment/Income-Consumption-Living-Conditions/Income-Receipts-Expenditure/_node.html
			// https://www.destatis.de/EN/Themes/Society-Environment/Population/Households-Families/_node.html
			// https://www.destatis.de/EN/Themes/Society-Environment/Population/Current-Population/_node.html;jsessionid=E0D7A060D654B31C3045AAB1E884CA75.live711
		}
		PersonUtils.setIncome(person, income);
	}
}
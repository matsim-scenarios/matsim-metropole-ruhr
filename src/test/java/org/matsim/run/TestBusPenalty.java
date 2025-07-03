package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.carriers.Tour;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.util.ArrayList;
import java.util.List;

public class TestBusPenalty {


	@RegisterExtension
	public MatsimTestUtils testUtils = new MatsimTestUtils();

	@Test
	public void testPenalty() {
		MATSimApplication.execute(testClass.class, "--run --config.global.numberOfThreads=2 config:qsim.numberOfThreads=2");
	}

	public static class testClass extends MetropoleRuhrScenario {
		@Override
		protected Config prepareConfig(Config config) {
			super.prepareConfig(config);
			config.controller().setLastIteration(0);
			config.removeModule(String.valueOf(SimWrapperConfigGroup.class));
			return config;
		}

		@Override
		protected void prepareScenario(Scenario scenario) {
			super.prepareScenario(scenario);
			//only thousand agents to speed it up
			retainPtUsersOnly(scenario);
		}

		@Override
		protected void prepareControler(Controler controler) {
			super.prepareControler(controler);
		}
	}
}






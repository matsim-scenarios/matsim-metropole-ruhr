/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.run;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup.AccessEgressType;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.scenario.ScenarioUtils;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;

public class RunMetropoleRuhrScenario {

	private static final Logger log = Logger.getLogger(RunMetropoleRuhrScenario.class);

	public static void main(String[] args) {
		
		for (String arg : args) {
			log.info( arg );
		}
		
		if (args.length == 0) {
			args = new String[] {"./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-1pct.config.xml"};
		}

		Config config = loadConfig(args);
		Scenario scenario = loadScenario(config);
		Controler controler = loadControler(scenario);
		controler.run();
		
		log.info("Simulation completed.");
	}

	public static Config loadConfig(String[] args, ConfigGroup... modules) {

		OutputDirectoryLogging.catchLogEntries();

		BicycleConfigGroup bikeConfigGroup = new BicycleConfigGroup();
		bikeConfigGroup.setBicycleMode(TransportMode.bike);

		List<ConfigGroup> moduleList = new ArrayList<>(Arrays.asList(modules));
		moduleList.add(bikeConfigGroup);

		Config config = ConfigUtils.loadConfig(args, moduleList.toArray(ConfigGroup[]::new));

		config.plansCalcRoute().setAccessEgressType(AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		for (long ii = 600; ii <= 97200; ii += 600) {

			for (String act : List.of("home",
					"restaurant",
					"other",
					"visit",
					"errands",
					"educ_higher",
					"educ_secondary")) {
				config.planCalcScore().addActivityParams(new ActivityParams(act + "_" + ii + ".0").setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new ActivityParams("work_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("business_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("leisure_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("shopping_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		return config;
	}

	public static Scenario loadScenario(Config config) {

		return ScenarioUtils.loadScenario(config);
	}

	public static Controler loadControler(Scenario scenario) {

		Controler controler = new Controler(scenario);
		if (!controler.getConfig().transit().isUsingTransitInMobsim())
			throw new RuntimeException("Public transit will be teleported and not simulated in the mobsim! "
					+ "This will have a significant effect on pt-related parameters (travel times, modal split, and so on). "
					+ "Should only be used for testing or car-focused studies with fixed modal split.");

		controler.addOverridingModule(new SwissRailRaptorModule());
		
		// use the (congested) car travel time for the teleported ride mode
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
			}
		});

		Bicycles.addAsOverridingModule(controler);
		return controler;
	}
}

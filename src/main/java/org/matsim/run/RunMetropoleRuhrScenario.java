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

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.common.collect.Sets;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup.AccessEgressType;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import picocli.CommandLine;

import java.util.List;
import java.util.Set;

@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = "v1.0")
public class RunMetropoleRuhrScenario extends MATSimApplication {

	private static final Logger log = Logger.getLogger(RunMetropoleRuhrScenario.class);

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(25, 10, 1);

	public RunMetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-1pct.config.xml");
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMetropoleRuhrScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		OutputDirectoryLogging.catchLogEntries();

		//BicycleConfigGroup bikeConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
		//bikeConfigGroup.setBicycleMode(TransportMode.bike);


		config.plansCalcRoute().setAccessEgressType(AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		for (long ii = 600; ii <= 97200; ii += 600) {

			for (String act : List.of("home", "restaurant", "other", "visit", "errands",
					"educ_higher", "educ_secondary", "educ_primary", "educ_tertiary", "educ_kiga", "educ_other")) {
				config.planCalcScore()
						.addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams(act + "_" + ii + ".0").setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("work_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("business_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("leisure_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_daily_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_other_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		// Nothing to do yet

	}

	@Override
	protected void prepareControler(Controler controler) {

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

				addTravelTimeBinding(TransportMode.bike).to(networkTravelTime());

				bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);
			}
		});

		//Bicycles.addAsOverridingModule(controler);
	}
}

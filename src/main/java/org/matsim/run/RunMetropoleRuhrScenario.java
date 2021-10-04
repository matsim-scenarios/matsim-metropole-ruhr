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
import com.google.inject.name.Names;
import org.apache.log4j.Logger;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup.AccessEgressType;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.run.strategy.CreateSingleModePlans;
import org.matsim.run.strategy.PreCalibrationModeChoice;
import org.matsim.run.strategy.TuneModeChoice;
import picocli.CommandLine;

import javax.inject.Singleton;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = "v1.0")
public class RunMetropoleRuhrScenario extends MATSimApplication {

	private static final Logger log = Logger.getLogger(RunMetropoleRuhrScenario.class);

	public static final String URL = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(10, 25, 3, 1);

	@CommandLine.Option(names = "--pre-calibrate", defaultValue = "false", description = "Precalibrate without congestion and few iterations")
	private boolean preCalibration;

	@CommandLine.Option(names = "--download-input", defaultValue = "false", description = "Download input files from remote location")
	private boolean download;

	public RunMetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-10pct.config.xml");
	}

	/**
	 * Have this here for unit testing, the other constructor doesn't seem to work for that 🤷‍♀️
	 */
	RunMetropoleRuhrScenario(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunMetropoleRuhrScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		OutputDirectoryLogging.catchLogEntries();

		BicycleConfigGroup bikeConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
		bikeConfigGroup.setBicycleMode(TransportMode.bike);

		config.plansCalcRoute().setAccessEgressType(AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.subtourModeChoice().setProbaForRandomSingleTripMode(0.5);

		config.controler().setRunId(sample.adjustName(config.controler().getRunId()));
		config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));
		config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));

		if (sample.isSet()) {
			config.qsim().setFlowCapFactor(sample.getSize() / 100.0);
			config.qsim().setStorageCapFactor(sample.getSize() / 100.0);
		}

		if (download) {
			adjustURL(config.network()::getInputFile, config.network()::setInputFile);
			adjustURL(config.plans()::getInputFile, config.plans()::setInputFile);
			adjustURL(config.vehicles()::getVehiclesFile, config.vehicles()::setVehiclesFile);
			adjustURL(config.transit()::getVehiclesFile, config.transit()::setVehiclesFile);
			adjustURL(config.transit()::getTransitScheduleFile, config.transit()::setTransitScheduleFile);
		}

		if (preCalibration) {
			// no congestion
			config.qsim().setFlowCapFactor(10000);
			config.qsim().setStorageCapFactor(10000);
			config.controler().setLastIteration(50);

			config.strategy().setFractionOfIterationsToDisableInnovation(0.95);

			List<StrategyConfigGroup.StrategySettings> strategies = config.strategy().getStrategySettings().stream()
					.filter(s -> !s.getStrategyName().equals("SubtourModeChoice") && !s.getStrategyName().equals("ChangeSingleTripMode") && !s.getStrategyName().equals("ReRoute"))
					.collect(Collectors.toList());

			config.strategy().clearStrategySettings();

			strategies.forEach(s -> {
				if (s.getStrategyName().equals("ChangeExpBeta"))
					s.setWeight(0.1);
			});

			strategies.forEach(s -> config.strategy().addStrategySettings(s));

			StrategyConfigGroup.StrategySettings preCalibMode = new StrategyConfigGroup.StrategySettings();

			preCalibMode.setStrategyName("PreCalibrateMode");
			preCalibMode.setWeight(1.0);
			preCalibMode.setSubpopulation("person");

			config.strategy().addStrategySettings(preCalibMode);

			// Not creating new pt legs
			config.changeMode().setModes(new String[]{"car", "ride", "bike", "walk"});

			config.transit().setBoardingAcceptance(TransitConfigGroup.BoardingAcceptance.checkStopOnly);

			addRunOption(config, "pre-calibration");
		}

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
		if (preCalibration) {
			ParallelPersonAlgorithmUtils.run(scenario.getPopulation(), scenario.getConfig().global().getNumberOfThreads(), new CreateSingleModePlans());
		}

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

				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);

				if (preCalibration) {

					bind(PlanStrategy.class).annotatedWith(Names.named("PreCalibrateMode")).toProvider(PreCalibrationModeChoice.class);

				} else
					addControlerListenerBinding().to(TuneModeChoice.class).in(Singleton.class);

			}
		});

		Bicycles.addAsOverridingModule(controler);
	}

	/**
	 * Appends url to download a resource if not present.
	 */
	private void adjustURL(Supplier<String> getter, Consumer<String> setter) {

		String input = getter.get();
		if (input.startsWith("http"))
			return;

		String name = new File(input).getName();

		setter.accept(URL + name);
	}

}

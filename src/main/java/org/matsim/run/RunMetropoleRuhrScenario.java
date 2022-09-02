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
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import org.apache.log4j.Logger;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.analysis.TripMatrix;
import org.matsim.analysis.linkpaxvolumes.LinkPaxVolumesAnalysisModule;
import org.matsim.analysis.pt.stop2stop.PtStop2StopAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.analysis.travelTimeValidation.TravelTimeAnalysis;
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
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = RunMetropoleRuhrScenario.VERSION)
@MATSimApplication.Analysis({
		TravelTimeAnalysis.class, LinkStats.class, TripMatrix.class
})
public class RunMetropoleRuhrScenario extends MATSimApplication {

	public static final String VERSION = "v1.4";

	private static final Logger log = Logger.getLogger(RunMetropoleRuhrScenario.class);

	public static final String URL = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(10, 25, 3, 1);

	@CommandLine.Option(names = "--zero-bike-pcu", defaultValue = "false", description = "Set bike pcu to zero")
	private boolean zeroBikePCU;

	@CommandLine.Option(names = "--download-input", defaultValue = "false", description = "Download input files from remote location")
	private boolean download;

	@CommandLine.Option(names = "--income-dependent", defaultValue = "false", description = "Income dependent scoring", negatable = true)
	private boolean incomeDependent;

	/**
	 * Constructor for extending scenarios.
	 */
	protected RunMetropoleRuhrScenario(String defaultScenario) {
		super(defaultScenario);
	}

	public RunMetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-3pct.config.xml");
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

		if (sample.isSet()) {
			config.controler().setRunId(sample.adjustName(config.controler().getRunId()));
			config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));

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

		for (long ii = 600; ii <= 86400; ii += 600) {

			for (String act : List.of("home", "restaurant", "other", "visit", "errands",
					"educ_higher", "educ_secondary", "educ_primary", "educ_tertiary", "educ_kiga", "educ_other")) {
				config.planCalcScore()
						.addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams(act + "_" + ii).setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("work_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("business_" + ii).setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("leisure_" + ii).setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));

			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_daily_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("shop_other_" + ii).setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		if (zeroBikePCU) {
			Id<VehicleType> key = Id.create("bike", VehicleType.class);
			VehicleType bike = scenario.getVehicles().getVehicleTypes().get(key);
			bike.setPcuEquivalents(0);
		}

	}

	@Override
	protected void prepareControler(Controler controler) {

		if (!controler.getConfig().transit().isUsingTransitInMobsim())
			throw new RuntimeException("Public transit will be teleported and not simulated in the mobsim! "
					+ "This will have a significant effect on pt-related parameters (travel times, modal split, and so on). "
					+ "Should only be used for testing or car-focused studies with fixed modal split.");

		controler.addOverridingModule(new SwissRailRaptorModule());

		controler.addOverridingModule(new LinkPaxVolumesAnalysisModule());
		controler.addOverridingModule(new PtStop2StopAnalysisModule());

		if (incomeDependent) {
			log.info("Using income dependent scoring");
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).in(Singleton.class);
				}
			});
		}

		// use the (congested) car travel time for the teleported ride mode
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());

				addTravelTimeBinding(TransportMode.bike).to(networkTravelTime());

				bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);

				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);

				// Configure mode-choice strategy
				addControlerListenerBinding().to(StrategyWeightFadeout.class).in(Singleton.class);
				Multibinder<StrategyWeightFadeout.Schedule> schedules = Multibinder.newSetBinder(binder(), StrategyWeightFadeout.Schedule.class);
				schedules.addBinding().toInstance(new StrategyWeightFadeout.Schedule(DefaultPlanStrategiesModule.DefaultStrategy.SubtourModeChoice, "person", 0.75, 0.85));
				schedules.addBinding().toInstance(new StrategyWeightFadeout.Schedule(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute, "person", 0.78));


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

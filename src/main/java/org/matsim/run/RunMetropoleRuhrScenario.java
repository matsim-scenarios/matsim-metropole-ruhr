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

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.analysis.TripMatrix;
import org.matsim.analysis.linkpaxvolumes.LinkPaxVolumesAnalysisModule;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.analysis.pt.stop2stop.PtStop2StopAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleModule;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ChangeModeConfigGroup;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.SubtourModeChoiceConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.extensions.pt.PtExtensionsConfigGroup;
import org.matsim.extensions.pt.fare.intermodalTripFareCompensator.IntermodalTripFareCompensatorConfigGroup;
import org.matsim.extensions.pt.fare.intermodalTripFareCompensator.IntermodalTripFareCompensatorsConfigGroup;
import org.matsim.extensions.pt.fare.intermodalTripFareCompensator.IntermodalTripFareCompensatorsModule;
import org.matsim.extensions.pt.routing.EnhancedRaptorIntermodalAccessEgress;
import org.matsim.extensions.pt.routing.ptRoutingModes.PtIntermodalRoutingModesConfigGroup;
import org.matsim.extensions.pt.routing.ptRoutingModes.PtIntermodalRoutingModesModule;
import org.matsim.prepare.AdjustDemand;
import org.matsim.prepare.CreateSupply;
import org.matsim.prepare.RuhrUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;
import playground.vsp.simpleParkingCostHandler.ParkingCostConfigGroup;
import playground.vsp.simpleParkingCostHandler.ParkingCostModule;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.matsim.core.config.groups.PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLinkPlusTimeConstant;


@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = RunMetropoleRuhrScenario.VERSION, showDefaultValues = true)
@MATSimApplication.Analysis({
		LinkStats.class, TripMatrix.class
})
@MATSimApplication.Prepare({AdjustDemand.class})
public class RunMetropoleRuhrScenario extends MATSimApplication {

	public static final String VERSION = "v1.4";

	private static final Logger log = LogManager.getLogger(RunMetropoleRuhrScenario.class);

	public static final String URL = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(10, 25, 3, 1);

	@CommandLine.Option(names = "--zero-bike-pcu", defaultValue = "false", description = "Set bike pcu to zero")
	private boolean zeroBikePCU;

	@CommandLine.Option(names = "--download-input", defaultValue = "false", description = "Download input files from remote location")
	private boolean download;

	@CommandLine.Option(names = "--no-intermodal", defaultValue = "true", description = "Enable or disable intermodal routing", negatable = true)
	protected boolean intermodal;

	/**
	 * Constructor for extending scenarios.
	 */
	protected RunMetropoleRuhrScenario(String defaultScenario) {
		super(defaultScenario);
	}

	public RunMetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-" + VERSION +"-3pct.config.xml");
	}

	/**
	 * Have this here for unit testing, the other constructor doesn't seem to work for that 🤷‍♀️
	 */
	RunMetropoleRuhrScenario(Config config) {
		super(config);
	}

	public static void main(String[] args) {
//		args = new String [] {
//				"--1pct"
//				, "--config:controler.lastIteration", "0"
//				, "run"
//		};
		MATSimApplication.run(RunMetropoleRuhrScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		// avoid unmaterialized config group exceptions in general for PtExtensionsConfigGroup, IntermodalTripFareCompensatorsConfigGroup
		// avoid unmaterialized config group exceptions in tests for PtIntermodalRoutingModesConfigGroup, SwissRailRaptorConfigGroup
		PtExtensionsConfigGroup ptExtensionsConfigGroup = ConfigUtils.addOrGetModule(config, PtExtensionsConfigGroup.class);
		IntermodalTripFareCompensatorsConfigGroup intermodalTripFareCompensatorsConfigGroup = ConfigUtils.addOrGetModule(config, IntermodalTripFareCompensatorsConfigGroup.class);
		PtIntermodalRoutingModesConfigGroup ptIntermodalRoutingModesConfigGroup = ConfigUtils.addOrGetModule(config, PtIntermodalRoutingModesConfigGroup.class);
		SwissRailRaptorConfigGroup swissRailRaptorConfigGroup = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

		// because vsp default reasons
		config.facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.onePerActivityLinkInPlansFile);

		// someone wished to have an easy option to remove all intermodal functionality, so remove it from config or switch off
		if (!intermodal) {

			log.info("Disabling intermodal config...");

			// remove config options
			SubtourModeChoiceConfigGroup subtourModeChoice = config.subtourModeChoice();

			// intermodal pt should not be a chain-based mode, otherwise those would have to be modified too
			subtourModeChoice.setModes(
					Arrays.stream(subtourModeChoice.getModes())
					.filter(s -> !s.equals("pt_intermodal_allowed"))
					.toArray(String[]::new)
			);


			ChangeModeConfigGroup changeModeConfigGroup = config.changeMode();
			changeModeConfigGroup.setModes(
					Arrays.stream(changeModeConfigGroup.getModes())
							.filter(s -> !s.equals("pt_intermodal_allowed"))
							.toArray(String[]::new)
			);


			swissRailRaptorConfigGroup.setUseIntermodalAccessEgress(false);
			List<SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet> intermodalAccessEgressParameterSets =
					swissRailRaptorConfigGroup.getIntermodalAccessEgressParameterSets();
			intermodalAccessEgressParameterSets.clear();

			PtExtensionsConfigGroup.IntermodalAccessEgressModeUtilityRandomization[] intermodalAccessEgressModeUtilityRandomizationArray =
					ptExtensionsConfigGroup.getIntermodalAccessEgressModeUtilityRandomizations().
							toArray(new PtExtensionsConfigGroup.IntermodalAccessEgressModeUtilityRandomization[0]);
			for (PtExtensionsConfigGroup.IntermodalAccessEgressModeUtilityRandomization intermodalAccessEgressModeUtilityRandomization : intermodalAccessEgressModeUtilityRandomizationArray) {
				intermodalTripFareCompensatorsConfigGroup.removeParameterSet(intermodalAccessEgressModeUtilityRandomization);
			}

			IntermodalTripFareCompensatorConfigGroup[] intermodalTripFareCompensatorConfigGroupArray =
					intermodalTripFareCompensatorsConfigGroup.getIntermodalTripFareCompensatorConfigGroups().
							toArray(new IntermodalTripFareCompensatorConfigGroup[0]);
			for (IntermodalTripFareCompensatorConfigGroup intermodalTripFareCompensatorConfigGroup : intermodalTripFareCompensatorConfigGroupArray) {
				intermodalTripFareCompensatorsConfigGroup.removeParameterSet(intermodalTripFareCompensatorConfigGroup);
			}

			PtIntermodalRoutingModesConfigGroup.PtIntermodalRoutingModeParameterSet[] ptIntermodalRoutingModeParameterArrays =
					ptIntermodalRoutingModesConfigGroup.getPtIntermodalRoutingModeParameterSets().
							toArray(new PtIntermodalRoutingModesConfigGroup.PtIntermodalRoutingModeParameterSet[0]);
			for (PtIntermodalRoutingModesConfigGroup.PtIntermodalRoutingModeParameterSet ptIntermodalRoutingModeParameterArray : ptIntermodalRoutingModeParameterArrays) {
				ptIntermodalRoutingModesConfigGroup.removeParameterSet(ptIntermodalRoutingModeParameterArray);
			}
		}

		OutputDirectoryLogging.catchLogEntries();

		// bike contrib is needed for bike highways and elevation routing
		BicycleConfigGroup bikeConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
		bikeConfigGroup.setBicycleMode(TransportMode.bike);

		// this is needed for the parking cost money events
		ParkingCostConfigGroup parkingCostConfigGroup = ConfigUtils.addOrGetModule(config, ParkingCostConfigGroup.class);
		parkingCostConfigGroup.setFirstHourParkingCostLinkAttributeName(RuhrUtils.ONE_HOUR_P_COST);
		parkingCostConfigGroup.setExtraHourParkingCostLinkAttributeName(RuhrUtils.EXTRA_HOUR_P_COST);
		parkingCostConfigGroup.setMaxDailyParkingCostLinkAttributeName(RuhrUtils.MAX_DAILY_P_COST);
		parkingCostConfigGroup.setMaxParkingDurationAttributeName(RuhrUtils.MAX_P_TIME);
		parkingCostConfigGroup.setParkingPenaltyAttributeName(RuhrUtils.P_FINE);
		parkingCostConfigGroup.setResidentialParkingFeeAttributeName(RuhrUtils.RES_P_COSTS);

		log.info("using accessEgressModeToLinkPlusTimeConstant");
		// we do this to model parking search traffic, as on some links car agents have additional travel time
		config.plansCalcRoute().setAccessEgressType(accessEgressModeToLinkPlusTimeConstant);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);

		SimWrapperConfigGroup simWrapperConfigGroup = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		// adjust if sample size specific parameters
		if (sample.isSet()) {
			config.controler().setRunId(sample.adjustName(config.controler().getRunId()));
			config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));

			config.qsim().setFlowCapFactor(sample.getSize() / 100.0);
			config.qsim().setStorageCapFactor(sample.getSize() / 100.0);

			simWrapperConfigGroup.defaultParams().sampleSize = Double.valueOf(String.valueOf(sample.getSample()));
		}

		// changes so that input is downloaded
		if (download) {
			adjustURL(config.network()::getInputFile, config.network()::setInputFile);
			adjustURL(config.plans()::getInputFile, config.plans()::setInputFile);
			adjustURL(config.vehicles()::getVehiclesFile, config.vehicles()::setVehiclesFile);
			adjustURL(config.transit()::getVehiclesFile, config.transit()::setVehiclesFile);
			adjustURL(config.transit()::getTransitScheduleFile, config.transit()::setTransitScheduleFile);
		}

		// snz activtiy types that are always the same, Differentiated by typical duration
		SnzActivities.addScoringParams(config);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		//TODO ask Janek if he has no idea --> delete
		if (zeroBikePCU) {
			Id<VehicleType> key = Id.create("bike", VehicleType.class);
			VehicleType bike = scenario.getVehicles().getVehicleTypes().get(key);
			bike.setPcuEquivalents(0);
		}

		VehicleType bike = scenario.getVehicles().getVehicleTypes().get(Id.create("bike", VehicleType.class));
		bike.setNetworkMode(TransportMode.bike);
	}

	@Override
	protected void prepareControler(Controler controler) {

		if (!controler.getConfig().transit().isUsingTransitInMobsim()) {
			log.error("Public transit will be teleported and not simulated in the mobsim! "
					+ "This will have a significant effect on pt-related parameters (travel times, modal split, and so on). "
					+ "Should only be used for testing or car-focused studies with fixed modal split.");
			throw new IllegalArgumentException("Pt is teleported, wich is not supported");
		}

		controler.addOverridingModule(new SimWrapperModule());

		// allow for separate pt routing modes (pure walk+pt, bike+walk+pt, car+walk+pt, ...)
		controler.addOverridingModule(new PtIntermodalRoutingModesModule());
		// throw additional score or money events if pt is combined with bike or car in the same trip
		controler.addOverridingModule(new IntermodalTripFareCompensatorsModule());

		// additional analysis output
		controler.addOverridingModule(new LinkPaxVolumesAnalysisModule());
		controler.addOverridingModule(new PtStop2StopAnalysisModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {

				// use the (congested) car travel time for the teleported ride mode
				addTravelTimeBinding(TransportMode.ride).to(networkTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
				addTravelTimeBinding(TransportMode.bike).to(networkTravelTime());
				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);

				// calculate access/egress leg generalized cost correctly for intermodal pt routing
				bind(RaptorIntermodalAccessEgress.class).to(EnhancedRaptorIntermodalAccessEgress.class);
				// separate pure walk+pt from intermodal pt in mode stats etc.
				bind(AnalysisMainModeIdentifier.class).to(IntermodalPtAnalysisModeIdentifier.class);

				// for income dependent scoring --> this works with the bicycle contrib as we don´t use the scoring in the bicycle contrib
				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).in(Singleton.class);

			}
		});


		log.info("Adding money event analysis");

		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());

		//this is needed for  the parking cost
		controler.addOverridingModule(new ParkingCostModule());
		// bicycle contrib
		controler.addOverridingModule(new BicycleModule());
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
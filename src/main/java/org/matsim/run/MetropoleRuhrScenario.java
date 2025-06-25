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
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.analysis.TripMatrix;
import org.matsim.analysis.linkpaxvolumes.LinkPaxVolumesAnalysisModule;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.analysis.pt.stop2stop.PtStop2StopAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleModule;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.population.PopulationUtils;
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
import org.matsim.prepare.RuhrUtils;
import org.matsim.run.scoring.AdvancedScoringConfigGroup;
import org.matsim.run.scoring.AdvancedScoringModule;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;
import playground.vsp.simpleParkingCostHandler.ParkingCostConfigGroup;
import playground.vsp.simpleParkingCostHandler.ParkingCostModule;

import java.util.*;

import static org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType.accessEgressModeToLinkPlusTimeConstant;


@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = MetropoleRuhrScenario.VERSION, showDefaultValues = true)
@MATSimApplication.Analysis({
		LinkStats.class, TripMatrix.class
})
@MATSimApplication.Prepare({AdjustDemand.class})
public class MetropoleRuhrScenario extends MATSimApplication {

	public static final String VERSION = "v2024.0";

	private static final Logger log = LogManager.getLogger(MetropoleRuhrScenario.class);

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(3, 10, 25, 1);

	@CommandLine.Option(names = "--no-intermodal", defaultValue = "true", description = "Enable or disable intermodal routing", negatable = true)
	protected boolean intermodal;

	/**
	 * Constructor for extending scenarios.
	 */
	protected MetropoleRuhrScenario(String defaultScenario) {
		super(defaultScenario);
	}

	public MetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-" + VERSION +"-3pct.config.xml");
	}

	/**
	 * Have this here for unit testing, the other constructor doesn't seem to work for that 🤷‍♀️
	 */
	MetropoleRuhrScenario(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		// (a (presumably crappy) way to give args from java instead of from the command line See KNRunMetropoleRuhrScenario)

		MATSimApplication.run(MetropoleRuhrScenario.class, args);
	}

	/**
	 * Prepare the config for commercial traffic.
	 */
	public static void prepareCommercialTrafficConfig(Config config) {

		Set<String> modes = Set.of("truck8t", "truck18t", "truck26t", "truck40t");

		modes.forEach(mode -> {
			ScoringConfigGroup.ModeParams thisModeParams = new ScoringConfigGroup.ModeParams(mode);
			config.scoring().addModeParams(thisModeParams);
		});

		Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
		config.qsim().setMainModes(Sets.union(qsimModes, modes));

		Set<String> networkModes = new HashSet<>(config.routing().getNetworkModes());
		config.routing().setNetworkModes(Sets.union(networkModes, modes));

		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("pickup").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("delivery").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("commercial_return").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("freight_return").setTypicalDuration(30 * 60));

		for (String subpopulation : List.of("LTL_trip", "commercialPersonTraffic", "commercialPersonTraffic_service", "longDistanceFreight",
			"FTL_trip", "FTL_kv_trip", "goodsTraffic")) {
			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
					.setWeight(0.85)
					.setSubpopulation(subpopulation)
			);

			config.replanning().addStrategySettings(
				new ReplanningConfigGroup.StrategySettings()
					.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
					.setWeight(0.1)
					.setSubpopulation(subpopulation)
			);
		}
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

		// this has no effect as described here https://github.com/matsim-org/matsim-libs/issues/3403, but is stated in order to avoid warnings from the
		// consistency checker
		bikeConfigGroup.setMaxBicycleSpeedForRouting(5.0);

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
		config.routing().setAccessEgressType(accessEgressModeToLinkPlusTimeConstant);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);

		SimWrapperConfigGroup simWrapperConfigGroup = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		// adjust if sample size specific parameters
		if (sample.isSet()) {
			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));

			config.qsim().setFlowCapFactor(sample.getSample());
			config.qsim().setStorageCapFactor(sample.getSample());

			simWrapperConfigGroup.sampleSize = sample.getSample();

			config.counts().setCountsScaleFactor(sample.getSample());
		}

		// snz activity types that are always the same, Differentiated by typical duration
		SnzActivities.addScoringParams(config);

		//ride scoring params
		// alpha can be calibrated
		double alpha = 2.0;
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(config.scoring(), alpha);

		prepareCommercialTrafficConfig(config);

		preparePtFareConfig(config);

		return config;
	}

	private static void preparePtFareConfig(Config config) {
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);

		// inside of RVR use the RVR Tarif
		FareZoneBasedPtFareParams rvr = new FareZoneBasedPtFareParams();
		rvr.setTransactionPartner("VRR");
		rvr.setDescription("VRR Tarifstufe A");
		rvr.setFareZoneShp("./pt-pricing/pt_preisstufen_fare_all3.0.shp");
		rvr.setOrder(1);

		// outside of RVR use the eezyVRR Tarif 1,50 EUR + 0.25 * Luftlinien-km.
		DistanceBasedPtFareParams eezy = new DistanceBasedPtFareParams();
		eezy.setTransactionPartner("eezyVRR");
		eezy.setDescription("eezyVRR");
		eezy.setFareZoneShp("./nrwArea/dvg2bld_nw.shp");
		DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams eezyFareFunction = eezy.getOrCreateDistanceClassFareParams(Double.POSITIVE_INFINITY);
		eezyFareFunction.setFareIntercept(1.5);
		eezyFareFunction.setFareSlope(0.00025);
		eezy.setOrder(2);

		DistanceBasedPtFareParams germany = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
		germany.setTransactionPartner("Deutschlandtarif");
		germany.setDescription("Deutschlandtarif");
		germany.setOrder(3);

		ptFareConfigGroup.addParameterSet(rvr);
		ptFareConfigGroup.addParameterSet(eezy);
		ptFareConfigGroup.addParameterSet(germany);

		//use upper bounds
		ptFareConfigGroup.setApplyUpperBound(true);
		ptFareConfigGroup.setUpperBoundFactor(1.5);
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		VehicleType bike = scenario.getVehicles().getVehicleTypes().get(Id.create("bike", VehicleType.class));
		bike.setNetworkMode(TransportMode.bike);
		//retainPtUsersOnly(scenario);
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

		controler.addOverridingModule(new PtFareModule());

		// AdvancedScoring as for matsim-berlin!
		if (ConfigUtils.hasModule(controler.getConfig(), AdvancedScoringConfigGroup.class)) {
			controler.addOverridingModule(new AdvancedScoringModule());
		} else {
			// if the above config group is not present we still need income dependent scoring
			// this implementation also allows for person specific asc

			// for income dependent scoring --> this works with the bicycle contrib as we don´t use the scoring in the bicycle contrib
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).in(Singleton.class);
				}
			});
		}

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
			}
		});


		log.info("Adding money event analysis");

		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());

		//this is needed for  the parking cost
		controler.addOverridingModule(new ParkingCostModule());
		// bicycle contrib
		controler.addOverridingModule(new BicycleModule());
		controler.addOverridingModule(new LinkPaxVolumesAnalysisModule());


		// add custom in-vehicle cost calculator that makes using the bus less attractive
		controler.addOverridingModule( new AbstractModule(){
			@Override public void install(){
				bind( DefaultRaptorInVehicleCostCalculator.class );
//				bind( CapacityDependentInVehicleCostCalculator.class );
				bind( RaptorInVehicleCostCalculator.class ).to( MyRaptorInVehicleCostCalculator.class );
			}
		} );


		// we somehow need the bus vehicle ids to punish bus users in the event Handler??
		//there must be a better way to do this....
		List<Id<Vehicle>> buses = getBusVehicleIds(controler);

		// add the bus punishment event handler
		controler.addOverridingModule(new AbstractModule(){
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(new BusPunishmentEventHandler(buses));
			}
		});
	}

	@NotNull
	private static List<Id<Vehicle>> getBusVehicleIds(Controler controler) {
		List<Id<Vehicle>> buses = new ArrayList<>();
		for (Vehicle vehicle: controler.getScenario().getTransitVehicles().getVehicles().values()) {
			if (vehicle.getType().getId().toString().contains("Bus")) {
				buses.add(vehicle.getId());
			}
		}
		return buses;
	}


	/*
	Here we use a custom in-vehicle cost calculator that makes using the bus less attractive.
	 */
	private static class MyRaptorInVehicleCostCalculator implements RaptorInVehicleCostCalculator {
		@Inject
		DefaultRaptorInVehicleCostCalculator delegate;
		//		@Inject CapacityDependentInVehicleCostCalculator delegate;
		@Override
		public double getInVehicleCost(double inVehicleTime, double marginalUtility_utl_s, Person person, Vehicle vehicle, RaptorParameters parameters, RouteSegmentIterator iterator ){
			double cost = delegate.getInVehicleCost( inVehicleTime, marginalUtility_utl_s, person, vehicle, parameters, iterator) ;
			if ( isBus( vehicle ) ) {
				//TODO find useful value for the bus penalty
				cost *= 1.2;
			}
			return cost;
		}
		private boolean isBus( Vehicle vehicle ){
			// somehow figure out if the vehicle is a bus or something else.
			return vehicle.getType().getId().toString().contains("Bus");
		}
	}

	/*
	 * This method retains only those persons in the population who use public transport in their selected plan.
	 */
	private static void retainPtUsersOnly(Scenario scenario) {
		var population = scenario.getPopulation();
		List<Person> ptUsers = new ArrayList<>();

		for (Person person: population.getPersons().values()) {
			Plan plan = person.getSelectedPlan();
			PopulationUtils.resetRoutes(plan);

			boolean usesPt = false;
			for (PlanElement pe : plan.getPlanElements()) {
				if (pe instanceof Leg) {
					String mode = ((Leg) pe).getMode();
					if (mode.equals("pt") || mode.startsWith("pt:")) {
						usesPt = true;
						break;
					}
				}
			}

			if (usesPt) {
				ptUsers.add(person);
			}
		}

		scenario.getPopulation().getPersons().clear();
		for (Person person: ptUsers) {
			scenario.getPopulation().addPerson(person);
		}

	}


}

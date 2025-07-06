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
import jakarta.validation.constraints.NotNull;
import org.apache.commons.math3.util.Pair;
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
import org.matsim.api.core.v01.network.Link;
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
import org.matsim.core.network.NetworkUtils;
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

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.matsim.core.config.groups.RoutingConfigGroup.AccessEgressType.accessEgressModeToLinkPlusTimeConstant;


@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = MetropoleRuhrScenario.VERSION, showDefaultValues = true)
@MATSimApplication.Analysis({
	LinkStats.class, TripMatrix.class
})
@MATSimApplication.Prepare({AdjustDemand.class})
public class MetropoleRuhrScenario extends MATSimApplication {

	public static final String VERSION = "v2.0";

	private static final double PCU_CAR = 1;
	private static final double PCU_TRUCK = 3.5;

	private static final Logger log = LogManager.getLogger(MetropoleRuhrScenario.class);

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(3, 10, 25, 1);

	@CommandLine.Option(names = "--no-intermodal", defaultValue = "true", description = "Enable or disable intermodal routing", negatable = true)
	protected boolean intermodal;

	@CommandLine.Option(names = "--adjust-capacities-to-dtv-counts", defaultValue = "false", description = "Enable or disable adjustment of capacities according to dtv", negatable = true)
	private boolean adjustCapacitiesToDtvCounts;

	@CommandLine.Option(names = "--adjust-capacities-to-bast-counts", defaultValue = "false", description = "Enable or disable adjustment of capacities according to bast", negatable = true)
	private boolean adjustCapacitiesToBastCounts;

	@CommandLine.Option(names = "--bast-car-counts", description = "Path to the BAST car counts CSV file")
	private Path bastCarCounts;

	@CommandLine.Option(names = "--bast-truck-counts", description = "Path to the BAST truck counts CSV file")
	private Path bastTruckCounts;

	@CommandLine.Option(names = "--dtv-counts", description = "Path to the DTV counts CSV file")
	private Path dtvCounts;

	/**
	 * Constructor for extending scenarios.
	 */
	protected MetropoleRuhrScenario(String defaultScenario) {
		super(defaultScenario);
	}

	public MetropoleRuhrScenario() {
		super("./scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-" + VERSION + "-3pct.config.xml");
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

		Set<String> modes = Set.of("freight", "truck8t", "truck18t", "truck26t", "truck40t");

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
		List<CapacityChange> capacityChanges = new ArrayList<>();

		if(adjustCapacitiesToDtvCounts){
			log.info("Adjusting network capacities to DTV counts...");
			adjustNetworkCapacitiesToDtvCounts(scenario, capacityChanges);
		}

		if(adjustCapacitiesToBastCounts){
			log.info("Adjusting network capacities to BAST counts...");
			adjustNetworkCapacitiesToBastCounts(scenario, capacityChanges);
		}

		if(adjustCapacitiesToBastCounts || adjustCapacitiesToDtvCounts) {
			log.info("Adjusting network capacities to BAST and DTV counts...");
			//write capacity changes to file
			try (BufferedWriter writer = new BufferedWriter(new FileWriter("adjustedCapacities.csv"))) {
				// Write header
				writer.write("source,link_id,simulated_traffic,observed_traffic,old_capacity,new_capacity");
				writer.newLine();

				// Write each record
				for (CapacityChange change : capacityChanges) {
					writer.write(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f",
						change.source(),
						change.linkId().toString(),
						change.simulatedTraffic(),
						change.observedTraffic(),
						change.oldCapacity(),
						change.newCapacity()));
					writer.newLine();
				}

				System.out.println("CSV written successfully");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		VehicleType bike = scenario.getVehicles().getVehicleTypes().get(Id.create("bike", VehicleType.class));
		bike.setNetworkMode(TransportMode.bike);

		NetworkUtils.writeNetwork(scenario.getNetwork(), "adjustedNetwork_forValidation.xml.gz");
		retainPtUsersOnly(scenario);
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
		//controler.addOverridingModule(new LinkPaxVolumesAnalysisModule());
		controler.addOverridingModule(new PtStop2StopAnalysisModule());

		controler.addOverridingModule(new PtFareModule());

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

	private void adjustNetworkCapacitiesToBastCounts(Scenario scenario, List<CapacityChange> capacityChanges) {
		// read counts from calibrated scenario
		Map<Id<Link>, List<BastCountEntry>> carCsvCountEntries = readBastCountsCsvFile(bastCarCounts);
		Map<Id<Link>, List<BastCountEntry>> truckCsvCountEntries = readBastCountsCsvFile(bastTruckCounts);

		Map<Id<Link>, Pair<Double, Double>> linkMaxHourlyObservedVolumes = new HashMap<>();
		for (Map.Entry<Id<Link>, List<BastCountEntry>> entry : carCsvCountEntries.entrySet()) {
			Id<Link> linkId = entry.getKey();
			List<BastCountEntry> countEntries = entry.getValue();

			// find the maximum observed traffic for this link
			double maxCarObservedTraffic = countEntries.stream()
				.mapToDouble(BastCountEntry::observedTraffic)
				.max()
				.orElse(0.0);

			double maxTruckObservedTraffic = truckCsvCountEntries.get(linkId).stream()
				.mapToDouble(BastCountEntry::observedTraffic)
				.max()
				.orElse(0.0);

			double maxCarSimulatedTraffic = countEntries.stream()
				.mapToDouble(BastCountEntry::simulatedTraffic)
				.max()
				.orElse(0.0);

			double maxTruckSimulatedTraffic = truckCsvCountEntries.get(linkId).stream()
				.mapToDouble(BastCountEntry::simulatedTraffic)
				.max()
				.orElse(0.0);

			linkMaxHourlyObservedVolumes.put(linkId, Pair.create(maxCarObservedTraffic * PCU_CAR + maxTruckObservedTraffic * PCU_TRUCK,
				maxCarSimulatedTraffic * PCU_CAR + maxTruckSimulatedTraffic * PCU_TRUCK));
		}

		for (Map.Entry<Id<Link>, Pair<Double, Double>> entry : linkMaxHourlyObservedVolumes.entrySet()) {
			Link link = scenario.getNetwork().getLinks().get(entry.getKey());
			Double simulated = entry.getValue().getSecond();
			Double observed = entry.getValue().getFirst();
			if (simulated > observed) {
				// If the simulated traffic is higher than the observed traffic, we adjust the capacity.
				// We do this because the simulated traffic should not exceed the observed traffic in order to match the counts (better).
				log.info("Adjusting capacity for link {}: simulated traffic ({}) > observed traffic ({}). Setting capacity to observed traffic.", link.getId(), simulated, observed);
				log.info("Link {}: old capacity = {}, new capacity = {}", link.getId(), link.getCapacity(), observed);

				if(observed>link.getCapacity()){
					log.info("This seems unplausible, as the observed traffic is higher than the current capacity. NOT adjusting capacity to observed traffic.");
					capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), link.getCapacity()));
					continue;
				}
				capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), observed));
				link.setCapacity(observed);
			} else {
				// If the simulated traffic is lower than the observed traffic, we do not adjust the capacity.
				capacityChanges.add(new CapacityChange("Bast", link.getId(), simulated, observed, link.getCapacity(), link.getCapacity()));
			}
		}
	}

	private void adjustNetworkCapacitiesToDtvCounts(Scenario scenario, List<CapacityChange> capacityChanges) {
		Map<Id<Link>, DtvCountEntry> idDtvCountEntryMap = readDtvCountsCsvFile(dtvCounts);

		Map<Id<Link>, List<BastCountEntry>> carBastCounts = readBastCountsCsvFile(bastCarCounts);
		Map<Id<Link>, List<BastCountEntry>> truckBastCounts = readBastCountsCsvFile(bastTruckCounts);

		List<Double> observerdSpitzenstundeFactor = new ArrayList<>();
		for (Map.Entry<Id<Link>, List<BastCountEntry>> entry : carBastCounts.entrySet()) {
			Id<Link> linkId = entry.getKey();

			double maxObservedFlow = -1;
			double sumObservedFlow = 0.;
			for (int i = 0; i < 24; i++) {
				double carObserved = entry.getValue().get(i).observedTraffic;
				double truckObserved = truckBastCounts.get(linkId).get(i).observedTraffic;
				double scaledObserved = carObserved * PCU_CAR + truckObserved * PCU_TRUCK;
				if (scaledObserved > maxObservedFlow) {
					maxObservedFlow = scaledObserved;
				}
				sumObservedFlow += scaledObserved;
			}

			observerdSpitzenstundeFactor.add(maxObservedFlow / sumObservedFlow);
		}

		double factor = observerdSpitzenstundeFactor.stream()
			.mapToDouble(Double::doubleValue)
			.average()
			.orElseThrow();

		log.info("Average factor for observed peak hour traffic: {}", factor);

		for (Map.Entry<Id<Link>, DtvCountEntry> entry : idDtvCountEntryMap.entrySet()) {
			Link link = scenario.getNetwork().getLinks().get(entry.getKey());
			double dailySimulated = entry.getValue().simulatedLkw * PCU_TRUCK + entry.getValue().simulatedPkw * PCU_CAR;
			double dailyObserved = entry.getValue().observedLkw * PCU_TRUCK + entry.getValue().observedPkw * PCU_CAR;
			if (dailySimulated > dailyObserved) {
				// If the dailySimulated traffic is higher than the dailyObserved traffic, we adjust the capacity.
				// We do this because the dailySimulated traffic should not exceed the dailyObserved traffic in order to match the counts (better).
				log.info("Adjusting capacity for link {}: dailySimulated traffic ({}) > dailyObserved traffic ({}). Setting capacity to dailyObserved traffic.", link.getId(), dailySimulated, dailyObserved);
				log.info("Link {}: old capacity = {}, new capacity = {}", link.getId(), link.getCapacity(), dailyObserved * factor);

				if(dailyObserved * factor > link.getCapacity()){
					log.info("This seems unplausible, as the observed traffic is higher than the current capacity. NOT adjusting capacity to observed traffic.");
					capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), link.getCapacity()));
					continue;
				}
				capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), dailyObserved * factor));
				link.setCapacity(dailyObserved * factor);
			} else {
				capacityChanges.add(new CapacityChange("dtv", link.getId(), dailySimulated * factor, dailyObserved * factor, link.getCapacity(), link.getCapacity()));
			}
		}
	}

	private static Map<Id<Link>, List<BastCountEntry>> readBastCountsCsvFile(Path csvFile) {
		String line;
		String csvSplitBy = ",";

		Map<Id<Link>, List<BastCountEntry>> csvCountEntries = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new FileReader(String.valueOf(csvFile)))) {
			br.readLine();
			while ((line = br.readLine()) != null) {
				// Zeile splitten
				String[] fields = line.split(csvSplitBy, -1); // -1 = alle Felder (auch leere)

				// Felder zuweisen (bei Bedarf parsen)
				Id<Link> linkId = Id.createLinkId(fields[0]);
				String name = fields[1];
				int hour = Integer.parseInt(fields[3]);
				double observedTraffic = Double.parseDouble(fields[4]);
				double simulatedTraffic = Double.parseDouble(fields[5]);

				csvCountEntries.computeIfAbsent(linkId, k -> new ArrayList<>()).add(new BastCountEntry(name, hour, observedTraffic, simulatedTraffic));
			}

			return csvCountEntries;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (NumberFormatException e) {
			System.err.println("Fehler beim Parsen von numerischen Werten.");
			e.printStackTrace();
		}
		return csvCountEntries;
	}

	private static Map<Id<Link>, DtvCountEntry> readDtvCountsCsvFile(Path csvFile) {
		String line;
		String csvSplitBy = ";";

		Map<Id<Link>, DtvCountEntry> result = new HashMap<>();
		try (BufferedReader br = new BufferedReader(new FileReader(String.valueOf(csvFile)))) {
			br.readLine();
			while ((line = br.readLine()) != null) {
				// Zeile splitten
				String[] fields = line.split(csvSplitBy, -1); // -1 = alle Felder (auch leere)

				// Id hin
				Id<Link> linkId_forward = Id.createLinkId(fields[0]);
				// Id zurück
				Id<Link> linkId_backward = Id.createLinkId(fields[1]);

				double observedPkw = Double.parseDouble(fields[2].replace("\"", ""));
				double observedLkw = Double.parseDouble(fields[3].replace("\"", ""));

				double simulatedPkw = Double.parseDouble(fields[6].replace("\"", "")); // (6!!)
				double simulatedLkw = Double.parseDouble(fields[5].replace("\"", "")); // (5!!)

				// We assume that the observed traffic is split evenly between forward and backward directions
				DtvCountEntry forwardEntry = new DtvCountEntry(observedPkw / 2, observedLkw / 2, simulatedPkw / 2, simulatedLkw / 2);
				result.put(linkId_forward, forwardEntry);

				DtvCountEntry backwardEntry = new DtvCountEntry(observedPkw / 2, observedLkw / 2, simulatedPkw / 2, simulatedLkw / 2);
				result.put(linkId_backward, backwardEntry);
			}
			return result;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (NumberFormatException e) {
			System.err.println("Fehler beim Parsen von numerischen Werten.");
			e.printStackTrace();
		}
		return result;
	}


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

		private static final AtomicInteger busLogCount = new AtomicInteger(0);
		//		@Inject CapacityDependentInVehicleCostCalculator delegate;
		@Override
		public double getInVehicleCost(double inVehicleTime, double marginalUtility_utl_s, Person person, Vehicle vehicle, RaptorParameters parameters, RouteSegmentIterator iterator ){
			double cost = delegate.getInVehicleCost( inVehicleTime, marginalUtility_utl_s, person, vehicle, parameters, iterator) ;
				cost *= 5.0; // make bus 5 times more expensive than other modes
			return cost;
		}
		private boolean isBus( Vehicle vehicle ){
			// somehow figure out if the vehicle is a bus or something else.
			return vehicle.getType().getId().toString().contains("Bus");
		}
	}


	private record BastCountEntry(String name, int hour, double observedTraffic, double simulatedTraffic) {
	}

	private record DtvCountEntry(double observedPkw, double observedLkw, double simulatedPkw, double simulatedLkw) {
	}

	private record CapacityChange(String source, Id<Link> linkId, double simulatedTraffic, double observedTraffic, double oldCapacity, double newCapacity) {
	}

	/*
	 * This method retains only those persons in the population who use public transport in their selected plan.
	 */
	public static void retainPtUsersOnly(Scenario scenario) {
		var population = scenario.getPopulation();
		List<Person> ptUsers = new ArrayList<>();

		for (Person person: population.getPersons().values()) {
			Plan plan = person.getSelectedPlan();
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
		System.out.println(scenario.getPopulation().getPersons().size());
		System.out.println("Retained " + ptUsers.size() + " persons who use public transport in their plans.");
	}


}

package org.matsim.run;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CommandLine.Command(name = "run-metropole-ruhr-commercial", description = "Runs the commercial traffic for the metropole ruhr scenario", showDefaultValues = true)
public class RunMetropoleRuhrScenarioCommercial implements MATSimAppCommand {

    private static final Logger log = LogManager.getLogger(RunMetropoleRuhrScenarioCommercial.class);

    @CommandLine.Option(names = "--configPath", description = "Path to the config file", required = true, defaultValue = "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml")
    private Path configPath;

    @CommandLine.Option(names = "--pathCommercialPopulation", description = "Path to the commercial population file", required = true, defaultValue = "../../tubCloud/rvrWirtschaftsverkehr/ruhr_commercial_0.1pct.plans.xml.gz")
    private Path pathCommercialPopulation;

    @CommandLine.Option(names = "--networkPath", description = "Path to the network file", required = true, defaultValue = "../../tubCloud/rvrWirtschaftsverkehr/metropole-ruhr-v1.x_network.xml.gz")
    private Path networkPath;

    @CommandLine.Option(names = "--output", description = "Path to the output directory", required = true, defaultValue = "output/")
    private Path output;

    @CommandLine.Option(names = "--pathVehicleTypes", description = "Path to the vehicle types file", required = true, defaultValue = "../../tubCloud/rvrWirtschaftsverkehr/metropole-ruhr-v1.x.mode-vehicles.xml")
    private Path pathVehicleTypes;

    @CommandLine.Option(names = "--sample", description = "Sample size", required = true, defaultValue = "0.001")
    private double sample;

    public static void main(String[] args) {
        System.exit(new CommandLine(new RunMetropoleRuhrScenarioCommercial()).execute(args));
    }
    @Override
    public Integer call() throws Exception {
        log.info("Running Metropole Ruhr Scenario Commercial");

        Config config = ConfigUtils.loadConfig(configPath.toString());
        config.plans().setInputFile(configPath.getParent().relativize(pathCommercialPopulation).toString());
        config.plans().setActivityDurationInterpretation(PlansConfigGroup.ActivityDurationInterpretation.tryEndTimeThenDuration);
        config.network().setInputFile(configPath.getParent().relativize(networkPath).toString());
        config.vehicles().setVehiclesFile(configPath.getParent().relativize(pathVehicleTypes).toString());
        config.controler().setOutputDirectory(output.resolve("commercialTraffic_Run" + (int) (sample * 100) + "pct").toString());
        config.controler().setLastIteration(0);
        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.transit().setUseTransit(false);
        config.transit().setTransitScheduleFile(null);
        config.transit().setVehiclesFile(null);
        config.global().setCoordinateSystem("EPSG:25832");
        config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
        config.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);
        config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
        config.qsim().setUsingTravelTimeCheckInTeleportation(true);
        config.qsim().setUsePersonIdForMissingVehicleId(false);
        //to get no traffic jam for the iteration 0
        if (config.controler().getLastIteration() == 0){
            config.qsim().setFlowCapFactor(1.0);
            config.qsim().setStorageCapFactor(1.0);
            log.warn("Setting flowCapFactor and storageCapFactor to 1.0 because we have only the iteration 0 and we dont want to have traffic jams.");
        }
        config.strategy().setFractionOfIterationsToDisableInnovation(0.8);
        config.planCalcScore().setFractionOfIterationsToStartScoreMSA(0.8);
        config.getModules().remove("intermodalTripFareCompensators");
        config.getModules().remove("ptExtensions");
        config.getModules().remove("ptIntermodalRoutingModes");
        config.getModules().remove("swissRailRaptor");

        //prepare the different modes
        Set<String> modes = Set.of("truck8t", "truck18t", "truck26t", "truck40t");

        modes.forEach(mode -> {
            PlanCalcScoreConfigGroup.ModeParams thisModeParams = new PlanCalcScoreConfigGroup.ModeParams(mode);
            config.planCalcScore().addModeParams(thisModeParams);
        });
        Set<String> qsimModes = new HashSet<>(config.qsim().getMainModes());
        config.qsim().setMainModes(Sets.union(qsimModes, modes));

        Set<String> networkModes = new HashSet<>(config.plansCalcRoute().getNetworkModes());
        config.plansCalcRoute().setNetworkModes(Sets.union(networkModes, modes));

        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("commercial_start").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("commercial_end").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("service").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("pickup").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("delivery").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("commercial_return").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("start").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("end").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60));
        config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams("freight_return").setTypicalDuration(30 * 60));

        for (String subpopulation : List.of("LTL_trips", "commercialPersonTraffic", "commercialPersonTraffic_service", "longDistanceFreight",
                "FTL_trip", "FTL_kv_trip", "goodsTraffic")) {
            config.strategy().addStrategySettings(
                    new StrategyConfigGroup.StrategySettings()
                            .setStrategyName(DefaultPlanStrategiesModule.DefaultSelector.ChangeExpBeta)
                            .setWeight(0.85)
                            .setSubpopulation(subpopulation)
            );

            config.strategy().addStrategySettings(
                    new StrategyConfigGroup.StrategySettings()
                            .setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute)
                            .setWeight(0.1)
                            .setSubpopulation(subpopulation)
            );
        }

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controller = new Controler(scenario);

        controller.run();

        return 0;
    }
}
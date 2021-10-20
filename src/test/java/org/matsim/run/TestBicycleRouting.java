package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.Assert.*;

public class TestBicycleRouting {

    private static Id<Person> personId = Id.createPersonId("test-person");

    @Rule
    public MatsimTestUtils testUtils = new MatsimTestUtils();

    @Test
    public void testElevationRouting() {

        var outputDir = testUtils.getOutputDirectory();

        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withElevation", "--useElevation=true", "--download-input", "--1pct");
        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withoutElevation", "--useElevation=false", "--download-input", "--1pct");

        // load output of both runs
        var scenarioWithElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithElevation).readFile(outputDir + "withElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        var scenarioWithoutElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithoutElevation).readFile(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        // somehow compare the two routes
        var personWithElevation = scenarioWithElevation.getPopulation().getPersons().get(personId);
        var personWithoutElevation = scenarioWithoutElevation.getPopulation().getPersons().get(personId);

        assertTrue(personWithElevation.getSelectedPlan().getScore() < personWithoutElevation.getSelectedPlan().getScore());

        var bikeRouteWithElevation = getBikeRoute(personWithElevation);
        var bikeRouteWithoutElevation = getBikeRoute(personWithoutElevation);

        assertNotEquals(bikeRouteWithElevation.toString(), bikeRouteWithoutElevation.toString());
    }

    private static NetworkRoute getBikeRoute(Person person) {
        return person.getSelectedPlan().getPlanElements().stream()
                .filter(element -> element instanceof Leg)
                .map(element -> (Leg)element)
                .filter(leg -> leg.getMode().equals(TransportMode.bike))
                .map(bikeLeg -> (NetworkRoute) bikeLeg.getRoute())
                .findAny()
                .orElseThrow();
    }

    public static class TestApplication extends RunMetropoleRuhrScenario {

        @CommandLine.Option(names = "--useElevation", description = "Overwrite output folder defined by the application")
        protected boolean isUseElevation;

        private static final String RUN_ID = "TestApplication";

        @Override
        public Config prepareConfig(Config config) {
            var preparedConfig = super.prepareConfig(config);

            preparedConfig.global().setNumberOfThreads(1);
            preparedConfig.qsim().setNumberOfThreads(1);
            preparedConfig.plans().setInputFile(null);
            preparedConfig.controler().setLastIteration(0);
            preparedConfig.controler().setRunId(RUN_ID);

            // Disable PT
            preparedConfig.transit().setVehiclesFile(null);
            preparedConfig.transit().setTransitScheduleFile(null);

            var bikeConfig = ((BicycleConfigGroup) config.getModules().get("bicycle"));
            // set an insanely high disutility for gradients
            bikeConfig.setMarginalUtilityOfGradient_m_100m(-1000);
            return preparedConfig;
        }

        @Override
        public void prepareScenario(Scenario scenario) {
            super.prepareScenario(scenario);

            // Other agents are not needed for the test
            scenario.getPopulation().getPersons().clear();

            // add single person with two activities
            var factory = scenario.getPopulation().getFactory();
            var plan = factory.createPlan();
            var homeCoord = scenario.getNetwork().getLinks().get( Id.createLinkId("431735990000f")).getCoord();
            var home = factory.createActivityFromCoord("home_600.0", homeCoord);
            home.setEndTime(0);
            plan.addActivity(home);
            var leg = factory.createLeg(TransportMode.bike);
            leg.setMode(TransportMode.bike);
            plan.addLeg(leg);
            var otherCoord = scenario.getNetwork().getLinks().get( Id.createLinkId("7339595750004f")).getCoord();
            var other = factory.createActivityFromCoord("other_3600.0",otherCoord);
            other.setEndTime(3600);
            plan.addActivity(other);
            var person = factory.createPerson(personId);
            person.addPlan(plan);
            scenario.getPopulation().addPerson(person);

            // remove elevation if necessary
            if (!isUseElevation) {
                scenario.getNetwork().getNodes().values().parallelStream()
                        .forEach(node -> {
                            // copy coord but ignore z-component
                            var coord = new Coord(node.getCoord().getX(), node.getCoord().getY());
                            node.setCoord(coord);
                        });
            }
        }
    }
}

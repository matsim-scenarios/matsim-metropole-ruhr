package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

public class TestBicycleRouting {

    @Rule
    public MatsimTestUtils testUtils = new MatsimTestUtils();

    @Test
    public void testElevationRouting() throws Exception {

        var outputDir = testUtils.getOutputDirectory();

        var appWithElevation = new TestApplication(true, outputDir + "withElevation");
        appWithElevation.call();

        var appWithoutElevation = new TestApplication(false, outputDir + "withoutElevation");
        appWithoutElevation.call();

        // load output of both runs
        var scenarioWithElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithElevation).readFile(outputDir + "withElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        var scenarioWithoutElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithoutElevation).readFile(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");


        // somehow compare the two routes
        System.out.println("bla test");




    }

    private static class TestApplication extends RunMetropoleRuhrScenario {

        private static final String RUN_ID = "TestApplication";
        private final boolean useElevation;
        private final String outputFolder;

        TestApplication(boolean useElevation, String outputFolder) {
            super(ConfigUtils.loadConfig("./scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0-10pct.config.xml"));
            this.useElevation = useElevation;
            this.outputFolder = outputFolder;
        }

        @Override
        public Config prepareConfig(Config config) {
            var preparedConfig = super.prepareConfig(config);
            preparedConfig.plans().setInputFile(null);
            preparedConfig.controler().setOutputDirectory(outputFolder);
            preparedConfig.controler().setLastIteration(1);
            preparedConfig.controler().setRunId(RUN_ID);
            return preparedConfig;
        }

        @Override
        public void prepareScenario(Scenario scenario) {
            super.prepareScenario(scenario);

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
            var person = factory.createPerson(Id.createPersonId("test-person"));
            person.addPlan(plan);
            scenario.getPopulation().addPerson(person);

            // remove elevation if necessary
            if (!useElevation) {
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

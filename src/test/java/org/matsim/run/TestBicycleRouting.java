package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.testcases.MatsimTestUtils;
import picocli.CommandLine;

import java.util.stream.Collectors;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TestBicycleRouting {

    private static final Id<Person> personId = Id.createPersonId("test-person");
    //private static final String inputNetworkFile = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/test-input/metropole-ruhr-v1.0.network_resolutionHigh-with-pt-bicylceTest.xml.gz";
    private static final String inputNetworkFile = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh-with-pt.xml.gz";

    @Rule
    public MatsimTestUtils testUtils = new MatsimTestUtils();

    @Test
    public void testElevationRouting() {

        var outputDir = testUtils.getOutputDirectory();

        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withElevation", "--useElevation=true", "--download-input", "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);
        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withoutElevation", "--useElevation=false", "--download-input", "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);

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
            var otherCoord = scenario.getNetwork().getLinks().get( Id.createLinkId("7339832750094r")).getCoord();
            var other = factory.createActivityFromCoord("other_3600.0",otherCoord);
            other.setEndTime(3600);
            plan.addActivity(other);
            var person = factory.createPerson(personId);
            person.addPlan(plan);
            scenario.getPopulation().addPerson(person);

            // filter the network
            var bbox = createBoundingBox(scenario.getNetwork());
            var nodeIdsToRemove = scenario.getNetwork().getNodes().values().parallelStream()
                    .filter(node -> !bbox.covers(MGC.coord2Point(node.getCoord())))
                    .map(Identifiable::getId)
                    .collect(Collectors.toList());

            for (var id : nodeIdsToRemove) {
                scenario.getNetwork().removeNode(id);
            }

            new NetworkWriter(scenario.getNetwork()).write("C:/Users/Janekdererste/Desktop/reduced-network.xml.gz");

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

    /*
    Create a bounding box around the links. Take the links as corners of the box and then add a 2km padding around it.
     */
    private static PreparedGeometry createBoundingBox(Network network) {

        var homeCoord = network.getLinks().get( Id.createLinkId("431735990000f")).getCoord();
        var otherCoord = network.getLinks().get( Id.createLinkId("7339832750094r")).getCoord();
        var left = Math.min(homeCoord.getX(), otherCoord.getX()) - 2000;
        var right = Math.max(homeCoord.getX(), otherCoord.getX()) + 2000;
        var top = Math.max(homeCoord.getY(), otherCoord.getY()) + 2000;
        var bottom = Math.min(homeCoord.getY(), otherCoord.getY()) - 2000;

        var geometry = new GeometryFactory().createPolygon(new Coordinate[]{
                new Coordinate(left, top), new Coordinate(right, top), new Coordinate(right, bottom), new Coordinate(left, bottom), new Coordinate(left, top)
        });
        return new PreparedGeometryFactory().create(geometry);
    }
}

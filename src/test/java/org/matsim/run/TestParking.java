package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.testcases.MatsimTestUtils;
import picocli.CommandLine;
import playground.vsp.simpleParkingCostHandler.ParkingCostModule;

import java.util.stream.Collectors;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.matsim.core.config.groups.PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLinkPlusTimeConstant;

public class TestParking {

    private static final Id<Person> personId = Id.createPersonId("test-person");
    private static final String inputNetworkFile = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh-with-pt.xml.gz";

    @Rule
    public MatsimTestUtils testUtils = new MatsimTestUtils();

    @Test
    public void testParking() {

        var outputDir = testUtils.getOutputDirectory();

        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withParking", "--useParking=true", "--download-input", "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);
        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withoutParking", "--useParking=false", "--download-input", "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);

        // load output of both runs
        var scenarioWithParking = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithParking).readFile(outputDir + "withParking/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        var scenarioWithoutParking = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithoutParking).readFile(outputDir + "withoutParking/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        // somehow compare the two routes
        var personWithParking = scenarioWithParking.getPopulation().getPersons().get(personId);
        var personWithoutParking = scenarioWithoutParking.getPopulation().getPersons().get(personId);
        assertTrue(personWithParking.getSelectedPlan().getScore() < personWithoutParking.getSelectedPlan().getScore());
    }


    public static class TestApplication extends RunMetropoleRuhrScenario {

        @CommandLine.Option(names = "--useParking", description = "Overwrite output folder defined by the application")
        protected boolean useParking;

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

            if (useParking== false) {
                config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);
            }

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
            var home = factory.createActivityFromCoord("work_600.0", homeCoord);
            home.setEndTime(0);
            plan.addActivity(home);
            var leg = factory.createLeg(TransportMode.car);
            leg.setMode(TransportMode.car);
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

            if (useParking == true) {
               for (Link l: scenario.getNetwork().getLinks().values()) {
                   l.getAttributes().putAttribute("accesstime_car", 1234.0);
                   l.getAttributes().putAttribute("egresstime_car", 1234.0);
                   l.getAttributes().putAttribute("oneHourPCost", 10.0);
                   l.getAttributes().putAttribute("extraHourPCost", 10.0);
                   l.getAttributes().putAttribute("maxDailyPCost", 10.0);
                   l.getAttributes().putAttribute("maxPTime", 0.0);
                   l.getAttributes().putAttribute("pFine", 0.0);
                   l.getAttributes().putAttribute("resPCosts", 0.0);
                   l.getAttributes().putAttribute("zoneName", "zoneName");
                   l.getAttributes().putAttribute("zoneGroup", "zoneGroup");
               }
            }
        }

        @Override
        protected void prepareControler(Controler controler) {

            if (useParking==true) {
                controler.addOverridingModule(new AbstractModule() {
                    @Override
                    public void install() {
                        //analyse PersonMoneyEvents
                        install(new PersonMoneyEventsAnalysisModule());
                    }
                });
                controler.addOverridingModule(new ParkingCostModule());
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

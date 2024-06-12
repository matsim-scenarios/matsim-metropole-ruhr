package org.matsim.run;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.PersonScoreEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Set;

@Disabled
public class TestBicycleRouting {

    private static final Id<Person> personId = Id.createPersonId("test-person");
    private static final String inputNetworkFile = "/Users/gregorr/Documents/work/respos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0.network_resolutionHigh-with-pt.xml.gz";

    @RegisterExtension
    public MatsimTestUtils testUtils = new MatsimTestUtils();
    @Test
    public void testElevationRouting() {

        var outputDir = testUtils.getOutputDirectory();

		MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withElevation", "--useElevation=true", "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);
        MATSimApplication.execute(TestApplication.class, "--output=" + outputDir + "withoutElevation", "--useElevation=false",  "--1pct", "--config:network.inputNetworkFile=" + inputNetworkFile);

		// load output of both runs
       var scenarioWithElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
	   new PopulationReader(scenarioWithElevation).readFile(outputDir + "withElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        var scenarioWithoutElevation = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenarioWithoutElevation).readFile(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_plans.xml.gz");

        // somehow compare the two routes
		var personWithElevation = scenarioWithElevation.getPopulation().getPersons().get(personId);
        var personWithoutElevation = scenarioWithoutElevation.getPopulation().getPersons().get(personId);

        Assertions.assertTrue(personWithElevation.getSelectedPlan().getScore() < personWithoutElevation.getSelectedPlan().getScore());

        var bikeRouteWithElevation = getBikeRoute(personWithElevation);
        var bikeRouteWithoutElevation = getBikeRoute(personWithoutElevation);

		Network networkWithoutElevation = NetworkUtils.readNetwork(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_network.xml.gz");
		Network networkWithElevation = NetworkUtils.readNetwork(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_network.xml.gz");

		TravelTime travelTimeCalculatorNoElevation = getBikeTravelTime(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_events.xml.gz", networkWithoutElevation);
		TravelTime travelTimeCalculatorWithElevation = getBikeTravelTime(outputDir + "withElevation/" + TestApplication.RUN_ID + ".output_events.xml.gz", networkWithElevation);

		double bikeScoringEventsNoElevation = getScoreEvents(outputDir + "withoutElevation/" + TestApplication.RUN_ID + ".output_events.xml.gz");
		double bikeScoringEventsWithElevation = getScoreEvents(outputDir + "withElevation/" + TestApplication.RUN_ID + ".output_events.xml.gz");


		double speedOnLinkNoElevation = travelTimeCalculatorNoElevation.getLinkTravelTime(networkWithoutElevation.getLinks().get(Id.createLinkId("2368352800005r")),
			10.0, personWithoutElevation, null);

		double speedOnLinkNoElevation2 = travelTimeCalculatorNoElevation.getLinkTravelTime(networkWithoutElevation.getLinks().get(Id.createLinkId( "9404091330015r")),
			0.0, scenarioWithoutElevation.getPopulation().getPersons().get("test-person"), null);

		double speedOnLinkWithElevation = travelTimeCalculatorWithElevation.getLinkTravelTime(networkWithElevation.getLinks().get(Id.createLinkId( "2368352800005r")),
			10.0, scenarioWithoutElevation.getPopulation().getPersons().get("test-person"), null);

		double speedOnLinkWithElevation2 = travelTimeCalculatorWithElevation.getLinkTravelTime(networkWithElevation.getLinks().get(Id.createLinkId( "9404091330015r")),
			0.0, scenarioWithoutElevation.getPopulation().getPersons().get("test-person"), null);

		Assertions.assertNotEquals(bikeRouteWithElevation.toString(), bikeRouteWithoutElevation.toString());
		Assertions.assertNotEquals(speedOnLinkNoElevation,  speedOnLinkWithElevation);

		// equal as the second nodes z coord is below the first node
		Assertions.assertEquals(speedOnLinkNoElevation2,  speedOnLinkWithElevation2);
		Assertions.assertTrue(speedOnLinkNoElevation > speedOnLinkNoElevation2);
		Assertions.assertTrue(speedOnLinkNoElevation > speedOnLinkWithElevation);


		Assertions.assertTrue(bikeScoringEventsWithElevation<0.0);
		Assertions.assertTrue(bikeScoringEventsNoElevation==0.0);
		Assertions.assertTrue(bikeScoringEventsNoElevation > bikeScoringEventsWithElevation);

    }

	private static TravelTime getBikeTravelTime(String eventsPath, Network network) {


		TravelTimeCalculator.Builder builder = new TravelTimeCalculator.Builder(network);
		builder.setAnalyzedModes(Set.of("bike"));
		TravelTimeCalculator tt = builder.build();
		EventsManager eventsManager = EventsUtils.createEventsManager();
		eventsManager.addHandler(tt);
		EventsUtils.readEvents(eventsManager, eventsPath);
		eventsManager.finishProcessing();
		return tt.getLinkTravelTimes();
	}

	private static double getScoreEvents (String eventsPath) {
		ScoringEventHandler scoringEventHandler= new ScoringEventHandler();
		EventsManager eventsManager = EventsUtils.createEventsManager();
		eventsManager.addHandler(scoringEventHandler);
		EventsUtils.readEvents(eventsManager, eventsPath);
		eventsManager.finishProcessing();

		double sumScoreEvents = 0.0;
		for(PersonScoreEvent scoringEvent: scoringEventHandler.getPersonsScoreEvents()) {
			scoringEvent.getAmount();
			sumScoreEvents = sumScoreEvents + scoringEvent.getAmount();
		}

		return sumScoreEvents;

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

    public static class TestApplication extends MetropoleRuhrScenario {

        @CommandLine.Option(names = "--useElevation", description = "Overwrite output folder defined by the application")
        protected boolean isUseElevation;
        private static final String RUN_ID = "TestApplication";

        @Override
        public Config prepareConfig(Config config) {
            var preparedConfig = super.prepareConfig(config);

            preparedConfig.global().setNumberOfThreads(1);
            preparedConfig.qsim().setNumberOfThreads(1);
            preparedConfig.plans().setInputFile(null);
            preparedConfig.controller().setLastIteration(0);
            preparedConfig.controller().setRunId(RUN_ID);

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
            var home = factory.createActivityFromCoord("home_600", homeCoord);
            home.setEndTime(0);
            plan.addActivity(home);
            var leg = factory.createLeg(TransportMode.bike);
            leg.setMode(TransportMode.bike);
            plan.addLeg(leg);
            var otherCoord = scenario.getNetwork().getLinks().get( Id.createLinkId("7339832750094r")).getCoord();
            var other = factory.createActivityFromCoord("other_3600",otherCoord);
            other.setEndTime(3600);
            plan.addActivity(other);
            var person = factory.createPerson(personId);
            person.addPlan(plan);
            PersonUtils.setIncome(person, 1);
            scenario.getPopulation().addPerson(person);

            // filter the network
            var bbox = createBoundingBox(scenario.getNetwork());
            var nodeIdsToRemove = scenario.getNetwork().getNodes().values().parallelStream()
                    .filter(node -> !bbox.covers(MGC.coord2Point(node.getCoord())))
                    .map(Identifiable::getId)
                    .toList();

            for (var id : nodeIdsToRemove) {
                scenario.getNetwork().removeNode(id);
            }

            // remove elevation if necessary
            if (!isUseElevation) {
                scenario.getNetwork().getNodes().values().parallelStream()
                        .forEach(node -> {
                            // copy coord but ignore z-component
                            var coord = new Coord(node.getCoord().getX(), node.getCoord().getY());
                            node.setCoord(coord);
                        });
            }

			if (isUseElevation) {
				scenario.getNetwork().getNodes().values().parallelStream()
					.forEach(node -> {
						// copy coord and always add a z component
						var coord = new Coord(node.getCoord().getX(), node.getCoord().getY(), node.getCoord().getZ() + MatsimRandom.getRandom().nextDouble() *100);
						node.setCoord(coord);
					});
			}

            // this is necessary to get the test to work, the default networkMode of the bicycle vehicle type was set to "car"
            scenario.getVehicles().getVehicleTypes().get(Id.create("bike", VehicleType.class)).setNetworkMode("bike");
        }

		public void prepareControler(Controler controler) {
			super.prepareControler(controler);
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					addEventHandlerBinding().toInstance(new ScoringEventHandler());
				}
			});
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

	private static class ScoringEventHandler implements PersonScoreEventHandler {


		ArrayList<PersonScoreEvent> personsScoreEvents = new ArrayList<>();

		private ArrayList<PersonScoreEvent> getPersonsScoreEvents() {
			return personsScoreEvents;
		}


		@Override
		public void handleEvent(PersonScoreEvent personScoreEvent) {
			if (personScoreEvent.getKind().equals("bicycleAdditionalLinkScore")) {
				personsScoreEvents.add(personScoreEvent);
			}
		}



		@Override
		public void reset(int iteration) {
			PersonScoreEventHandler.super.reset(iteration);
		}
	}
}

package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.freight.carriers.Tour;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class TestBusPenalty {


	@RegisterExtension
	public MatsimTestUtils testUtils = new MatsimTestUtils();

	@Test
	public void testPenalty() {
		MATSimApplication.execute(testClass.class, "--run --config.global.numberOfThreads=2 config:qsim.numberOfThreads=2");
	}

	public static class testClass extends MetropoleRuhrScenario {
		@Override
		protected Config prepareConfig(Config config) {
			super.prepareConfig(config);
			config.controller().setLastIteration(0);
			config.removeModule(String.valueOf(SimWrapperConfigGroup.class));
			return config;
		}

		@Override
		protected void prepareScenario(Scenario scenario) {
			super.prepareScenario(scenario);
			scenario.getPopulation().getPersons().clear();

			PopulationFactory populationFactory = scenario.getPopulation().getFactory();
			Person person = populationFactory.createPerson(Id.createPersonId("bus_person"));
			PersonUtils.setIncome(person, 1);

			Plan plan = populationFactory.createPlan();
			Activity homeActivity = populationFactory.createActivityFromCoord("home", scenario.getNetwork().getLinks().get(Id.createLinkId("pt_30026")).getCoord());
			homeActivity.setEndTime(8 * 3600); // 8 AM
			plan.addActivity(homeActivity);
			plan.addLeg(populationFactory.createLeg(TransportMode.pt));
			Activity workActivity = populationFactory.createActivityFromCoord("work", scenario.getNetwork().getLinks().get(Id.createLinkId("pt_30043")).getCoord());
			workActivity.setEndTime(17 * 3600);
			plan.addActivity(workActivity);
			plan.addLeg(populationFactory.createLeg(TransportMode.pt));
			Activity homeActivity2 = populationFactory.createActivityFromCoord("home", scenario.getNetwork().getLinks().get(Id.createLinkId("pt_30026")).getCoord());
			homeActivity2.setEndTime(30 * 3600); // 7 PM
			plan.addActivity(homeActivity2);
			person.addPlan(plan);

			//create a person with a plan that has a bus leg with a penalty
			scenario.getPopulation().addPerson(person);
			System.out.println(scenario.getPopulation().getPersons().size());


			// filter the network
			ArrayList <Id<Link>> linksToRemove = new ArrayList<>();
			for (Link link : scenario.getNetwork().getLinks().values()) {
				if (!link.getAllowedModes().contains(TransportMode.pt)) {
					linksToRemove.add(link.getId());
				}
			}

			for (Id<Link> linkId : linksToRemove) {
				scenario.getNetwork().removeLink(linkId);
			}


			var bbox = createBoundingBox(scenario.getNetwork());
			var nodeIdsToRemove = scenario.getNetwork().getNodes().values().parallelStream()
				.filter(node -> !bbox.covers(MGC.coord2Point(node.getCoord())))
				.map(Identifiable::getId)
				.toList();

			for (var id : nodeIdsToRemove) {
				scenario.getNetwork().removeNode(id);
			}



			Function<Id<Link>, Set<String>> modesToRemoveByLinkId = linkId -> Set.of("freight", "truck8t", "truck18t", "truck26t", "truck40t", TransportMode.bike, TransportMode.ride, TransportMode.car);
			MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(scenario.getNetwork());
			cleaner.run(Set.of("freight", "truck8t", "truck18t", "truck26t", "truck40t", TransportMode.bike, TransportMode.ride, TransportMode.car));

			List<String> keepLines = List.of(
				"nrw462---vgm-42-462-1_6",
				"nrw462---vgm-42-462-1\n"
			);

			//removeRoutesWithInvalidLinks(scenario.getTransitSchedule(), scenario.getNetwork(), keepLines);
			keepOnlyLines(scenario.getTransitSchedule(), keepLines);
			NetworkUtils.writeNetwork(scenario.getNetwork(), "cleaned-network.xml.gz");
		}

		@Override
		protected void prepareControler(Controler controler) {
			super.prepareControler(controler);
		}


		/*
   		Create a bounding box around the links. Take the links as corners of the box and then add a 2km padding around it.
		*/
		private static PreparedGeometry createBoundingBox(Network network) {

			var homeCoord = network.getLinks().get( Id.createLinkId("pt_30026")).getCoord();
			var otherCoord = network.getLinks().get( Id.createLinkId("pt_30043")).getCoord();
			var left = Math.min(homeCoord.getX(), otherCoord.getX()) - 200;
			var right = Math.max(homeCoord.getX(), otherCoord.getX()) + 200;
			var top = Math.max(homeCoord.getY(), otherCoord.getY()) + 200;
			var bottom = Math.min(homeCoord.getY(), otherCoord.getY()) - 200;

			var geometry = new GeometryFactory().createPolygon(new Coordinate[]{
				new Coordinate(left, top), new Coordinate(right, top), new Coordinate(right, bottom), new Coordinate(left, bottom), new Coordinate(left, top)
			});
			return new PreparedGeometryFactory().create(geometry);
		}

		private static void removeRoutesWithInvalidLinks(TransitSchedule schedule, Network network, List<String> keepLineIdStrings) {

			Set<Id<TransitLine>> keepLineIds = new HashSet<>();
			for (String lineIdStr : keepLineIdStrings) {
				keepLineIds.add(Id.create(lineIdStr, TransitLine.class));
			}


			Set<TransitRoute> routesToRemove = new HashSet<>();

			for (TransitLine line : schedule.getTransitLines().values()) {
				for (TransitRoute route : line.getRoutes().values()) {
					boolean valid = true;

					// Check if all stop facilities are on existing links
					for (TransitRouteStop stop : route.getStops()) {
						Id<Link> stopLinkId = stop.getStopFacility().getLinkId();
						if (!network.getLinks().containsKey(stopLinkId)) {
							valid = false;
							break;
						}
					}

					// Optional: also check network route link IDs (if exists)
					if (valid && route.getRoute() != null) {
						for (Id<Link> linkId : route.getRoute().getLinkIds()) {
							if (!network.getLinks().containsKey(linkId)) {
								valid = false;
								break;
							}
						}
					}

					if (!valid) {
						routesToRemove.add(route);
					}
				}

				// Remove the invalid routes from the line
				for (TransitRoute route : routesToRemove) {
					line.removeRoute(route);
				}
				routesToRemove.clear(); // Clear for next line
			}
		}


		private static void keepOnlyLines(TransitSchedule schedule, List<String> keepLineIdStrings) {
			Set<Id<TransitLine>> keepLineIds = new HashSet<>();
			for (String lineIdStr : keepLineIdStrings) {
				keepLineIds.add(Id.create(lineIdStr, TransitLine.class));
			}

			Set<TransitLine> linesToRemove = new HashSet<>();
			for (TransitLine line : schedule.getTransitLines().values()) {
				if (!keepLineIds.contains(line.getId())) {
					linesToRemove.add(line);
				}
			}

			for (TransitLine line : linesToRemove) {
				schedule.removeTransitLine(line);
			}
		}
	}
}






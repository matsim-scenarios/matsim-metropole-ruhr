package org.matsim.prepare;

/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
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
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.run.RunMetropoleRuhrScenario;
import java.util.HashSet;
import java.util.Set;

public class ScenarioCutOut {

    private static final Logger log = Logger.getLogger(ScenarioCutOut.class);
    private static String planningAreaShpFile = "../../shared-svn/projects/GlaMoBi/data/shp-files/Gladbeck.shp";
    private static final double linkBuffer = 1000.;
    private static final double personBuffer = 1000.;

    public static void main(String[] args) {
        log.info(planningAreaShpFile);
        MATSimApplication.run(ScenarioCutOutApplication.class, args);
    }

    public static class ScenarioCutOutApplication extends RunMetropoleRuhrScenario {
        @Override
        public Config prepareConfig(Config config) {
            var preparedConfig = super.prepareConfig(config);
            log.info("changing config");
            preparedConfig.controler().setLastIteration(0);
            return preparedConfig;
        }

        @Override
        protected void prepareScenario(Scenario scenario) {
            super.prepareScenario(scenario);
            ShapeFileUtils shpUtils = new ShapeFileUtils(planningAreaShpFile);
            Set<Id<Link>> linksToDelete = new HashSet<>();
            for (Link link : scenario.getNetwork().getLinks().values()) {
                if (shpUtils.isCoordInArea(link.getCoord(), linkBuffer)
                        || shpUtils.isCoordInArea(link.getFromNode().getCoord(), linkBuffer)
                        || shpUtils.isCoordInArea(link.getToNode().getCoord(), linkBuffer)
                        || link.getAllowedModes().contains(TransportMode.bike)
                        || link.getAllowedModes().contains(TransportMode.pt)
                        || link.getFreespeed() >= 5.) {
                    // keep the link
                } else {
                    linksToDelete.add(link.getId());
                }
            }

            log.info("Links to delete: " + linksToDelete.size());
            for (Id<Link> linkId : linksToDelete) {
                scenario.getNetwork().removeLink(linkId);
            }

            // clean the network
            log.info("number of nodes before cleaning:" + scenario.getNetwork().getNodes().size());
            log.info("number of links before cleaning:" + scenario.getNetwork().getLinks().size());
            log.info("attempt to clean the network");
            new MultimodalNetworkCleaner(scenario.getNetwork()).removeNodesWithoutLinks();
            Set<String> modes = new HashSet<>();
            modes.add(TransportMode.car);
            new MultimodalNetworkCleaner(scenario.getNetwork()).run(modes);
            log.info("number of nodes after cleaning:" + scenario.getNetwork().getNodes().size());
            log.info("number of links after cleaning:" + scenario.getNetwork().getLinks().size());
            NetworkUtils.writeNetwork(scenario.getNetwork(),"network_reduced.xml");
            // now delete irrelevant persons
            Set<Id<Person>> personsToDelete = new HashSet<>();
            for (Person person : scenario.getPopulation().getPersons().values()) {
                boolean keepPerson = false;
                for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                    PopulationUtils.resetRoutes(person.getSelectedPlan());
                    // keep all agents starting or ending in area
                    if (shpUtils.isCoordInArea(trip.getOriginActivity().getCoord(), personBuffer)
                            || shpUtils.isCoordInArea(trip.getDestinationActivity().getCoord(), personBuffer)) {
                        keepPerson = true;
                        break;
                    }
                    // also keep persons traveling through or close to area (beeline)
                    if (shpUtils.isLineInArea(trip.getOriginActivity().getCoord(), trip.getDestinationActivity().getCoord(), personBuffer)) {
                        keepPerson = true;
                        break;
                    }

                }
                if (!keepPerson) {
                    personsToDelete.add(person.getId());
                }
            }
            log.info("Persons to delete: " + personsToDelete.size());
            for (Id<Person> personId : personsToDelete) {
                scenario.getPopulation().removePerson(personId);
            }
            PopulationUtils.writePopulation(scenario.getPopulation(), "pop_reduced.xml");
        }

        @Override
        protected void prepareControler(Controler controler) {
            super.prepareControler(controler);
        }
    }
}





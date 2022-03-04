package org.matsim.policy;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.events.EventsUtils;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CountAgentsOnLink {

    public static void main(String[] args) {

        var manager = EventsUtils.createEventsManager();
        var handler = new Handler();
        manager.addHandler(handler);
        EventsUtils.readEvents(manager, "file");

        for (var personId : handler.personsOnLink) {
            System.out.println(personId);
        }
    }

    private static class Handler implements TransitDriverStartsEventHandler, PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler, LinkLeaveEventHandler {

        private Set<Id<Link>> linkOfInterest = Set.of(
                Id.createLinkId("4944613320001f"),
                Id.createLinkId("4944613320001r")
        );

        private Set<Id<Person>> transitDrivers = new HashSet<>();
        private Map<Id<Vehicle>, Id<Person>> personsInVehicles = new HashMap<>();
        private Set<Id<Person>> personsOnLink = new HashSet<>();

        @Override
        public void handleEvent(LinkLeaveEvent event) {

            if (linkOfInterest.contains(event.getLinkId())) {
                var personId = personsInVehicles.get(event.getVehicleId());
                personsOnLink.add(personId);
            }
        }

        @Override
        public void handleEvent(PersonEntersVehicleEvent event) {

            if (transitDrivers.contains(event.getPersonId())) return;

            personsInVehicles.put(event.getVehicleId(), event.getPersonId());
        }

        @Override
        public void handleEvent(PersonLeavesVehicleEvent event) {

            personsInVehicles.remove(event.getVehicleId());
        }

        @Override
        public void handleEvent(TransitDriverStartsEvent event) {

            transitDrivers.add(event.getDriverId());
        }
    }
}



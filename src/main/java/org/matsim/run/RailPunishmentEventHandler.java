package org.matsim.run;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.vehicles.Vehicle;

import java.util.List;

public class RailPunishmentEventHandler implements PersonEntersVehicleEventHandler {

	private final List<Id<Vehicle>> railVehicles;
	private static final Logger log = LogManager.getLogger(RailPunishmentEventHandler.class);

	@Inject
	EventsManager eventsManager;

	public RailPunishmentEventHandler(List<Id<Vehicle>> railVehicles) {
		this.railVehicles = railVehicles;
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {

		// Only punish persons that are not public transport operators
		if (!event.getPersonId().toString().contains("pt")) {

			if (railVehicles.contains(event.getVehicleId())) {
				//System.out.println("BusPunishmentEventHandler: " + event.getPersonId() + " enters bus " + event.getVehicleId());
				//TODO -100 is a placeholder, adjust the amount as needed, need to find a better value
				//might be worth considering to make this somehow distance based or time dependent as for short trips the penalty might be too high and there are
				//also some people that can only the pt when it is a bus?
				PersonMoneyEvent personMoneyEvent= new PersonMoneyEvent(event.getTime(), event.getPersonId(), -100.0, "punishment_for_using_bus", null, null);
				//person score event
				PersonScoreEvent personScoreEvent =  new PersonScoreEvent(event.getTime(), event.getPersonId(), -100.0, "punishment_for_using_bus");
				//fire the events
				eventsManager.processEvent(personScoreEvent);
				eventsManager.processEvent(personMoneyEvent);
			}
		}

	}

	@Override
	public void reset(int iteration) {
		PersonEntersVehicleEventHandler.super.reset(iteration);
	}
}

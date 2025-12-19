package org.matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.List;

/*
 * This handler will give a penalty to all persons entering a bus that are not public transport operators.
 */

public class BusPunishmentEventHandler implements PersonEntersVehicleEventHandler {
	private final List<Id<Vehicle>> buses;

	BusPunishmentEventHandler(List<Id<Vehicle>> buses) {
		this.buses = buses;
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		// Only punish persons that are not public transport operators
		if (!event.getPersonId().toString().contains("pt")) {
			if (buses.contains(event.getVehicleId())) {
				//System.out.println("BusPunishmentEventHandler: " + event.getPersonId() + " enters bus " + event.getVehicleId());
				//TODO -100 is a placeholder, adjust the amount as needed, need to find a better value
				//might be worth considering to make this somehow distance based or time dependent as for short trips the penalty might be too high and there are
				//also some people that can only the pt when it is a bus?
				new PersonMoneyEvent(event.getTime(), event.getPersonId(), -100, "bus", null, null);
			}
		}
	}

}

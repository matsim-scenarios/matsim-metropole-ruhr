package org.matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.List;

public class BusPunishmentEventHandler implements PersonEntersVehicleEventHandler {
	private final List<Id<Vehicle>> buses;

	BusPunishmentEventHandler(List<Id<Vehicle>> buses) {
		this.buses = buses;
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {

		if (!event.getPersonId().toString().contains("pt")) {
			if (buses.contains(event.getVehicleId())) {
				//System.out.println("BusPunishmentEventHandler: " + event.getPersonId() + " enters bus " + event.getVehicleId());
				new PersonMoneyEvent(event.getTime(), event.getPersonId(), -100, "bus", null, null);
			}

		}
	}

}

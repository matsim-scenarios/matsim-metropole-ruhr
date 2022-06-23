package org.matsim.parking;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.PersonScoreEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.Set;

/**
 * @author zmeng, rybczak
 */
public class UtilityBasedParkingPressureEventHandler implements PersonArrivalEventHandler {

    @Inject
    EventsManager eventsManager;
    @Inject
    Scenario scenario;

    private final Set<String> parkingRelevantTransportModes = Set.of(TransportMode.car);
    public static final String PARK_PRESSURE_ATTRIBUTE_TIME = "time";
    public static final String PARK_PRESSURE_ATTRIBUTE_COST = "cost";

    @Override
    public void reset(int iteration) {

    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {

        if (scenario.getPopulation().getPersons().containsKey(event.getPersonId()) && parkingRelevantTransportModes.contains(event.getLegMode())) {


            if( !scenario.getNetwork().getLinks().get(event.getLinkId()).getAttributes().getAsMap().containsKey(PARK_PRESSURE_ATTRIBUTE_COST)){
                throw new RuntimeException(PARK_PRESSURE_ATTRIBUTE_TIME + " is not found as an attribute in link: " + event.getLinkId());
            }

            double time = (double) scenario.getNetwork().getLinks().get(event.getLinkId()).getAttributes().getAttribute(PARK_PRESSURE_ATTRIBUTE_TIME);
            double cost = (double) scenario.getNetwork().getLinks().get(event.getLinkId()).getAttributes().getAttribute(PARK_PRESSURE_ATTRIBUTE_COST);

            if (time != 0 && cost !=0) {
                // how to calculate time into score/utils
                PersonScoreEvent personScoreEvent = new PersonScoreEvent(event.getTime(), event.getPersonId(), time, "parkPressure");
                PersonMoneyEvent personMoneyEvent = new PersonMoneyEvent(event.getTime(), event.getPersonId(),cost,"parkingCost",null, null);
                eventsManager.processEvent(personScoreEvent);
                eventsManager.processEvent(personMoneyEvent);
            }
        }
    }
}
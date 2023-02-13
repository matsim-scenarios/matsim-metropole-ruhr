package org.matsim.run;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import playground.vsp.pt.fare.DistanceBasedPtFareHandler;
import playground.vsp.pt.fare.DistanceBasedPtFareParams;
import playground.vsp.pt.fare.PtFareConfigGroup;
import playground.vsp.pt.fare.PtFareUpperBoundHandler;

public class RuhrPtFareModule extends AbstractModule {

    @Override
    public void install() {
        // Set the money related thing in the config (planCalcScore) file to 0.
        getConfig().planCalcScore().getModes().get(TransportMode.pt).setDailyMonetaryConstant(0);
        getConfig().planCalcScore().getModes().get(TransportMode.pt).setMarginalUtilityOfDistance(0);

        // Initialize config group (and also write in the output config)
        PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(this.getConfig(), PtFareConfigGroup.class);
        DistanceBasedPtFareParams distanceBasedPtFareParams = ConfigUtils.addOrGetModule(this.getConfig(), DistanceBasedPtFareParams.class);

        // Set parameters
        ptFareConfigGroup.setApplyUpperBound(true);
        ptFareConfigGroup.setUpperBoundFactor(1.5);

        // https://www.vrr.de/de/tickets-tarife/ticketuebersicht/
        // Tarif eezy VRR (app): Grundpreis 1.5 + 0.25/km, max. 25/24h, Kappung bei normalen Einzeltickettarifen
        //A1: 2.9 to D: 15.7, 10-ride-ticket Kurzstrecke 14.5, A1-A3: 23.40, B: 47, C: 95.2 D: 105.2
        // Abo Ticket 1000 monthly by fare level A1-D / multiplied b 12 months and divided by 250 days:
        //A1 	63,95 /  3.06  (within small cities)
        //A2 	67,91  (within medium-sized cities)
        //A3 	71,13 /  3.41 (within one bigger city: Bochum, Dortmund, Essen, Duesseldorf or Wuppertal)
        //B 	101,99 / 4.89  (1 zone and all adjacent zones next to it, e.g. Essen is split in 2 zones so e.g. all Essen+Muehlheim+Oberhausen+Gelsenkirchen+Bochum+...)
        //C 	137,92 / 6.62 (multiple zones)
        //D 	170,93 / 8.20 (entire VRR area: NL to Unna, Borken to Monheim)

        // K: Essen Hbf - Rathaus 0.65km, Essen Hbf - Berliner Platz 1.1km, Essen Hbf - Essen Universitaet 1.9km
        // A3: e.g. Essen Hbf - Bamlerstr. 2.7km, Essen Hbf - Bredeney 4.9km, Essen Hbf-Essen Dellwig 7.5km
        // B: e.g. Essen Hbf - Gelsenkirchen 8km, Essen Hbf - Bochum Hbf 15km, Essen Hbf - Dortmund Hbf 31.5km
        // C: e.g. Essen Duesseldorf 30.5km, Essen - Neuss 36km, Essen Hbf - Unna 48km
        // D: e.g. Essen Hbf - Rheydt 51km, Essen Hbf - Kaldenkirchen 58.5km, Dortmund Hbf - Kaldenkirchen 90.5km

        distanceBasedPtFareParams.setMinFare(1.45);  // Minimum fare (e.g. short trip or 1 zone ticket)
        distanceBasedPtFareParams.setLongDistanceTripThreshold(50000); // Division between long trip and short trip (unit: m)

        distanceBasedPtFareParams.setNormalTripSlope(0.00017987993018495408); // y = ax + b --> a value, for short trips
        distanceBasedPtFareParams.setNormalTripIntercept(2.4710702921120262); // y = ax + b --> b value, for short trips

        // Base price is the daily ticket for long trips
        distanceBasedPtFareParams.setLongDistanceTripSlope(0.000); // y = ax + b --> a value, for long trips
        distanceBasedPtFareParams.setLongDistanceTripIntercept(18.90); // y = ax + b --> b value, for long trips


        // Add bindings
        addEventHandlerBinding().toInstance(new DistanceBasedPtFareHandler(distanceBasedPtFareParams));
        if (ptFareConfigGroup.getApplyUpperBound()) {
            PtFareUpperBoundHandler ptFareUpperBoundHandler = new PtFareUpperBoundHandler(ptFareConfigGroup.getUpperBoundFactor());
            addEventHandlerBinding().toInstance(ptFareUpperBoundHandler);
            addControlerListenerBinding().toInstance(ptFareUpperBoundHandler);
        }
    }
}

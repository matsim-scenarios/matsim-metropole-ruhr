/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2022 by the members listed in the COPYING,        *
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

package org.matsim.prepare;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.extensions.pt.utils.TransitStopTagger;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.net.MalformedURLException;
import java.net.URL;

public class TagTransitSchedule {

    public static void main(String[] args) {
        String scheduleFile = "/home/gregor/git/matsim-metropole-ruhr/scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-transitSchedule.xml.gz";

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(scheduleFile);
        TransitSchedule transitSchedule = scenario.getTransitSchedule();

        String newAttributeName = "car_bike_accessible";
        String newAttributeValue = "true";
        URL ruhrShape;
        try {
            ruhrShape = new URL("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/original-data/shp-files/ruhrgebiet_boundary/ruhrgebiet_boundary.shp");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        String oldFilterAttribute = "stopFilter";
        String oldFilterValue = "station_S/U/RE/RB";
        double bufferAroundServiceArea = 1000;

        TransitStopTagger.tagTransitStopsInShpFile(transitSchedule, newAttributeName, newAttributeValue,
                ruhrShape, oldFilterAttribute, oldFilterValue, bufferAroundServiceArea);

        // manually add bus stops, umlauts and german-s replaced in names

        //1.     Sprockhoevel – Hasslinghausen Busbahnhof
        //Id	vrrde:05954:8038:0:3.1
        //Name	Sprockh. Hasslingh. Busbahnhof
        //X	380397.92152379703
        //Y	5688444.493645721
        tagTransitStop(transitSchedule, Id.create("vrrde:05954:8038:0:3", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //2.     Niedersprockhoevel
        //Id	vrrde:05954:8271:1:3
        //Name	Niedersprockhoevel Kirche
        //X	378129.85169434466
        //Y	5692388.795280713
        tagTransitStop(transitSchedule, Id.create("vrrde:05954:8271:1:3", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //3.     Huenxe Busbahnhof
        //Id	vrrde:05170:40130:0:01
        //Name	Hüuebahnhof
        //X	345665.9639279146
        //Y	5723662.498267628
        tagTransitStop(transitSchedule, Id.create("vrrde:05170:40130:0:01", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //4.     Schermbeck Rathaus
        //Id	vrrde:05170:40162:1:02
        //Name	Schermbeck Rathaus
        //X	352546.90315241134
        //Y	5728813.977912111
        tagTransitStop(transitSchedule, Id.create("vrrde:05170:40162:1:02", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //5.     Neukirchen-Vluyn – Vluyner Suedring
        //Id	vrrde:05170:36264:0:2
        //Name	Neukirchen-Vl. Vluyner Suedring
        //X	328241.9577939104
        //Y	5701311.686783449
        tagTransitStop(transitSchedule, Id.create("vrrde:05170:36264:0:2", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //6.     Kamp-Lintfort – Neues Rathaus
        //Id	vrrde:05170:36938:2:3
        //Name	K.-L. Neues Rathaus
        //X	329791.54002677416
        //Y	5708538.251832792
        tagTransitStop(transitSchedule, Id.create("vrrde:05170:36938:2:3", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //7.     Sonsbeck Post
        //Id	vrrde:05170:36934:0:2
        //Name	Sonsbeck Post
        //X	318347.26103016397
        //Y	5720613.972940993
        tagTransitStop(transitSchedule, Id.create("vrrde:05170:36934:0:2", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //8.     Datteln Busbahnhof
        //Id	vrrde:05562:3886:4:04
        //Name	Datteln Bus Bf
        //X	385068.1840692083
        //Y	5723687.578937067
        tagTransitStop(transitSchedule, Id.create("vrrde:05562:3886:4:04", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //9.     Waltrop – Am Moselbach
        //Id	vrrde:05562:4163:2:02
        //Name	Waltrop Am Moselbach
        //X	389165.1425044374
        //Y	5720505.781132693
        tagTransitStop(transitSchedule, Id.create("vrrde:05562:4163:2:02", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //10.  Oer-Erkenschwick – Berliner Platz
        //Id	vrrde:05562:3878:4:04
        //Name	OER-E. Berliner Platz
        //X	379619.956697611
        //Y	5722492.1364607625
        tagTransitStop(transitSchedule, Id.create("vrrde:05562:3878:4:04", TransitStopFacility.class), newAttributeName, newAttributeValue);

        //11.  Bergkamen Busbahnhof
        //Id	nwlde:05978:60867_Parent
        //Name	Bergkamen, Busbahnhof
        //X	405224.4362690924
        //Y	5719160.550863991
        tagTransitStop(transitSchedule, Id.create("nwlde:05978:60867_Parent", TransitStopFacility.class), newAttributeName, newAttributeValue);

        new TransitScheduleWriter(transitSchedule).writeFile("/home/gregor/git/matsim-metropole-ruhr/scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-transitSchedule-attributed.xml.gz");
    }

    private static void tagTransitStop(TransitSchedule transitSchedule, Id<TransitStopFacility> stopFacilityId,
                                String newAttributeName, String newAttributeValue) {
        transitSchedule.getFacilities().get(stopFacilityId).getAttributes().putAttribute(newAttributeName, newAttributeValue);
    }
}

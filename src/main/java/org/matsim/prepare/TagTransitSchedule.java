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

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.gtfs.RouteType;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.extensions.pt.utils.TransitStopTagger;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.api.*;
import picocli.CommandLine;

import java.net.URL;
import java.nio.file.Path;
import java.util.Set;

@CommandLine.Command(name = "tag-transit-schedule")
public class TagTransitSchedule implements MATSimAppCommand {

	@CommandLine.Option(names = "--input")
	private String input;

	@CommandLine.Option(names = "--output")
	private String output;

	@CommandLine.Mixin
	private ShpOptions shp;

	public static void main(String[] args) {
		new TagTransitSchedule().execute(args);
    }

    private void tagIntermodalStops (TransitSchedule transitSchedule, Set<String> filterModes, URL ruhrShapeUrl) {
        String modeFilterAttribute = "selected_modes_stop";
        String modeFilterValue = "true";

        for (TransitLine line: transitSchedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                String gtfsRouteType = (String) route.getAttributes().getAttribute("simple_route_type");
                if (filterModes.contains(gtfsRouteType)) {
                    for (TransitRouteStop routeStop : route.getStops()) {
                        routeStop.getStopFacility().getAttributes().putAttribute(modeFilterAttribute, modeFilterValue);
                    }
                }
            }
        }

        String newAttributeName = "car_bike_accessible";
        String newAttributeValue = "true";

        double bufferAroundServiceArea = 1000;

        TransitStopTagger.tagTransitStopsInShpFile(transitSchedule, newAttributeName, newAttributeValue,
                ruhrShapeUrl, modeFilterAttribute, modeFilterValue, bufferAroundServiceArea);

		QuadTree<TransitStopFacility> stopsQT = TransitScheduleUtils.createQuadTreeOfTransitStopFacilities(transitSchedule.getFacilities().values());

		// manually add bus stops, umlauts and german-s replaced in names
		// apparently some Ids have changed

        //1.     Sprockhoevel – Hasslinghausen Busbahnhof
        //Id	vrrde:05954:8038:0:3.1
        //Name	Sprockh. Hasslingh. Busbahnhof
        //X	380397.92152379703
        //Y	5688444.493645721
        findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(380397.92152379703, 5688444.493645721),
			newAttributeName, newAttributeValue);

        //2.     Niedersprockhoevel
        //Id	vrrde:05954:8271:1:3
        //Name	Niedersprockhoevel Kirche
        //X	378129.85169434466
        //Y	5692388.795280713
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(378129.85169434466, 5692388.795280713),
			newAttributeName, newAttributeValue);

        //3.     Huenxe Busbahnhof
        //Id	vrrde:05170:40130:0:01
        //Name	Hüuebahnhof
        //X	345665.9639279146
        //Y	5723662.498267628
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(345665.9639279146, 5723662.498267628),
			newAttributeName, newAttributeValue);

        //4.     Schermbeck Rathaus
        //Id	vrrde:05170:40162:1:02
        //Name	Schermbeck Rathaus
        //X	352546.90315241134
        //Y	5728813.977912111
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(352546.90315241134, 5728813.977912111),
			newAttributeName, newAttributeValue);

        //5.     Neukirchen-Vluyn – Vluyner Suedring
        //Id	vrrde:05170:36264:0:2
        //Name	Neukirchen-Vl. Vluyner Suedring
        //X	328241.9577939104
        //Y	5701311.686783449
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(328241.9577939104, 5701311.686783449),
			newAttributeName, newAttributeValue);

        //6.     Kamp-Lintfort – Neues Rathaus
        //Id	vrrde:05170:36938:2:3
        //Name	K.-L. Neues Rathaus
        //X	329791.54002677416
        //Y	5708538.251832792
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(329791.54002677416, 5708538.251832792),
			newAttributeName, newAttributeValue);

        //7.     Sonsbeck Post
        //Id	vrrde:05170:36934:0:2
        //Name	Sonsbeck Post
        //X	318347.26103016397
        //Y	5720613.972940993
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(318347.26103016397, 5720613.972940993),
			newAttributeName, newAttributeValue);

        //8.     Datteln Busbahnhof
        //Id	vrrde:05562:3886:4:04
        //Name	Datteln Bus Bf
        //X	385068.1840692083
        //Y	5723687.578937067
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(385068.1840692083, 5723687.578937067),
			newAttributeName, newAttributeValue);

        //9.     Waltrop – Am Moselbach
        //Id	vrrde:05562:4163:2:02
        //Name	Waltrop Am Moselbach
        //X	389165.1425044374
        //Y	5720505.781132693
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(389165.1425044374, 5720505.781132693),
			newAttributeName, newAttributeValue);

        //10.  Oer-Erkenschwick – Berliner Platz
        //Id	vrrde:05562:3878:4:04
        //Name	OER-E. Berliner Platz
        //X	379619.956697611
        //Y	5722492.1364607625
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(379619.956697611, 5722492.1364607625),
			newAttributeName, newAttributeValue);

        //11.  Bergkamen Busbahnhof
        //Id	nwlde:05978:60867_Parent
        //Name	Bergkamen, Busbahnhof
        //X	405224.4362690924
        //Y	5719160.550863991
		findAndTagNextTransitStop(transitSchedule, stopsQT,
			CoordUtils.createCoord(405224.4362690924, 5719160.550863991),
			newAttributeName, newAttributeValue);
    }

    private static void findAndTagNextTransitStop(TransitSchedule transitSchedule, QuadTree<TransitStopFacility> stopsQT,
												  Coord coord,
												  String newAttributeName, String newAttributeValue) {
		TransitStopFacility stopFacility = stopsQT.getClosest(coord.getX(), coord.getY());
        transitSchedule.getFacilities().get(stopFacility.getId()).getAttributes().putAttribute(newAttributeName, newAttributeValue);
    }

	@Override
	public Integer call() throws Exception {

		if (!shp.isDefined())
			throw new IllegalArgumentException("Shp file must be defined [--shp]");

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(input);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();
		Set<String> filterModes = Set.of(RouteType.RAIL.getSimpleTypeName());

		tagIntermodalStops(transitSchedule, filterModes, IOUtils.resolveFileOrResource(shp.getShapeFile()));

		new TransitScheduleWriter(transitSchedule).writeFile(output.toString());

		return 0;
	}
}

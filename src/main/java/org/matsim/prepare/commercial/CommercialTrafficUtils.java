package org.matsim.prepare.commercial;

import org.matsim.api.core.v01.population.Person;

public class CommercialTrafficUtils {

    public enum TransportType {
        FTL, LTL
    }

    public static void writeCommonAttributes(Person person, RvrTripRelation rvrTripRelation, String tripRelationId) {
        setFreightSubpopulation(person);
        setTripRelationIndex(person, tripRelationId);
        setOriginCell(person, rvrTripRelation);
        setDestinationCell(person, rvrTripRelation);
        setOriginLocationId(person, rvrTripRelation);
        setDestinationLocationId(person, rvrTripRelation);
        setGoodsType(person, rvrTripRelation);
        setTransportType(person, rvrTripRelation);
        setOriginX(person, rvrTripRelation);
        setOriginY(person, rvrTripRelation);
        setDestinationX(person, rvrTripRelation);
        setDestinationY(person, rvrTripRelation);
        setTonesPerYear(person, rvrTripRelation);
    }

    private static void setFreightSubpopulation(Person person) {
        person.getAttributes().putAttribute("subpopulation", "freight");
    }
    private static void setTripRelationIndex(Person person, String tripRelationId) {
        person.getAttributes().putAttribute("trip_relation_index", tripRelationId);
    }
    private static void setOriginCell(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("origin_cell", rvrTripRelation.getOriginCell());
    }
    private static void setDestinationCell(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("destination_cell", rvrTripRelation.getDestinationCell());
    }
    private static void setOriginLocationId(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("origin_locationId", rvrTripRelation.getOriginLocationId());
    }
    private static void setDestinationLocationId(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("destination_locationId", rvrTripRelation.getDestinationLocationId());
    }
    private static void setGoodsType(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("goods_type", rvrTripRelation.getGoodsType());
    }
    private static void setTransportType(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("transportType", rvrTripRelation.getTransportType());
    }
    private static void setOriginX(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("origin_x", rvrTripRelation.getOriginX());
    }
    private static void setOriginY(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("origin_y", rvrTripRelation.getOriginY());
    }
    private static void setDestinationX(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("destination_x", rvrTripRelation.getDestinationX());
    }
    private static void setDestinationY(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("destination_y", rvrTripRelation.getDestinationY());
    }
    private static void setTonesPerYear(Person person, RvrTripRelation rvrTripRelation) {
        person.getAttributes().putAttribute("tons_per_year", rvrTripRelation.getTonsPerYear());
    }

    public static String getTransportType(Person person) {
        return person.getAttributes().getAttribute("transportType").toString();
    }
    public static String getTripRelationIndex(Person person) {
        return person.getAttributes().getAttribute("trip_relation_index").toString();
    }
    public static String getOriginCell(Person person) {
        return person.getAttributes().getAttribute("origin_cell").toString();
    }
    public static String getDestinationCell(Person person) {
        return person.getAttributes().getAttribute("destination_cell").toString();
    }
    public static double getOriginX(Person person) {
        return Double.parseDouble(person.getAttributes().getAttribute("origin_x").toString());
    }
    public static double getOriginY(Person person) {
        return Double.parseDouble(person.getAttributes().getAttribute("origin_y").toString());
    }
    public static double getDestinationX(Person person) {
        return Double.parseDouble(person.getAttributes().getAttribute("destination_x").toString());
    }
    public static double getDestinationY(Person person) {
        return Double.parseDouble(person.getAttributes().getAttribute("destination_y").toString());
    }
    public static int getGoodsType(Person person) {
        return Integer.parseInt(person.getAttributes().getAttribute("goods_type").toString());
    }
    public static double getTonsPerYear(Person person) {
        return Double.parseDouble(person.getAttributes().getAttribute("tons_per_year").toString());
    }
    public static String getOriginLocationId(Person person) {
        return person.getAttributes().getAttribute("origin_locationId").toString();
    }
    public static String getDestinationLocationId(Person person) {
        return person.getAttributes().getAttribute("destination_locationId").toString();
    }
}

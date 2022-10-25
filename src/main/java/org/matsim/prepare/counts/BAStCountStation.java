package org.matsim.prepare.counts;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.HashMap;

class BAStCountStation {

    private final String name;
    private final String id;
    private final String dir1; //from column 'Hi_Ri1'
    private final String dir2; //from column 'Hi_Ri2'

    private Link matchedLink;
    private Id<Link> oppLink;
    private String matchedDir;

    private final Coord coord;

    private final HashMap<String, Double> trafficVolume1 = new HashMap<>();
    private final HashMap<String, Double> trafficVolume2 = new HashMap<>();


    BAStCountStation(String id, String name, String dir1, String dir2, Coord coord) {

        this.coord = coord;
        this.dir1 = dir1;
        this.dir2 = dir2;
        this.id = id;
        this.name = name;

    }

    public String getId() {
        return id;
    }

    public HashMap<String, Double> getTrafficVolume1() {
        return trafficVolume1;
    }

    public HashMap<String, Double> getTrafficVolume2() {
        return trafficVolume2;
    }

    public String getDir1() {
        return dir1;
    }

    public String getDir2() {
        return dir2;
    }

    public Coord getCoord() {
        return coord;
    }

    public String getName() {
        return name;
    }

    public Link getMatchedLink() {
        return matchedLink;
    }

    public String getMatchedDir() {
        return matchedDir;
    }

    public Id<Link> getOppLink() {
        return oppLink;
    }

    public void setMatchedLink(Link matchedLink) {
        this.matchedLink = matchedLink;

        matchDirection();
    }

    public void setOppLink(Id<Link> oppLink) {
        this.oppLink = oppLink;
    }

    private void matchDirection() {
        String direction = getLinkDirection(this.matchedLink);

        matchedDir = this.dir1.contains(direction) ? "Hi_Ri1" : "Hi_Ri2";
    }

    private String getLinkDirection(Link link) {

        Coord fromCoord = link.getFromNode().getCoord();
        Coord toCoord = link.getToNode().getCoord();

        String direction = toCoord.getY() > fromCoord.getY() ? "N" : "S";

        if (toCoord.getX() > fromCoord.getX()) {

            direction += "O";
        } else {
            direction += "W";
        }

        return direction;
    }
}

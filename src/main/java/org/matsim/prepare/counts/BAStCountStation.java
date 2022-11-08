package org.matsim.prepare.counts;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;

import java.util.HashMap;

class BAStCountStation {

    private final String name;
    private final String id;
    private final String dir1; //from column 'Hi_Ri1'
    private final String dir2; //from column 'Hi_Ri2'

    private Link matchedLink;
    private Link oppLink;
    private String matchedDir;
    private String oppDir;

    private boolean hasOppLink = true;

    private final Coord coord;

    private final HashMap<String, Double> trafficVolume1 = new HashMap<>();
    private final HashMap<String, Double> trafficVolume2 = new HashMap<>();


    BAStCountStation(String id, String name, String dir1, String dir2, Coord coord) {

        this.coord = coord;
        this.dir1 = dir1; // is one of: "N", "O", "S", "W"
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

    public Link getOppLink() {
        return oppLink;
    }

    public String getOppDir() {
        return oppDir;
    }

    public boolean hasOppLink() {
        return hasOppLink;
    }

    public void setMatchedLink(Link matchedLink) {
        this.matchedLink = matchedLink;

        matchDirection(matchedLink, this.dir1);
    }

    public void setOppLink(Link oppLink) {
        this.oppLink = oppLink;

        matchDirection(oppLink, this.dir2);
    }

    public void setHasNoOppLink() {
        this.hasOppLink = false;
    }

    private void matchDirection(Link link, String bastDirection) {
        String direction = getLinkDirection(link);

        matchedDir = direction.contains(bastDirection) ? "KFZ_R1" : "KFZ_R2";
        oppDir = matchedDir.equals("KFZ_R1") ? "KFZ_R2": "KFZ_R1";
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

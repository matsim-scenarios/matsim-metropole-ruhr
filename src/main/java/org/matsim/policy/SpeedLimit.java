package org.matsim.policy;

import com.vividsolutions.jts.geom.Geometry;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;

import java.util.stream.Collectors;

public class SpeedLimit {

    public static void main(String[] args) {

        var network = NetworkUtils.readTimeInvariantNetwork("C:\\Users\\janek\\Desktop\\rvr-36-output\\metropole-ruhr-v1.0-10pct.output_network.xml.gz");

        for(var link : network.getLinks().values()) {

            if (link.getCoord().getX() > 30000 && link.getCoord().getX() < 40000 && link.getCoord().getY() > 30000 && link.getCoord().getY() < 40000) {
                link.setFreespeed(20 / 3.6);
            } else if ("primary".equals(link.getAttributes().getAttribute("type"))) {
                link.setFreespeed(30 / 3.6);
            }
        }

        NetworkUtils.writeNetwork(network, "C:\\Users\\janek\\Desktop\\rvr-36-output\\036.network-with-speed-limit.xml.gz");
    }
}

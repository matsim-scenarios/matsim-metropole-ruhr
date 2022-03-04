package org.matsim.policy;

import org.locationtech.jts.geom.Geometry;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;

public class SpeedLimit {

    public static void main(String[] args) {

        var geometry = findGeometry();
        var network = NetworkUtils.readTimeInvariantNetwork("C:\\Users\\Janekdererste\\Desktop\\metropole-ruhr-036\\036.output_network.xml.gz");

        for(var link : network.getLinks().values()) {

            var point = MGC.coord2Point(link.getCoord());

            if (geometry.covers(point)) {
                link.setFreespeed(20 / 3.6);
            }
        }

        NetworkUtils.writeNetwork(network, "C:\\Users\\Janekdererste\\Desktop\\metropole-ruhr-036\\network-with-speed-limit.xml.gz");
    }

    static Geometry findGeometry() {

        var features = ShapeFileReader.getAllFeatures("C:\\Users\\Janekdererste\\Downloads\\vg5000_12-31.utm32s.shape.ebenen\\vg5000_12-31.utm32s.shape.ebenen\\vg5000_ebenen_1231\\VG5000_KRS.shp");

        for (var feature : features) {

            var genValue = (String) feature.getAttribute("GEN");

            if ("Essen".equals(genValue)){
                System.out.println("Found feature Essen.");
                return (Geometry) feature.getDefaultGeometry();
            }
        }

        throw new RuntimeException("This didn't work.");
    }
}

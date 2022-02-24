package org.matsim.policy;


import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

public class AddHighway {

    public static void main(String[] args) {

        var network = NetworkUtils.readTimeInvariantNetwork("C:/Users/Janekdererste/Desktop/metropole-ruhr-036/036.output_network.xml.gz");

        var essenFromNode = network.getNodes().get(Id.createNodeId("344740166"));
        var essenToNode = network.getNodes().get(Id.createNodeId("344741483"));

        var gelsenkirchenFromNode = network.getNodes().get(Id.createNodeId("1597813342"));
        var gelsenkirchenToNode = network.getNodes().get(Id.createNodeId("1597813341"));

        var link1 = network.getFactory().createLink(Id.createLinkId("super-autobahn_1!"), essenFromNode, gelsenkirchenToNode);
        link1.setFreespeed(160/3.6);
        link1.setCapacity(2000);
        link1.setNumberOfLanes(3);
        link1.setAllowedModes(Set.of("car", "ride"));

        var link2 = network.getFactory().createLink(Id.createLinkId("super-autobahn_2!"), gelsenkirchenFromNode, essenToNode);
        link2.setFreespeed(160/3.6);
        link2.setCapacity(2000);
        link2.setNumberOfLanes(3);
        link2.setAllowedModes(Set.of("car", "ride"));

        network.addLink(link1);
        network.addLink(link2);

        NetworkUtils.writeNetwork(network, "C:/Users/Janekdererste/Desktop/metropole-ruhr-036/036.network-with-super-autobahn.xml.gz");
    }
}

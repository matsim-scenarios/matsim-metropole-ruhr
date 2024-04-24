package org.matsim.prepare;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AdjustModesForNetwork {
    public static void main(String[] args) {
//        Config config = ConfigUtils.loadConfig("scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.config.xml");
        Network network = NetworkUtils.readNetwork("scenarios/metropole-ruhr-v1.0/input/ruhr_network.xml.gz");
//        config.network().setInputFile("ruhr_network.xml.gz");
//        config.network().setInputCRS("EPSG:25832");
//
//        Scenario scenario = ScenarioUtils.loadScenario(config);

//        Network network = scenario.getNetwork();
        ArrayList<String> newModes = new ArrayList<>(List.of("freight", "truck8t", "truck18t", "truck26t", "truck40t"));

        network.getLinks().values().forEach(link -> {
            if (link.getAllowedModes().contains("car")) {
                Set<String> allNetworkModes = new HashSet<>(link.getAllowedModes());
                allNetworkModes.addAll(newModes);
                link.setAllowedModes(allNetworkModes);
            }
        });

        NetworkUtils.writeNetwork(network, "scenarios/metropole-ruhr-v1.0/input/ruhr_network_adjustedModes.xml.gz");

    }
}

package org.matsim.policy;

import org.matsim.application.prepare.population.DownSamplePopulation;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PopulationDownsampler {

    private static final Path outputFolder = Paths.get("C:\\Users\\Janekdererste\\Desktop\\metropole-ruhr-036");
    private static final String OUTPUT = outputFolder.resolve("036.output_plans.xml.gz").toString();

    public static void main(String[] args) {

        new DownSamplePopulation().execute(OUTPUT,
                "--sample-size=0.03",
                "--samples", "0.01","0.001"
        );
    }
}

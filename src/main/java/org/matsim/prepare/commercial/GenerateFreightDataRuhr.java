/* *********************************************************************** *
 * project: org.matsim.*
 * Controler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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
package org.matsim.prepare.commercial;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author Ricardo Ewert
 */
@CommandLine.Command(
        name = "generate-freight-data-ruhr",
        description = "Generates data for freight traffic in the Ruhr area.",
        showDefaultValues = true)

public class GenerateFreightDataRuhr implements MATSimAppCommand {

    private static final Logger log = LogManager.getLogger(GenerateFreightDataRuhr.class);

    @CommandLine.Option(names = "--data", description = "Path to buw data",
            defaultValue = "../shared-svn/projects/rvr-metropole-ruhr/data/commercialTraffic/buw/matrix_gesamt.csv")
    private Path dataFolderPath;

//    @CommandLine.Option(names = "--network", description = "Path to desired network file",
//            defaultValue = "../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz")
//    private String networkPath;

    @CommandLine.Option(names = "--pathOutput", description = "Path for the output", required = true, defaultValue = "output/commercial/")
    private Path output;

//    @CommandLine.Option(names = "--truck-load", defaultValue = "13.0", description = "Average load of truck")
//    private double averageTruckLoad;
//
//    @CommandLine.Option(names = "--working-days", defaultValue = "260", description = "Number of working days in a year")
//    private int workingDays;
//
//    @CommandLine.Option(names = "--sample", defaultValue = "1", description = "Scaling factor of the freight traffic (0, 1)")
//    private double sample;
//
    @CommandLine.Option(names = "--nameOutputDataFile", defaultValue = "ruhr_freightData_100pct.xml.gz", description = "Name of the output data file")
    private String nameOutputDataFile;
//
//    private Random rnd;

    public static void main(String[] args) {
        new CommandLine(new GenerateFreightDataRuhr()).execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Configurator.setLevel("org.matsim.core.utils.geometry.geotools.MGC", Level.ERROR);

        log.info("Reading trip relations...");
        List<RvrTripRelation> tripRelations = RvrTripRelation.readTripRelations(dataFolderPath);
        log.info("Trip relations successfully loaded. There are " + tripRelations.size() + " trip relations");

        log.info("Start generating population...");
        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory populationFactory = PopulationUtils.getFactory();
        for (int i = 0; i < tripRelations.size(); i++) {
            Person person = populationFactory.createPerson(Id.createPersonId("freightData_" + i));
            CommercialTrafficUtils.writeCommonAttributes(person, tripRelations.get(i), Integer.toString(i));
            outputPopulation.addPerson(person);

            if (i % 500000 == 0) {
                log.info("Processing: " + i + " out of " + tripRelations.size() + " entries have been processed");
            }
        }

        if (!Files.exists(output)) {
            Files.createDirectory(output);
        }

        String outputPlansPath = output.resolve(nameOutputDataFile).toString();
        PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
        populationWriter.write(outputPlansPath);
        return 0;
    }
}


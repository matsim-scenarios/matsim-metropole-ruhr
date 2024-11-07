package org.matsim.prepare.commercial;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GenerateFTLFreightPlansRuhr implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(GenerateFTLFreightPlansRuhr.class);

    @CommandLine.Option(names = "--data", description = "Path to generated freight data",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/commercialTraffic/ruhr_freightData_100pct.xml.gz")
    private String dataPath;

    @CommandLine.Option(names = "--output", description = "Output folder path", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/output/rvr_freightPlans/")
    private Path output;

    @CommandLine.Option(names = "--nameOutputPopulation", description = "Name of the output population file")
    private String nameOutputPopulation;

    @CommandLine.Option(names = "--truck-load", defaultValue = "13.0", description = "Average load of truck")
    private double averageTruckLoad;

    @CommandLine.Option(names = "--working-days", defaultValue = "260", description = "Number of working days per year")
    private int workingDays;
    //TODO discuss if 260 is a good value

    @CommandLine.Option(names = "--max-kilometer-for-return-journey", defaultValue = "200", description = "[km] Set the maximum euclidean distance to add an empty return journey at the end of FLT trip")
    private int maxKilometerForReturnJourney;
    //TODO discuss if 200 is a good value, the ruhr area has a horizontal length of 100 km

    @CommandLine.Option(names = "--sample", defaultValue = "0.01", description = "Scaling factor of the freight traffic (0, 1)")
    private double sample;

    @Override
    public Integer call() throws Exception {

        log.info("preparing freight agent generator for FTL trips...");
        FTLFreightAgentGeneratorRuhr freightAgentGeneratorFTL = new FTLFreightAgentGeneratorRuhr(averageTruckLoad, workingDays, sample, null, null, null);
        log.info("Freight agent generator for FTL trips successfully created!");

        log.info("Reading freight data...");
        Population inputFreightDemandData = PopulationUtils.readPopulation(dataPath);
        log.info("Freight data successfully loaded. There are {} trip relations", inputFreightDemandData.getPersons().size());

        log.info("Start generating population...");
        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        int i = 0;
        for (Person freightDemandDataRelation : inputFreightDemandData.getPersons().values()) {
            if (i % 500000 == 0) {
                log.info("Processing: {} out of {} entries have been processed", i, inputFreightDemandData.getPersons().size());
            }
            i++;
            if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(
                    CommercialTrafficUtils.TransportType.FTL.toString()) || CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(
                    CommercialTrafficUtils.TransportType.FTL_kv.toString())) {
                createPLansForFTLTrips(freightDemandDataRelation, freightAgentGeneratorFTL, outputPopulation);
            }
        }

        if (!Files.exists(output)) {
            Files.createDirectory(output);
        }

        String sampleName = getSampleNameOfOutputFolder(sample);
        String outputPlansPath;
        if(nameOutputPopulation == null)
            outputPlansPath = output.resolve("freight." + sampleName+ "pct.plansFTL.xml.gz").toString();
        else
            outputPlansPath = output.resolve(nameOutputPopulation).toString();
        PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
        populationWriter.write(outputPlansPath);

        log.info("Freight plans successfully generated!");
        boolean writeTsv = false;
        if (writeTsv) {
            log.info("Writing down tsv file for visualization and analysis...");
            // Write down tsv file for visualization and analysis
            String freightTripTsvPath = output.toString() + "/freight_trips_" + sampleName + "pct_data.tsv";
            CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(freightTripTsvPath), CSVFormat.TDF);
            tsvWriter.printRecord("trip_id", "fromCell", "toCell", "from_x", "from_y", "to_x", "to_y", "goodsType", "tripType");
            for (Person person : outputPopulation.getPersons().values()) {
                List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
                Activity act0 = (Activity) planElements.get(0);
                Activity act1 = (Activity) planElements.get(2);
                Coord fromCoord = act0.getCoord();
                Coord toCoord = act1.getCoord();
                String fromCell = CommercialTrafficUtils.getOriginCell(person);
                String toCell = CommercialTrafficUtils.getDestinationCell(person);
                String tripType = CommercialTrafficUtils.getTransportType(person);

                int goodsType = CommercialTrafficUtils.getGoodsType(person);
                tsvWriter.printRecord(person.getId().toString(), fromCell, toCell, fromCoord.getX(), fromCoord.getY(), toCoord.getX(), toCoord.getY(),
                        goodsType, tripType);
            }
            tsvWriter.close();
            log.info("Tsv file successfully written to " + freightTripTsvPath);
        }
        return 0;
    }

    /**
     * Creates plans for FTL trips.
     */
    private void createPLansForFTLTrips(Person freightDemandDataRelation, FTLFreightAgentGeneratorRuhr freightAgentGeneratorFTL, Population outputPopulation) {
        List<Person> persons = freightAgentGeneratorFTL.generateFreightFTLAgents(freightDemandDataRelation, maxKilometerForReturnJourney);
        for (Person person : persons) {
            outputPopulation.addPerson(person);
        }
    }

    public static void main(String[] args) {
        new GenerateFTLFreightPlansRuhr().execute(args);
    }

    private static String getSampleNameOfOutputFolder(double sample) {
        String sampleName;
        if ((sample * 100) % 1 == 0)
            sampleName = String.valueOf((int) (sample * 100));
        else
            sampleName = String.valueOf((sample * 100));
        return sampleName;
    }

}

package org.matsim.prepare.commercial;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.vehicles.VehicleType;
import picocli.CommandLine;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class GenerateFreightPlansRuhr implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(GenerateFreightPlansRuhr.class);

    @CommandLine.Option(names = "--data", description = "Path to generated freight data",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/commercialTraffic/ruhr_freightData_100pct.xml.gz")
    private String dataPath;

    @CommandLine.Option(names = "--network", description = "Path to desired network file",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/ruhr_network_adjustedModes.xml.gz")
    private String networkPath;

    @CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0.mode-vehicles.xml")
    private String vehicleTypesFilePath;

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

    @CommandLine.Option(names = "--jsprit-iterations-for-LTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
    private int jspritIterationsForLTL;

    @Override
    public Integer call() throws Exception {

        log.info("preparing freight agent generator...");
        FTLFreightAgentGeneratorRuhr freightAgentGeneratorFTL = new FTLFreightAgentGeneratorRuhr(averageTruckLoad, workingDays, sample);
        LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL = new LTLFreightAgentGeneratorRuhr(workingDays, sample);

        log.info("Freight agent generator successfully created!");

        log.info("Reading freight data...");
        Population inputFreightDemandData = PopulationUtils.readPopulation(dataPath);
        log.info("Freight data successfully loaded. There are " + inputFreightDemandData.getPersons().size() + " trip relations");

        log.info("Start generating population...");
        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        // TODO überlegen ob man für die Leerfahrten bei längeren Touren eigenen Agenten erstellt

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

        createPLansForLTLTrips(inputFreightDemandData, freightAgentGeneratorLTL, outputPopulation, jspritIterationsForLTL);

        if (!Files.exists(output)) {
            Files.createDirectory(output);
        }

        String sampleName = getSampleNameOfOutputFolder(sample);
        String outputPlansPath;
        if(nameOutputPopulation == null)
            outputPlansPath = output.resolve("/german_freight." + sampleName+ "pct.plans.xml.gz").toString();
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
     * Creates plans for LTL trips. Therefore, multiple carriers are created to solve the resulted vehicle routing problem.
     */
    private void createPLansForLTLTrips(Population inputFreightDemandData, LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL, Population outputPopulation,
                                        int jspritIterationsForLTL) throws ExecutionException, InterruptedException {

        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkPath);
        config.global().setCoordinateSystem("EPSG:25832");
        FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightCarriersConfigGroup.setCarriersVehicleTypesFile(vehicleTypesFilePath);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        log.info("Read carrier vehicle types");
        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);
        new CarrierVehicleTypeReader( carrierVehicleTypes ).readURL( IOUtils.extendUrl(scenario.getConfig().getContext(), freightCarriersConfigGroup.getCarriersVehicleTypesFile()));

        freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL);

        CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), output.toString() + "/output_FTLcarriersNoSolution.xml.gz");

        filterRelevantVehicleTypesForTourplanning(scenario);

        CarriersUtils.runJsprit(scenario);

        CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), output.toString() + "/output_FTLcarriersWithSolution.xml.gz");

        LTLFreightAgentGeneratorRuhr.createPlansBasedOnCarrierPlans(scenario, outputPopulation);
    }

    /** Remove vehicle types which are not used by the carriers
     * @param scenario the scenario
     */
    private static void filterRelevantVehicleTypesForTourplanning(Scenario scenario) {
        //
        Map<Id<VehicleType>, VehicleType> readVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes();
        List<Id<VehicleType>> usedCarrierVehicleTypes = CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
                .flatMap(carrier -> carrier.getCarrierCapabilities().getCarrierVehicles().values().stream())
                .map(vehicle -> vehicle.getType().getId())
                .distinct()
                .toList();

        readVehicleTypes.keySet().removeIf(vehicleType -> !usedCarrierVehicleTypes.contains(vehicleType));
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
        new GenerateFreightPlansRuhr().execute(args);
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

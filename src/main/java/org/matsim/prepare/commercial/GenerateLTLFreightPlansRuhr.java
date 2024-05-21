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

public class GenerateLTLFreightPlansRuhr implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(GenerateLTLFreightPlansRuhr.class);

    @CommandLine.Option(names = "--data", description = "Path to generated freight data",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/commercialTraffic/ruhr_freightData_100pct.xml.gz")
    private String dataPath;

    @CommandLine.Option(names = "--network", description = "Path to desired network file",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0_network.xml.gz")
    private String networkPath;

    @CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file",
            defaultValue = "scenarios/metropole-ruhr-v2.0/input/metropole-ruhr-v2.0.mode-vehicles.xml")
    private String vehicleTypesFilePath;

    @CommandLine.Option(names = "--output", description = "Output folder path", required = true, defaultValue = "scenarios/metropole-ruhr-v2.0/output/rvr_freightPlans/")
    private Path output;

    @CommandLine.Option(names = "--nameOutputPopulation", description = "Name of the output population file")
    private String nameOutputPopulation;

    @CommandLine.Option(names = "--working-days", defaultValue = "260", description = "Number of working days per year")
    private int workingDays;
    //TODO discuss if 260 is a good value

    @CommandLine.Option(names = "--sample", defaultValue = "0.01", description = "Scaling factor of the freight traffic (0, 1)")
    private double sample;

    @CommandLine.Option(names = "--jsprit-iterations-for-LTL", defaultValue = "100", description = "Number of iterations for jsprit for solving the LTL vehicle routing problems", required = true)
    private int jspritIterationsForLTL;

    @Override
    public Integer call() throws Exception {

        log.info("preparing freight agent generator for FTL trips...");
        LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL = new LTLFreightAgentGeneratorRuhr(workingDays, sample);

        log.info("Freight agent generator for FTL trips successfully created!");

        log.info("Reading freight data...");
        Population inputFreightDemandData = PopulationUtils.readPopulation(dataPath);
        log.info("Freight data successfully loaded. There are {} trip relations", inputFreightDemandData.getPersons().size());

        log.info("Start generating population...");
        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        createPLansForLTLTrips(inputFreightDemandData, freightAgentGeneratorLTL, outputPopulation, jspritIterationsForLTL);

        if (!Files.exists(output)) {
            Files.createDirectory(output);
        }

        String sampleName = getSampleNameOfOutputFolder(sample);
        String outputPlansPath;
        if(nameOutputPopulation == null)
            outputPlansPath = output.resolve("freight." + sampleName+ "pct.plansLTL.xml.gz").toString();
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
                                        int jspritIterationsForLTL) throws ExecutionException, InterruptedException, IOException {

		enum CarrierType {
			REST, WASTE, PARCEL
		}

        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkPath);
        config.global().setCoordinateSystem("EPSG:25832");
        FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightCarriersConfigGroup.setCarriersVehicleTypesFile(vehicleTypesFilePath);
		Path outputFolderCarriers = output.resolve("carriersLTL");
		if (!Files.exists(outputFolderCarriers)) {
			Files.createDirectory(outputFolderCarriers);
		}
		Path carrierVRPFileLTL_Rest = outputFolderCarriers.resolve("output_LTL_Rest_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Rest_Solution = outputFolderCarriers.resolve("output_LTL_Rest_carriersWithSolution.xml.gz");
		Path carrierVRPFileLTL_Waste = outputFolderCarriers.resolve("output_LTL_Waste_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Waste_Solution = outputFolderCarriers.resolve("output_LTL_Waste_carriersWithSolution.xml.gz");
		Path carrierVRPFileLTL_Parcel = outputFolderCarriers.resolve("output_LTL_Parcel_carriersNoSolution.xml.gz");
		Path carrierVRPFile_Rest_Parcel = outputFolderCarriers.resolve("output_LTL_Parcel_carriersWithSolution.xml.gz");

		Path carrierFile_noSolution;
		Path carrierFile_withSolution;

//		Path carrierFile_noSolution = outputFolderCarriers.resolve("output_LTLcarriersNoSolution.xml.gz");
//		Path carrierFile_withSolution = outputFolderCarriers.resolve("output_LTLcarriersWithSolution.xml.gz");

		for(CarrierType carrierType : CarrierType.values()) {
			carrierFile_noSolution = switch (carrierType) {
				case REST -> carrierVRPFileLTL_Rest;
				case WASTE -> carrierVRPFileLTL_Waste;
				case PARCEL -> carrierVRPFileLTL_Parcel;
			};
			carrierFile_withSolution = switch (carrierType) {
				case REST -> carrierVRPFile_Rest_Solution;
				case WASTE -> carrierVRPFile_Waste_Solution;
				case PARCEL -> carrierVRPFile_Rest_Parcel;
			};

		Scenario scenario;
        if (Files.exists(carrierFile_withSolution)) {
            log.warn("Using existing carrier VRP file with solution: {}", carrierFile_withSolution);
            freightCarriersConfigGroup.setCarriersFile(carrierFile_withSolution.toString());
            scenario = ScenarioUtils.loadScenario(config);
            CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);
        } else {
			if (Files.exists(carrierFile_noSolution)) {
                log.warn("Using existing carrier VRP file without solution: {}", carrierFile_noSolution);
                freightCarriersConfigGroup.setCarriersFile(carrierFile_noSolution.toString());

                scenario = ScenarioUtils.loadScenario(config);
                CarriersUtils.loadCarriersAccordingToFreightConfig(scenario);

            } else {
                scenario = ScenarioUtils.loadScenario(config);

                log.info("Read carrier vehicle types");
                CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);
                new CarrierVehicleTypeReader(carrierVehicleTypes).readURL(
                        IOUtils.extendUrl(scenario.getConfig().getContext(), freightCarriersConfigGroup.getCarriersVehicleTypesFile()));
				switch (carrierType) {
					case REST ->
						freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL, Integer.MIN_VALUE);
					case WASTE -> freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL, 140);
					case PARCEL -> freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL, 150);
				};
//                freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario, jspritIterationsForLTL);

                CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), carrierFile_noSolution.toString());
            }
            filterRelevantVehicleTypesForTourPlanning(scenario);

            CarriersUtils.runJsprit(scenario);

            CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), carrierFile_withSolution.toString());
        }

        LTLFreightAgentGeneratorRuhr.createPlansBasedOnCarrierPlans(scenario, outputPopulation);
		}
    }

    /** Remove vehicle types which are not used by the carriers
     * @param scenario the scenario
     */
    private static void filterRelevantVehicleTypesForTourPlanning(Scenario scenario) {
        //
        Map<Id<VehicleType>, VehicleType> readVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario).getVehicleTypes();
        List<Id<VehicleType>> usedCarrierVehicleTypes = CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
                .flatMap(carrier -> carrier.getCarrierCapabilities().getCarrierVehicles().values().stream())
                .map(vehicle -> vehicle.getType().getId())
                .distinct()
                .toList();

        readVehicleTypes.keySet().removeIf(vehicleType -> !usedCarrierVehicleTypes.contains(vehicleType));
    }

    public static void main(String[] args) {
        new GenerateLTLFreightPlansRuhr().execute(args);
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

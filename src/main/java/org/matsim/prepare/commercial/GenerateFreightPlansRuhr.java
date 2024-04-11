package org.matsim.prepare.commercial;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.analysis.RunFreightAnalysisEventBased;
import org.matsim.freight.carriers.controler.CarrierModule;
import org.matsim.freight.carriers.controler.CarrierScoringFunctionFactory;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class GenerateFreightPlansRuhr implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(GenerateFreightPlansRuhr.class);

    @CommandLine.Option(names = "--data", description = "Path to generated freight data",
            defaultValue = "output/commercial/ruhr_freightData_100pct.xml.gz")
    private String dataPath;

    @CommandLine.Option(names = "--network", description = "Path to desired network file",
            defaultValue = "../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.0.network_resolutionHigh.xml.gz")
    private String networkPath;

    @CommandLine.Option(names = "--vehicleTypesFilePath", description = "Path to vehicle types file",
            defaultValue = "scenarios/metropole-ruhr-v1.0/input/commercial_VehicleTypes.xml")
    private String vehicleTypesFilePath;

    @CommandLine.Option(names = "--nuts", description = "Path to desired network file", required = true, defaultValue = "../public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/NUTS3/NUTS3_2010_DE.shp")
    // TODO Change this to URL pointing to SVN--> need to update the Location calculator
    private Path shpPath;

    @CommandLine.Option(names = "--output", description = "Output folder path", required = true, defaultValue = "output/rvr_freightPlans_new/")
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

    @CommandLine.Option(names = "--sample", defaultValue = "0.1", description = "Scaling factor of the freight traffic (0, 1)")
    private double sample;

    @Override
    public Integer call() throws Exception {
//        Network network = NetworkUtils.readNetwork(networkPath);
//        log.info("Network successfully loaded!");

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
                log.info("Processing: " + i + " out of " + inputFreightDemandData.getPersons().size() + " entries have been processed");
            }
            i++;
            if (CommercialTrafficUtils.getTransportType(freightDemandDataRelation).equals(CommercialTrafficUtils.TransportType.FTL.toString())){
                createPLansForFTLTrips(freightDemandDataRelation, freightAgentGeneratorFTL, outputPopulation);
            }
        }

        createPLansForLTLTrips(inputFreightDemandData, freightAgentGeneratorLTL, outputPopulation);


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
            tsvWriter.printRecord(person.getId().toString(), fromCell, toCell, fromCoord.getX(), fromCoord.getY(), toCoord.getX(), toCoord.getY(), goodsType, tripType);
        }
        tsvWriter.close();
        log.info("Tsv file successfully written to " + freightTripTsvPath);

        return 0;
    }

    private void createPLansForLTLTrips(Population inputFreightDemandData, LTLFreightAgentGeneratorRuhr freightAgentGeneratorLTL, Population outputPopulation) throws ExecutionException, InterruptedException, IOException {

        Config config = ConfigUtils.createConfig();
        config.network().setInputFile(networkPath);
        config.global().setCoordinateSystem("EPSG:25832");
        FreightCarriersConfigGroup freightCarriersConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightCarriersConfigGroup.setCarriersVehicleTypesFile(vehicleTypesFilePath);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        log.info("Read carrier vehicle types");
        CarrierVehicleTypes carrierVehicleTypes = CarriersUtils.getCarrierVehicleTypes(scenario);
        new CarrierVehicleTypeReader( carrierVehicleTypes ).readURL( IOUtils.extendUrl(scenario.getConfig().getContext(), freightCarriersConfigGroup.getCarriersVehicleTypesFile()));

        freightAgentGeneratorLTL.createCarriersForLTL(inputFreightDemandData, scenario);

        CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), output.toString() + "/output_carriersNoSolution.xml.gz");

        CarriersUtils.runJsprit(scenario);

        CarriersUtils.writeCarriers(CarriersUtils.addOrGetCarriers(scenario), output.toString() + "/output_carriersWithSolution.xml.gz");

        createPlansBasedOnCarrierPlans(scenario, outputPopulation);

        config.controller().setOutputDirectory(output.toString() + "/runMATSim/");
        config.controller().setLastIteration(0);
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new CarrierModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                bind(CarrierScoringFunctionFactory.class).toInstance(new CarrierScoringFunctionFactory_KeepScore());
            }
        });
        controler.run();

        log.info("Run freight analysis");
        RunFreightAnalysisEventBased freightAnalysis = new RunFreightAnalysisEventBased(output + "/",
                config.controller().getOutputDirectory() + "/Analysis_new/", config.global().getCoordinateSystem());
        freightAnalysis.runAnalysis();
//        List<Person> persons = freightAgentGeneratorLTL.generateFreightLTLAgents(freightDemandDataRelation);
//        for (Person person : persons) {
//            outputPopulation.addPerson(person);
//        }
    }

    /**
     * Creates a population including the plans in preparation for the MATSim run. If a different name of the population is set, different plan variants per person are created
     */
    static void createPlansBasedOnCarrierPlans(Scenario scenario, Population outputPopulation) {

        Population population = scenario.getPopulation();
        PopulationFactory popFactory = population.getFactory();

        Map<String, AtomicLong> idCounter = new HashMap<>();

        Population populationFromCarrier = (Population) scenario.getScenarioElement("allpersons");
        Vehicles allVehicles = VehicleUtils.getOrCreateAllvehicles(scenario);

        for (Person person : populationFromCarrier.getPersons().values()) {

            Plan plan = popFactory.createPlan();
//            String carrierName = person.getId().toString().split("freight_")[1].split("_veh_")[0];
//            Carrier relatedCarrier = CarriersUtils.addOrGetCarriers(scenario).getCarriers()
//                    .get(Id.create(carrierName, Carrier.class));
            String subpopulation = "LTL_trips";
            final String mode = "car";

            List<PlanElement> tourElements = person.getSelectedPlan().getPlanElements();
            double tourStartTime = 0;
            for (PlanElement tourElement : tourElements) {

                if (tourElement instanceof Activity activity) {
                    activity.setCoord(
                            scenario.getNetwork().getLinks().get(activity.getLinkId()).getFromNode().getCoord());
                    if (activity.getType().equals("start")) {
                        tourStartTime = activity.getEndTime().seconds();
                        activity.setType("commercial_start");
                    } else
                        activity.setEndTimeUndefined();
                    if (activity.getType().equals("end")) {
                        activity.setStartTime(tourStartTime + 8 * 3600);
                        activity.setType("commercial_end");
                    }
                    plan.addActivity(activity);
                }
                if (tourElement instanceof Leg) {
                    Leg legActivity = popFactory.createLeg(mode);
                    plan.addLeg(legActivity);
                }
            }

            String key = person.getId().toString();

            long id = idCounter.computeIfAbsent(key, (k) -> new AtomicLong()).getAndIncrement();

            Person newPerson = popFactory.createPerson(Id.createPersonId(key + "_" + id));

            newPerson.addPlan(plan);
            PopulationUtils.putSubpopulation(newPerson, subpopulation);

            Id<Vehicle> vehicleId = Id.createVehicleId(person.getId().toString());

            VehicleUtils.insertVehicleIdsIntoPersonAttributes(newPerson, Map.of(mode, vehicleId));
            VehicleUtils.insertVehicleTypesIntoPersonAttributes(newPerson, Map.of(mode, allVehicles.getVehicles().get(vehicleId).getType().getId()));

            outputPopulation.addPerson(newPerson);
        }

//        String outputPopulationFile;
//        if (numberOfPlanVariantsPerAgent > 1)
////				CreateDifferentPlansForFreightPopulation.createMorePlansWithDifferentStartTimes(population, numberOfPlanVariantsPerAgent, 6*3600, 14*3600, 8*3600);
//            CreateDifferentPlansForFreightPopulation.createMorePlansWithDifferentActivityOrder(population, numberOfPlanVariantsPerAgent);
//        else if (numberOfPlanVariantsPerAgent < 1)
//            log.warn(
//                    "You selected {} of different plan variants per agent. This is invalid. Please check the input parameter. The default is 1 and is now set for the output.",
//                    numberOfPlanVariantsPerAgent);

        scenario.getPopulation().getPersons().clear();
    }

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

    private static class CarrierScoringFunctionFactory_KeepScore implements CarrierScoringFunctionFactory {
        @Override public ScoringFunction createScoringFunction(Carrier carrier ){
            return new ScoringFunction(){
                @Override public void handleActivity( Activity activity ){
                }
                @Override public void handleLeg( Leg leg ){
                }
                @Override public void agentStuck( double time ){
                }
                @Override public void addMoney( double amount ){
                }
                @Override public void addScore( double amount ){
                }
                @Override public void finish(){
                }
                @Override public double getScore(){
                    return CarriersUtils.getJspritScore(carrier.getSelectedPlan()); //2nd Quickfix: Keep the current score -> which ist normally the score from jsprit. -> Better safe JspritScore as own value.
//					return Double.MIN_VALUE; // 1st Quickfix, to have a "double" value for xsd (instead of neg.-Infinity).
//					return Double.NEGATIVE_INFINITY; // Default from KN -> causes errors with reading in carrierFile because Java writes "Infinity", while XSD needs "INF"
                }
                @Override public void handleEvent( Event event ){
                }
            };
        }
    }
}

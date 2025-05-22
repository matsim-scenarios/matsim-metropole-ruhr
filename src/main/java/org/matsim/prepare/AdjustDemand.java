package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.matsim.analysis.PopulationComparison;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@CommandLine.Command(name = "adjust-demand")
public class AdjustDemand implements MATSimAppCommand {

    private static final Logger log = LogManager.getLogger(MATSimAppCommand.class);
    public static final String PERSON_ID_SUFFIX = "_cloned";
    private static final Random rand = new Random();
    private static final Adjustments EMPTY_ADJUSTMENTS = new Adjustments(List.of(), List.of());

    @CommandLine.Option(names = "--plans", required = true)
    private Path inputFile;
    @CommandLine.Option(names = "--output", required = true)
    private Path outputFile;

    @CommandLine.Option(names = "--adjustments", description = "CSV-File with adjustments parameters for the cells within the provided shape file", required = true)
    private String adjustments;

    @CommandLine.Option(names = "--locale", description = "Which number format to expect. For de-DE 100.000,05, for en-EN 100,000.05 is expected ")
    private String localeIdentifier = "de-DE";

    @CommandLine.Option(names = {"--filter", "--f"}, description = "Column Name of property filter. To filter for age and sex provide `--f age --f sex` for example. By default all columns which are not --attr-name or `value' are used.")
    private List<String> filterColumns = List.of();

    @CommandLine.Option(names = {"--ignore-filter", "--if"})
    private boolean ignoreFilter = false;


    @CommandLine.Option(names = "--attr-name", defaultValue = "id")
    private String attrName;

    @SuppressWarnings("FieldMayBeFinal")
    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();

    public static void main(String[] args) {
        System.exit(new CommandLine(new AdjustDemand()).execute(args));
    }

    @Override
    public Integer call() throws Exception {

        // first check if shapefile was provided
        if (!shp.isDefined()) throw new RuntimeException("Shapefile must be defined!");

        // prepare number format
        NumberFormat numberFormat = NumberFormat.getInstance(Locale.forLanguageTag(localeIdentifier));

        // read cells
        var preparedFactory = new PreparedGeometryFactory();
        Map<String, PreparedGeometry> preparedFeatures = shp.readFeatures().stream()
                .collect(Collectors.toMap(f -> f.getAttribute(attrName).toString(), f -> preparedFactory.create((Geometry) f.getDefaultGeometry())));

        // read adjustments
        Map<String, Adjustments> filteredAdjustments = new HashMap<>();
        try (var bufReader = Files.newBufferedReader(Paths.get(adjustments)); var csvParser = CSVParser.parse(bufReader, CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader())) {

            List<String> filterColumns = getFilterColumns(csvParser);

            for (CSVRecord record : csvParser) {
                var value = numberFormat.parse(record.get("value")).doubleValue();
                var key = record.get(attrName).trim();
                var filters = filterColumns.stream()
                        .map(record::get)
                        .map(String::trim)
                        .map(AdjustDemand::parseFilter)
                        .toList();

                var adjustments = filteredAdjustments.computeIfAbsent(key, k -> new Adjustments(filterColumns, new ArrayList<>()));
                adjustments.adjustments.add(new Adjustment(filters, value));
            }
        }

	// read originalPopulation
		Population originalPopulation = PopulationUtils.readPopulation(inputFile.toString());
	// Create a new empty population
		Population personPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		personPopulation.getPersons().clear();

		//only add person agents to the new population
		for (Person person : originalPopulation.getPersons().values()) {
			if (person.getAttributes().getAttribute("subpopulation").equals("person")) {
				personPopulation.addPerson(person);
			}
		}

//only use the person demand from here
		QuadTree<Id<Person>> spatialIndex = createSpatialIndex(personPopulation);
		// now go through all the cells and adjust the originalPopulation according to the values in adjust table
		adjust(personPopulation, preparedFeatures, spatialIndex, filteredAdjustments);

		for (Person person : originalPopulation.getPersons().values()) {
			//add back non-person demand
			if (!personPopulation.getPersons().containsKey(person.getId())) {
				personPopulation.addPerson(person);
			}
		}

		PopulationUtils.writePopulation(personPopulation, outputFile.toString());

        return 0;
    }

    /**
     * This method mutates the population
     * <p>
     * Notes from last meeting: Exception if no/too few persons are found for filter criteria
     */
    static void adjust(Population population, Map<String, PreparedGeometry> preparedFeatures, QuadTree<Id<Person>> spatialIndex, Map<String, Adjustments> adjustmentData) {
        for (var cell : preparedFeatures.entrySet()) {

            var personsAdded = new AtomicInteger();
            var personsRemoved = new AtomicInteger();

            // get all the people inside the cell
            var personsInCell = spatialIndex.coveredBy(cell.getValue());
            // get the adjustment and filter or empty adjustment, to avoid extra condition
            var adjustmentsForCell = adjustmentData.getOrDefault(cell.getKey(), EMPTY_ADJUSTMENTS);

            for (var adjustmentWithFilter : adjustmentsForCell.adjustments) {
                // get the adjustment factor
                var factor = adjustmentWithFilter.value();

                if (factor < 0) {
                    throw new RuntimeException("Adjustment factors are expected to be betweeen [0.0, 2.0]. The value for cell " + cell.getKey() + " was: " + factor);
                }
                if (factor > 2) {
                    log.warn("Growth factor was " + factor + ". We don't know what to do here. Using max growth factor of 2.0");
                    factor = 2;
                }

                var growth = factor - 1;
                // draw that amount of persons
                var drawnPersons = personsInCell.stream()
                        // filter for deleted people. The spatial index is not updated, so if there are a lot of categories
                        // for a single cell a person id, which is still in the spatial index might in fact be removed from the
                        // population. Chose to do it like this because it seems easier than updating the spatial index.
                        .filter(id -> population.getPersons().containsKey(id))
                        .map(id -> population.getPersons().get(id))
                        .filter(person -> applyAdjustmentFilter(adjustmentsForCell, adjustmentWithFilter, person))
                        .filter(id -> rand.nextDouble() <= Math.abs(growth))
                        .limit((long) (Math.abs(growth) * personsInCell.size()))
                        .map(Person::getId)
                        .collect(Collectors.toSet());

                if (growth > 0) {
                    // the cell grows clone the persons
                    for (var id : drawnPersons) {
                        var person = population.getPersons().get(id);
                        var cloned = clonePerson(person, population.getFactory());
                        var clonedPlan = clonePlan(person.getSelectedPlan(), population.getFactory());
                        cloned.addPlan(clonedPlan);
                        population.addPerson(cloned);
                        personsAdded.incrementAndGet();
                    }
                } else {
                    // the cell shrinks delete the persons
                    for (var id : drawnPersons) {
                        population.removePerson(id);
                        personsRemoved.incrementAndGet();
                    }
                }
            }

            log.info("Finished cell: " + cell.getKey() + ". Added: " + personsAdded.get() + ", Removed: " + personsRemoved.get());
        }
    }

    private static boolean applyAdjustmentFilter(Adjustments adjustmentsForCell, Adjustment adjustmentWithFilter, Person person) {

        for (var i = 0; i < adjustmentsForCell.columns.size(); i++) {
            var key = adjustmentsForCell.columns.get(i);
            var criteria = adjustmentWithFilter.filters().get(i);

            if (!criteria.test(person.getAttributes().getAttribute(key)))
                return false;
        }
        return true;
    }

    static Person clonePerson(Person person, PopulationFactory factory) {

        var cloned = factory.createPerson(Id.createPersonId(person.getId().toString() + PERSON_ID_SUFFIX));
        for (var attr : person.getAttributes().getAsMap().entrySet()) {
            cloned.getAttributes().putAttribute(attr.getKey(), attr.getValue());
        }
        return cloned;
    }

    static Plan clonePlan(Plan plan, PopulationFactory factory) {

        var result = factory.createPlan();
        Coord clonedHomeCoord = null;

        var planIterator = plan.getPlanElements().iterator();

        while (planIterator.hasNext()) {
            var element = planIterator.next();

            if (element instanceof Activity act && !TripStructureUtils.isStageActivityType(act.getType())) {
                if (clonedHomeCoord == null && act.getType().startsWith("home")) {
                    clonedHomeCoord = createRandomCoord(act.getCoord());
                }
                var newCoord = act.getType().startsWith("home") ? clonedHomeCoord : createRandomCoord(act.getCoord());
                var clonedAct = cloneActivity(act, newCoord, factory);
                result.addActivity(clonedAct);

                if (planIterator.hasNext()) {
                    var leg = (Leg) planIterator.next();
                    var routingModeAttribute = (String) leg.getAttributes().getAttribute("routingMode");
                    var routingMode = routingModeAttribute == null ? leg.getMode() : routingModeAttribute;
                    var clonedLeg = factory.createLeg(routingMode);
                    result.addLeg(clonedLeg);
                }
            }
        }
        return result;
    }

    static Activity cloneActivity(Activity act, Coord actCoord, PopulationFactory factory) {

        var clonedAct = factory.createActivityFromCoord(act.getType(), actCoord);
        if (act.getEndTime().isDefined()) {
            clonedAct.setEndTime(act.getEndTime().seconds());
        }
        if (act.getStartTime().isDefined()) {
            clonedAct.setStartTime(act.getStartTime().seconds());
        }
        if (act.getMaximumDuration().isDefined()) {
            clonedAct.setMaximumDuration(act.getMaximumDuration().seconds());
        }
        return clonedAct;
    }


    static Coord createRandomCoord(Coord originalCoord) {

        var x = rand.nextGaussian(originalCoord.getX(), 100);
        var y = rand.nextGaussian(originalCoord.getY(), 100);
        return new Coord(x, y);
    }

    static Filter parseFilter(String recordValue) {

        if (recordValue.isBlank()) return new YesFilter();
            // the following is kinda brittle, but will work if the input is absolutely correct 😬
        else if (recordValue.contains(" bis unter ")) {
            var split = recordValue.split("bis unter | Jahre");
            var lowerBound = Double.parseDouble(split[0]);
            var upperBound = Double.parseDouble(split[1]);
            return new Range(lowerBound, upperBound);
        } else if (recordValue.contains(" bis ")) {
            var split = recordValue.split("bis | Jahre");
            var lowerBound = Double.parseDouble(split[0]);
            var upperBound = Double.parseDouble(split[1]);
            return new Range(lowerBound, upperBound);
        } else if (recordValue.contains("unter") && (recordValue.contains("Jahre") || recordValue.contains("Jahr"))) {
            var split = recordValue.split("unter | Jahre | Jahr");
            var upperBound = Double.parseDouble(split[1]);
            var lowerBound = Double.NEGATIVE_INFINITY;
            return new Range(lowerBound, upperBound);
        } else if (recordValue.contains("Jahre und mehr")) {
            var split = recordValue.split("Jahre und mehr");
            var lowerBound = Double.parseDouble(split[0]);
            var upperBound = Double.POSITIVE_INFINITY;
            return new Range(lowerBound, upperBound);
        } else if (recordValue.contains("männlich")) {
            return new Exact("m");
        } else if (recordValue.contains("weiblich")) {
            return new Exact("f");
        } else {
            return new Exact(recordValue);
        }
    }

    private List<String> getFilterColumns(CSVParser csvParser) {

        if (ignoreFilter) return List.of();

        if (this.filterColumns.isEmpty())
            return csvParser.getHeaderMap().keySet().stream()
                    .filter(header -> !header.equals(attrName) && !header.equals("value"))
                    .toList();

        return this.filterColumns;
    }

    static QuadTree<Id<Person>> createSpatialIndex(Population population) {

        QuadTree<Id<Person>> result = new QuadTree<>();
        for (var person : population.getPersons().values()) {
            var activities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
            var homeAct = findHomeAct(activities);
            var homePoint = MGC.coord2Point(homeAct.getCoord());
            result.insert(homePoint, person.getId());
        }
        return result;
    }

    static class QuadTree<T> {

        private final Quadtree index = new Quadtree();

        public void insert(Geometry geometry, T item) {
            index.insert(geometry.getEnvelopeInternal(), new IndexItem<>(geometry, item));
        }

        public Set<T> coveredBy(PreparedGeometry geometry) {
            Set<T> result = new HashSet<>();
            index.query(geometry.getGeometry().getEnvelopeInternal(), entry -> {
                @SuppressWarnings("unchecked") // suppress warning, since we know that entry is an IndexItem<T>
                IndexItem<T> indexItem = (IndexItem<T>) entry;
                if (geometry.covers(indexItem.geom())) {
                    result.add(indexItem.item());
                }
            });
            return result;
        }

        /**
         * '
         * Return all items that are covered by the geometry supplied as argument
         *
         * @param geometry the spatial filter by which items in the index are filtered
         * @return all covered items
         */
        public Set<T> coveredBy(Geometry geometry) {

            Set<T> result = new HashSet<>();
            index.query(geometry.getEnvelopeInternal(), entry -> {
                @SuppressWarnings("unchecked") // suppress warning, since we know that entry is an IndexItem<T>
                IndexItem<T> indexItem = (IndexItem<T>) entry;
                if (geometry.covers(indexItem.geom())) {
                    result.add(indexItem.item());
                }
            });
            return result;
        }

        /**
         * Inverse of {@link #coveredBy(Geometry)}
         * Finds items which cover the supplied geometry.
         */
        public Set<T> allCover(Geometry geometry) {
            Set<T> result = new HashSet<>();
            index.query(geometry.getEnvelopeInternal(), entry -> {
                @SuppressWarnings("unchecked") // suppress warning, since we know that entry is an IndexItem<T>
                IndexItem<T> indexItem = (IndexItem<T>) entry;
                if (indexItem.geom().covers(geometry)) {
                    result.add(indexItem.item());
                }
            });
            return result;
        }

        private record IndexItem<T>(Geometry geom, T item) {
        }
    }

    record Adjustments(List<String> columns, List<Adjustment> adjustments) {
    }

    record Adjustment(List<Filter> filters, double value) {
    }

    /**
     * A range tests whether a number is within its bounds
     *
     * @param lowerBound is included
     * @param upperBound is excluded
     */
    record Range(Number lowerBound, Number upperBound) implements Filter {

        boolean isWithin(Number value) {
            return lowerBound.doubleValue() <= value.doubleValue() && value.doubleValue() < upperBound.doubleValue();
        }

        @Override
        public boolean test(Object value) {
            if (value instanceof Number number) {
                return isWithin(number);
            }
            return false;
        }
    }

    record Exact(String filter) implements Filter {

        @Override
        public boolean test(Object value) {
            return filter.equals(value);
        }
    }

    record YesFilter() implements Filter {

        @Override
        public boolean test(Object value) {
            return true;
        }
    }

    @FunctionalInterface
    interface Filter {
        boolean test(Object value);
    }

    private static Activity findHomeAct(Collection<Activity> activities) {
        return activities.stream().filter(act -> act.getType().startsWith("home")).findAny().orElseThrow();
    }
}


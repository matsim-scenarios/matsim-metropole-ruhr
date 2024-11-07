package org.matsim.prepare;

import org.geotools.api.referencing.FactoryException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.matsim.testcases.MatsimTestUtils;
import org.geotools.api.feature.simple.SimpleFeatureType;

import java.util.*;
import java.util.stream.Collectors;

public class AdjustDemandTest {

    @RegisterExtension
    public MatsimTestUtils testUtils = new MatsimTestUtils();

    @Test
    public void testGrowthRates() {

        var cells = createCells();
        var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        for (var cell : cells.entrySet()) {
            addPersonsForCell(population, cell.getValue().getGeometry(), cell.getValue().getGeometry(), cell.getKey(), 100);
        }
        var index = AdjustDemand.createSpatialIndex(population);
        Map<String, AdjustDemand.Adjustments> adjustments = Map.of(
                "1", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 2.0))),
                "2", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 0.5))),
                "3", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 1.0))),
                "4", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 1.0)))
        );

        for (int i = 0; i < 1000; i++) {
            var cloned = PopulationUtils.createPopulation(ConfigUtils.createConfig());
            for (var person : population.getPersons().values()) {
                cloned.addPerson(person);
            }
            AdjustDemand.adjust(cloned, cells, index, adjustments);

            var resultIndex = AdjustDemand.createSpatialIndex(cloned);

            for (var cell : cells.entrySet()) {

                var numberOfPersonsBefore = index.coveredBy(cell.getValue());
                var numberOfPersonsAfter = resultIndex.coveredBy(cell.getValue());
                var factor = adjustments.get(cell.getKey()).adjustments().get(0).value();

                // give this test a lot of slack, because we draw persons by using a random number generator and this test operates on
                // relatively small numbers the results vary in the range of 10%
                Assertions.assertEquals(numberOfPersonsBefore.size() * factor, numberOfPersonsAfter.size(), numberOfPersonsBefore.size() * factor * 1.1);
            }
        }
    }

    @Test
    public void testClonedActivities() {

        var cells = createCells();
        var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        var rand = new Random();
        for (var cell : cells.entrySet()) {
            var randomNumber = rand.nextInt(1, 5);
            addPersonsForCell(population, cell.getValue().getGeometry(), cells.get(Integer.toString(randomNumber)).getGeometry(), cell.getKey(), 100);
        }
        Map<String, AdjustDemand.Adjustments> adjustments = Map.of(
                "1", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 2.0))),
                "2", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 0.5))),
                "3", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 1.0))),
                "4", new AdjustDemand.Adjustments(List.of(), List.of(new AdjustDemand.Adjustment(List.of(), 1.0)))
        );
        var populationIndex = AdjustDemand.createSpatialIndex(population);

        AdjustDemand.adjust(population, cells, populationIndex, adjustments);

        population.getPersons().values().stream()
                .filter(person -> person.getId().toString().endsWith(AdjustDemand.PERSON_ID_SUFFIX))
                .map(person -> {
                    var originalId = Id.createPersonId(person.getId().toString().split(AdjustDemand.PERSON_ID_SUFFIX)[0]);
                    var originalPerson = population.getPersons().get(originalId);
                    return Tuple.of(originalPerson, person);
                })
                .flatMap(couple -> mergeActivities(couple.getFirst(), couple.getSecond()).stream())
                .forEach(activityTuple -> {
                    // make sure types are equal
                    Assertions.assertEquals(activityTuple.getFirst().getType(), activityTuple.getSecond().getType());

                    // make sure coords are not the same
                    Assertions.assertNotEquals(activityTuple.getFirst().getCoord(), activityTuple.getSecond().getCoord());
                });
    }

    @Test
    public void testExactAttributeFilter() {
        var cells = createCells();
        var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        var rand = new Random();
        for (var cell : cells.entrySet()) {
            var randomNumber = rand.nextInt(1, 5);
            addPersonsForCell(population, cell.getValue().getGeometry(), cells.get(Integer.toString(randomNumber)).getGeometry(), cell.getKey(), 100);

            // add a special person with filterable attribute
            if (cell.getKey().equals("1")) {
                addPersonsForCell(population, cell.getValue().getGeometry(), cells.get(Integer.toString(randomNumber)).getGeometry(), "special", 1);
                var specialPerson = population.getPersons().get(Id.createPersonId("special_0"));
                specialPerson.getAttributes().putAttribute("exact-filter", "yes!");
            }
        }

        var filterNames = List.of("exact-filter");
        Map<String, AdjustDemand.Adjustments> adjustments = Map.of(
                "1", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Exact("yes!")), 2.0))),
                "2", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Exact("yes!")), 0.5))),
                "3", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Exact("yes!")), 1.0))),
                "4", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Exact("yes!")), 1.0)))
        );
        var populationIndex = AdjustDemand.createSpatialIndex(population);

        var numberOfPersonsBefore = population.getPersons().size();
        AdjustDemand.adjust(population, cells, populationIndex, adjustments);

        // we expect only the special person to be cloned.
        Assertions.assertEquals(numberOfPersonsBefore + 1, population.getPersons().size());
    }

    @Test
    public void testRangeAttributeFilter() {
        var cells = createCells();
        var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        var rand = new Random();
        for (var cell : cells.entrySet()) {
            var randomNumber = rand.nextInt(1, 5);
            addPersonsForCell(population, cell.getValue().getGeometry(), cells.get(Integer.toString(randomNumber)).getGeometry(), cell.getKey(), 100);

            // add a special person with filterable attribute
            if (cell.getKey().equals("1")) {
                addPersonsForCell(population, cell.getValue().getGeometry(), cells.get(Integer.toString(randomNumber)).getGeometry(), "special", 1);
                var specialPerson = population.getPersons().get(Id.createPersonId("special_0"));
                specialPerson.getAttributes().putAttribute("age", 42);
            }
        }

        var filterNames = List.of("age");
        Map<String, AdjustDemand.Adjustments> adjustments = Map.of(
                "1", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Range(40, 50)), 2.0))),
                "2", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Range(Double.NaN, Double.NaN)), 0.5))),
                "3", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Range(Double.NaN, Double.NaN)), 1.0))),
                "4", new AdjustDemand.Adjustments(filterNames, List.of(new AdjustDemand.Adjustment(List.of(new AdjustDemand.Range(Double.NaN, Double.NaN)), 1.0)))
        );
        var populationIndex = AdjustDemand.createSpatialIndex(population);

        var numberOfPersonsBefore = population.getPersons().size();
        AdjustDemand.adjust(population, cells, populationIndex, adjustments);

        // we expect only the special person to be cloned.
        Assertions.assertEquals(numberOfPersonsBefore + 1, population.getPersons().size());
    }

    @Test
    public void testGrowthRatesWithFiles() {

        new AdjustDemand().execute(
                "--plans", testUtils.getClassInputDirectory() + "input_plans.xml.gz",
                "--adjustments", testUtils.getInputDirectory() + "adjustments.csv",
                "--shp", testUtils.getClassInputDirectory() + "cells.shp",
                "--output", testUtils.getOutputDirectory() + "output_plans.xml.gz",
                "--locale", "en-EN"
        );

        var result = PopulationUtils.readPopulation(testUtils.getOutputDirectory() + "output_plans.xml.gz");
        Assertions.assertEquals(500, result.getPersons().size());
        // make sure only people from cell 1 are cloned
        var clonedNumber = result.getPersons().values().stream()
                .filter(person -> person.getId().toString().endsWith("cloned"))
                .filter(person -> person.getId().toString().startsWith("1_"))
                .count();
        Assertions.assertEquals(100, clonedNumber);
    }

    @Test
    public void testFilterWithFiles() {

        var population = PopulationUtils.readPopulation(testUtils.getClassInputDirectory() + "input_plans.xml.gz");
        for (var person : population.getPersons().values()) {

            var sex = Math.random() <= 0.5 ? "male" : "female";
            var age = (int) (Math.random() * 100);
            var anyFilter = Math.random() <= 0.9 ? "foo" : "bar";

            person.getAttributes().putAttribute("sex", sex);
            person.getAttributes().putAttribute("age", age);
            person.getAttributes().putAttribute("any-filter-name", anyFilter);
        }
        PopulationUtils.writePopulation(population, testUtils.getOutputDirectory() + "plans-with-filter.xml.gz");

        new AdjustDemand().execute(
                "--plans", testUtils.getOutputDirectory() + "plans-with-filter.xml.gz",
                "--adjustments", testUtils.getInputDirectory() + "adjustments-with-filter.csv",
                "--shp", testUtils.getClassInputDirectory() + "cells.shp",
                "--output", testUtils.getOutputDirectory() + "output_plans.xml.gz",
                "--locale", "en-EN"
        );

        // count persons with filter criteria the file has
        // cell 1 with 0-30 years, male, foo, 2.0
        // cell 1 with 30-60 years, male, foo, 2.0
        // 2 cells with no filter and 2.0
        // 1 cell with no filter and 1.0
        var cells = createCells();
        var cell1 = cells.get("1");
        var matchingFilter = population.getPersons().values().stream()
                .filter(person -> {
                    var age = (Integer) person.getAttributes().getAttribute("age");
                    var sex = (String) person.getAttributes().getAttribute("sex");
                    var anyFilter = (String) person.getAttributes().getAttribute("any-filter-name");
                    var firstAct = (Activity) person.getSelectedPlan().getPlanElements().get(0); // assuming  first one is home activity

                    return 0 <= age && age < 60 && "male".equals(sex) && "foo".equals(anyFilter) && cell1.covers(MGC.coord2Point(firstAct.getCoord()));
                })
                .count();
        var expectedNumberOfPersons = population.getPersons().size() + 2 * 100 + matchingFilter;

        var result = PopulationUtils.readPopulation(testUtils.getOutputDirectory() + "output_plans.xml.gz");
        System.out.println("Expected number: " + expectedNumberOfPersons);
        System.out.println("Population before: " + population.getPersons().size() + " Population after: " + result.getPersons().size());
        Assertions.assertEquals(expectedNumberOfPersons, result.getPersons().size());
    }

    @Test
    public void testAgeFilter() {

        // we have different things we need to parse in the age filter
        // unter x everything smaller than x
        // x bis unter y everyting >= x and < y
        // x Jahre und mehr everyting >= x

        var population = PopulationUtils.readPopulation(testUtils.getClassInputDirectory() + "input_plans.xml.gz");
        for (var person : population.getPersons().values()) {
            var age = (int) (Math.random() * 100);
            person.getAttributes().putAttribute("age", age);
        }
        PopulationUtils.writePopulation(population, testUtils.getOutputDirectory() + "plans-with-age-filter.xml.gz");

        new AdjustDemand().execute(
                "--plans", testUtils.getOutputDirectory() + "plans-with-age-filter.xml.gz",
                "--adjustments", testUtils.getInputDirectory() + "adjustments-with-age-filter.csv",
                "--shp", testUtils.getClassInputDirectory() + "cells.shp",
                "--output", testUtils.getOutputDirectory() + "output_plans.xml.gz",
                "--locale", "en-EN"
        );

        var cells = createCells();
        var cell1 = cells.get("1");
        var matchingFilter = population.getPersons().values().stream()
                .filter(person -> {
                    var age = (Integer) person.getAttributes().getAttribute("age");
                    var firstAct = (Activity) person.getSelectedPlan().getPlanElements().get(0); // assuming  first one is home activity

                    // we want everybody from cell1 who is not between 30 and 60
                    return (age < 30 || 60 <= age) && cell1.covers(MGC.coord2Point(firstAct.getCoord()));
                })
                .count();
        var expectedNumberOfPersons = population.getPersons().size() + 2 * 100 + matchingFilter;
        var result = PopulationUtils.readPopulation(testUtils.getOutputDirectory() + "output_plans.xml.gz");
        System.out.println("Expected number: " + expectedNumberOfPersons);
        System.out.println("Population before: " + population.getPersons().size() + " Population after: " + result.getPersons().size());
        Assertions.assertEquals(expectedNumberOfPersons, result.getPersons().size());

    }

    @Test
    public void testMissingLines() {
        new AdjustDemand().execute(
                "--plans", testUtils.getClassInputDirectory() + "input_plans.xml.gz",
                "--adjustments", testUtils.getInputDirectory() + "adjustments-missing-lines.csv",
                "--shp", testUtils.getClassInputDirectory() + "cells.shp",
                "--output", testUtils.getOutputDirectory() + "output_plans.xml.gz",
                "--locale", "en-EN"
        );

        var result = PopulationUtils.readPopulation(testUtils.getOutputDirectory() + "output_plans.xml.gz");
        Assertions.assertEquals(500, result.getPersons().size());
        // make sure only people from cell 1 are cloned
        var clonedNumber = result.getPersons().values().stream()
                .filter(person -> person.getId().toString().endsWith("cloned"))
                .filter(person -> person.getId().toString().startsWith("1_"))
                .count();
        Assertions.assertEquals(100, clonedNumber);
    }

    private static SimpleFeatureType createCellType() {
        var builder = new SimpleFeatureTypeBuilder();
        builder.setName("cell");
        try {
            builder.setCRS(CRS.decode("EPSG:25833"));
        } catch (FactoryException e) {
            throw new RuntimeException(e);
        }
        builder.add("the_geom", Polygon.class);
        builder.add("id", String.class);
        return builder.buildFeatureType();
    }

    private void writeCells(Map<String, PreparedGeometry> cells, String filename) {
        var featureType = createCellType();
        var featureBuilder = new SimpleFeatureBuilder(featureType);
        var simpleFeatures = cells.entrySet().stream()
                .map(e -> {
                    featureBuilder.add(e.getValue().getGeometry());
                    featureBuilder.set("id", e.getKey());
                    return featureBuilder.buildFeature(null);
                })
                .collect(Collectors.toList());
        ShapeFileWriter.writeGeometries(simpleFeatures, filename);
    }


    private static Collection<Tuple<Activity, Activity>> mergeActivities(Person person1, Person person2) {

        var acts1 = TripStructureUtils.getActivities(person1.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
        var acts2 = TripStructureUtils.getActivities(person2.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);

        Collection<Tuple<Activity, Activity>> result = new ArrayList<>();
        for (var i = 0; i < acts1.size(); i++) {
            var act1 = acts1.get(i);
            var act2 = acts2.get(i);
            result.add(Tuple.of(act1, act2));
        }

        return result;
    }

    /**
     * 1----2----3
     * |	|	 |
     * |	|    |
     * 4----5----6
     * |    |    |
     * |    |    |
     * 7----8----9
     */
    private static Map<String, PreparedGeometry> createCells() {

        var gf = new GeometryFactory();
        var coordinate1 = new Coordinate(0, 0);
        var coordinate2 = new Coordinate(1000, 0);
        var coordinate3 = new Coordinate(2000, 0);
        var coordinate4 = new Coordinate(0, 1000);
        var coordinate5 = new Coordinate(1000, 1000);
        var coordinate6 = new Coordinate(2000, 1000);
        var coordinate7 = new Coordinate(0, 2000);
        var coordinate8 = new Coordinate(1000, 2000);
        var coordinate9 = new Coordinate(2000, 2000);

        var cell1 = gf.createPolygon(new Coordinate[]{
                coordinate1, coordinate2, coordinate5, coordinate4, coordinate1
        });
        var cell2 = gf.createPolygon(new Coordinate[]{
                coordinate2, coordinate3, coordinate6, coordinate5, coordinate2
        });
        var cell3 = gf.createPolygon(new Coordinate[]{
                coordinate4, coordinate5, coordinate8, coordinate7, coordinate4
        });
        var cell4 = gf.createPolygon(new Coordinate[]{
                coordinate5, coordinate6, coordinate9, coordinate8, coordinate5
        });

        var pgf = new PreparedGeometryFactory();
        Map<String, PreparedGeometry> result = new HashMap<>();

        result.put("1", pgf.create(cell1));
        result.put("2", pgf.create(cell2));
        result.put("3", pgf.create(cell3));
        result.put("4", pgf.create(cell4));

        return result;
    }

    private static void addPersonsForCell(Population population, Geometry homeCell, Geometry workCell, String idPrefix, int size) {
        var rand = new Random();

        for (int i = 0; i < size; i++) {

            var person = population.getFactory().createPerson(Id.createPersonId(idPrefix + "_" + i));
            var plan = population.getFactory().createPlan();

            var home = createAct("home_bla", homeCell.getEnvelopeInternal(), population.getFactory(), rand);
            plan.addActivity(home);

            var leg = population.getFactory().createLeg("some-mode");
            plan.addLeg(leg);

            var other = createAct("other_activity_home", workCell.getEnvelopeInternal(), population.getFactory(), rand);
            plan.addActivity(other);

            person.addPlan(plan);
            population.addPerson(person);
        }
    }

    private static Activity createAct(String type, Envelope bounds, PopulationFactory fact, Random rand) {
        var x = bounds.getMinX() + rand.nextDouble(bounds.getWidth());
        var y = bounds.getMinY() + rand.nextDouble(bounds.getHeight());
        return fact.createActivityFromCoord(type, new Coord(x, y));
    }
}

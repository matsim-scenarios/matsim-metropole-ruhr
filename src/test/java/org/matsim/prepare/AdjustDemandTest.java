package org.matsim.prepare;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.quadtree.Quadtree;
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

import java.util.*;

import static org.junit.Assert.assertEquals;

public class AdjustDemandTest {

    /*
    @Test
    public void testGrowthRates() {

        var cells = createCells();
        var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        for (var cell : cells.entrySet()) {
            addPersonsForCell(population, cell.getValue().getGeometry(), cell.getValue().getGeometry(), cell.getKey(), 100);
        }
        var index = new AdjustDemand.SpatialIndex(population);
        Map<String, AdjustDemand.Adjustment> adjustments = Map.of(
                "1", new AdjustDemand.Adjustment(List.of(), List.of(), 2.0),
                "2", new AdjustDemand.Adjustment(List.of(), List.of(), 0.5),
                "3", new AdjustDemand.Adjustment(List.of(), List.of(), 1.0),
                "4", new AdjustDemand.Adjustment(List.of(), List.of(), 1.0)
        );

        AdjustDemand.adjust(population, cells, index, adjustments);

        var resultIndex = new AdjustDemand.SpatialIndex(population);

        for (var cell : cells.entrySet()) {

            var numberOfPersonsBefore = index.query(cell.getValue());
            var numberOfPersonsAfter = resultIndex.query(cell.getValue());
            var factor = adjustments.get(cell.getKey()).value();

            assertEquals(numberOfPersonsBefore.size() * factor, numberOfPersonsAfter.size(), 0.000001);
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
        Map<String, AdjustDemand.Adjustment> adjustments = Map.of(
                "1", new AdjustDemand.Adjustment(List.of(), List.of(), 2.0),
                "2", new AdjustDemand.Adjustment(List.of(), List.of(), 0.5),
                "3", new AdjustDemand.Adjustment(List.of(), List.of(), 1.0),
                "4", new AdjustDemand.Adjustment(List.of(), List.of(), 1.0)
        );
        var populationIndex = new AdjustDemand.SpatialIndex(population);

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
                    assertEquals(activityTuple.getFirst().getType(), activityTuple.getSecond().getType());

                    // make sure coords are not the same
                    assertNotEquals(activityTuple.getFirst().getCoord(), activityTuple.getSecond().getCoord());
                });
    }

     */

    @Test
    public void testAttributeFilter() {
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
        var populationIndex = new AdjustDemand.SpatialIndex(population);

        AdjustDemand.adjust(population, cells, populationIndex, adjustments);

        assertEquals(402, population.getPersons().size());
    }

    private static class QuadTree<T> {

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
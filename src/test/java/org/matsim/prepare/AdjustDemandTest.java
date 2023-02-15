package org.matsim.prepare;

import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class AdjustDemandTest {

	@Test
	public void testRemove() {

		var cells = createCells();
		var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		for (var cell : cells.entrySet()) {
			addPersonsForCell(population, cell.getValue().getGeometry(), cell.getKey(), 100);
		}
		Object2DoubleMap<String> adjustments = new Object2DoubleArrayMap<>(
				Map.of("1", 2., "2", 1., "3", 1., "4", 1.)
		);
		var index = new AdjustDemand.SpatialIndex(population);

		AdjustDemand.adjust(population, cells, index, adjustments);

		var resultIndex = new AdjustDemand.SpatialIndex(population);

		for (var cell : cells.entrySet()) {

			var numberOfPersonsBefore = index.query(cell.getValue());
			var numberOfPersonsAfter = resultIndex.query(cell.getValue());
			var factor = adjustments.getDouble(cell.getKey());

			assertEquals(numberOfPersonsBefore.size() * factor, numberOfPersonsAfter.size(), 0.000001);
		}
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

	private static void addPersonsForCell(Population population, Geometry cell, String idPrefix, int size) {

		var envelope = cell.getEnvelopeInternal();
		var rand = new Random();

		for (int i = 0; i < size; i++) {

			var person = population.getFactory().createPerson(Id.createPersonId(idPrefix + "_" + i));
			var plan = population.getFactory().createPlan();

			var home = createAct("home_bla", envelope, population.getFactory(), rand);
			plan.addActivity(home);

			var leg = population.getFactory().createLeg("some-mode");
			plan.addLeg(leg);

			var other = createAct("other_activity_home", envelope, population.getFactory(), rand);
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
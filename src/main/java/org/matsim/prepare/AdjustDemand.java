package org.matsim.prepare;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.matsim.analysis.TripMatrix;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(name = "adjust-demand")
public class AdjustDemand implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(AdjustDemand.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Input run directory")
	private Path runDirectory;

	@CommandLine.Option(names = "--run-id", defaultValue = "*", description = "Pattern used to match runId", required = true)
	private String runId;

	@CommandLine.Option(names = "--adjustments", description = "CSV-File with adjustments parameters for the cells within the provided shape file", required = true)
	private String adjustments;

	@CommandLine.Option(names = "--attr-name", defaultValue = "id")
	private String attrName;

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static void main(String[] args) {
		System.exit(new CommandLine(new TripMatrix()).execute(args));
	}

	@Override
	public Integer call() throws Exception {

		// first check if shapefile was provided
		if (!shp.isDefined()) throw new RuntimeException("Shapefile must be defined!");

		// read cells
		var preparedFactory = new PreparedGeometryFactory();
		Map<String, PreparedGeometry> preparedFeatures = shp.readFeatures().stream()
				.collect(Collectors.toMap(f -> (String) f.getAttribute(attrName), f -> preparedFactory.create((Geometry) f.getDefaultGeometry())));

		// read adjustments
		Object2DoubleMap<String> adjustmentValues = new Object2DoubleOpenHashMap<>();
		try (var bufReader = Files.newBufferedReader(Paths.get(adjustments)); var csvParser = CSVParser.parse(bufReader, CSVFormat.DEFAULT.withHeader(attrName, "value"))) {

			for (CSVRecord record : csvParser) {
				var value = Double.parseDouble(record.get("value"));
				var key = record.get(attrName);
				adjustmentValues.put(key, value);
			}
		}

		// read population
		var populationFile = ApplicationUtils.globFile(runDirectory, "*plans*");
		var population = PopulationUtils.readPopulation(populationFile.toString());
		var spatialIndex = new SpatialIndex(population);

		// no go through all the cells and adjust the population according to the values in adjust table
		var rand = new Random();
		for (var cell : preparedFeatures.entrySet()) {

			// get all the people inside the cell
			var personsInCell = spatialIndex.query(cell.getValue());
			// get the adjustment factor
			var factor = adjustmentValues.getDouble(cell.getKey());
			// draw that amount of persons
			var drawnPersons = personsInCell.stream()
					.filter(id -> rand.nextDouble() <= factor)
					.collect(Collectors.toSet());

			if (factor > 0) {
				// the cell grows clone the persons
				for (var id : drawnPersons) {
					var person = population.getPersons().get(id);
					var cloned = population.getFactory().createPerson(Id.createPersonId(person.getId().toString() + "_cloned"));
					cloned.setSelectedPlan(person.getSelectedPlan());
					population.addPerson(cloned);
				}
			} else {
				// the cell shrinks delete the persons
				for (var id : drawnPersons) {
					population.removePerson(id);
				}
			}
		}

		PopulationUtils.writePopulation(population, "pop-test.xml.gz");

		return 0;
	}

	private static class SpatialIndex {

		private final Quadtree index = new Quadtree();

		SpatialIndex(Population population) {

			for (var person : population.getPersons().values()) {
				var activities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
				var homeAct = findHomeAct(activities);
				var homePoint = MGC.coord2Point(homeAct.getCoord());
				var indexItem = new IndexItem(homePoint, person.getId());
				index.insert(homePoint.getEnvelopeInternal(), indexItem);
			}
		}

		Set<Id<Person>> query(PreparedGeometry geometry) {

			Set<Id<Person>> result = new HashSet<>();
			index.query(geometry.getGeometry().getEnvelopeInternal(), entry -> {

				var indexItem = (IndexItem) entry;
				var homePoint = indexItem.homePoint();
				if (geometry.covers(homePoint)) {
					result.add(indexItem.personId());
				}
			});
			return result;
		}
	}

	private static Activity findHomeAct(Collection<Activity> activities) {
		return activities.stream().filter(act -> act.getType().startsWith("home")).findAny().orElseThrow();
	}

	record IndexItem(Geometry homePoint, Id<Person> personId) {
	}
}
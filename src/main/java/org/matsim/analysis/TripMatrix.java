package org.matsim.analysis;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

;

@SuppressWarnings("unused")
@CommandLine.Command( name = "trip-matrix")
public class TripMatrix implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(TripMatrix.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Input run directory")
	private Path runDirectory;

	@CommandLine.Option(names = "--run-id", defaultValue = "*", description = "Pattern used to match runId", required = true)
	private String runId;

	@CommandLine.Option(names = "--attr-name", defaultValue = "id")
	private String attrName;

	@SuppressWarnings("FieldMayBeFinal")
	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static void main(String[] args) { System.exit(new CommandLine(new TripMatrix()).execute(args)); }
	@Override
	public Integer call() throws Exception {

		// first check if shapefile was provided
		if (!shp.isDefined()) throw new RuntimeException("Shapefile must be defined!");

		var preparedFactory = new PreparedGeometryFactory();
		Map<String, PreparedGeometry> preparedFeatures = shp.readFeatures().stream()
				.collect(Collectors.toMap(f -> (String)f.getAttribute(attrName), f -> preparedFactory.create((Geometry) f.getDefaultGeometry())));

		var tripsFile = ApplicationUtils.globFile(runDirectory, runId + "*trips*");
		var factory = new GeometryFactory();
		Object2DoubleMap<MatrixEntry> result = new Object2DoubleOpenHashMap<>();

		log.info("Start parsing trips csv file at: " + tripsFile);
		int counter = 0;
		try (var reader = createReader(tripsFile); var parser = CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader().parse(reader)) {

			for (CSVRecord record : parser) {
				var startX = Double.parseDouble(record.get("start_x"));
				var startY =Double.parseDouble( record.get("start_y"));
				var endX = Double.parseDouble(record.get("end_x"));
				var endY = Double.parseDouble(record.get("end_y"));
				var endActivity = record.get("end_activity_type");

				var startKey = findKey(startX, startY, preparedFeatures, factory);
				var endKey = findKey(endX, endY, preparedFeatures, factory);
				var entry = new MatrixEntry(startKey, endKey, endActivity);
				result.mergeDouble(entry, 1, Double::sum);

				if (counter % 100000 == 0) {
					log.info("Parsed " + counter + " trips");
				}
				counter++;
			}
		}

		var outputPath = runDirectory.resolve(runId + ".trip_matrix.csv");
		log.info("Writing output file to: " + outputPath);
		try (var writer = Files.newBufferedWriter(outputPath); var printer = CSVFormat.DEFAULT.withDelimiter(';').withHeader("start", "end", "purpose", "count").print(writer)) {

			for (var entry : result.object2DoubleEntrySet()) {
				var matrixEntry = entry.getKey();
				var count = entry.getDoubleValue();
				printer.printRecord(matrixEntry.fromKey(), matrixEntry.toKey(), matrixEntry.endActivity(), count);
			}
		}

		return 0;
	}

	private static String findKey(double x, double y, Map<String, PreparedGeometry> features, GeometryFactory factory) {

		var point = factory.createPoint(new Coordinate(x, y));

		return features.entrySet().parallelStream()
				.filter(entry -> entry.getValue().covers(point))
				.map(Map.Entry::getKey)
				.findAny()
				.orElse("OUTSIDE_SHAPE");
	}

	private static Reader createReader(Path path) throws IOException {
		var fileStream = Files.newInputStream(path);
		var gzipStream = new GZIPInputStream(fileStream);
		return new InputStreamReader(gzipStream);
	}

	static record MatrixEntry(String fromKey, String toKey, String endActivity) {}
}

package org.matsim.prepare.counts;

import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CombinedCountsWriter<T extends Identifiable<T>> {

	private List<Counts<T>> countsList = new ArrayList<>();

	@SafeVarargs
	public static void writeCounts(Path filename, Counts<Link>... countsCollection) {

		var writer = new CombinedCountsWriter<Link>();
		for (var counts : countsCollection) {
			writer.addCounts(counts);
		}
		writer.write(filename.toString());
	}

	public void write(String filename) {

		Counts<T> combinedCounts = new Counts<>();
		countsList.forEach(counts -> counts.getCounts().forEach((id, count) -> {
			// can't use map and flat map since 'getcounts' returns a treemap which doesn't implement streaming
			combinedCounts.getCounts().put(id, count);
		}));
		CountsWriter writer = new CountsWriter(combinedCounts);
		writer.write(filename);
	}

	private void addCounts(Counts<T> counts) {
		this.countsList.add(counts);
	}
}

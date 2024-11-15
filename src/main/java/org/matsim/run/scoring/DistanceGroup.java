package org.matsim.run.scoring;

/**
 * COPIED FROM BERLIN - DO NOT DEVELOP HERE. WILL BE DELETED AS SOON AS THIS FUNCTIONALITY IS MOVED THE MATSIM CORE.
 *
 * Store distance group.
 *
 * @param dist     lower bound for distance group
 * @param constant added constant
 * @param utilPerM   utility per meter, i.e. slope of linear function
 */
record DistanceGroup(double dist, double constant, double utilPerM) implements Comparable<DistanceGroup> {
	@Override
	public int compareTo(DistanceGroup o) {
		return Double.compare(dist, o.dist);
	}
}

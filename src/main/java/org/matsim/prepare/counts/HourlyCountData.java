
package org.matsim.prepare.counts;

import java.util.HashMap;
import java.util.Map;

/**
 * @author tschlenther
 */
public class HourlyCountData {

	private String id;
	private Day representedDay;

	private Map<Integer, Integer> R1Divisors;
	private Map<Integer, Integer> R2Divisors;
	private HashMap<Integer, Double> R1VolumesPerHour;
	private HashMap<Integer, Double> R2VolumesPerHour;


	HourlyCountData(String id, Day representedDay) {
		this.id = id;
		this.representedDay = representedDay;
		this.R1VolumesPerHour = new HashMap<>();
		this.R2VolumesPerHour = new HashMap<>();
		this.R1Divisors = new HashMap<>();
		this.R2Divisors = new HashMap<>();
	}

	Map<Integer, Double> getR1Values() {
		return this.R1VolumesPerHour;
	}

	Map<Integer, Double> getR2Values() {
		return this.R2VolumesPerHour;
	}


	public String getId() {
		return this.id;
	}

	public Day getRepresentedDay() {
		return representedDay;
	}

	void computeAndSetVolume(boolean direction1, int hour, double value) {
		if (direction1) {
			Integer divisor = this.R1Divisors.get(hour);
			if (divisor != null) {
				Double volume = this.R1VolumesPerHour.get(hour);
				volume = (((volume * divisor) + value) / (divisor + 1));
				divisor++;
				this.R1Divisors.put(hour, divisor);
				this.R1VolumesPerHour.put(hour, volume);
			} else {
				this.R1Divisors.put(hour, 1);
				this.R1VolumesPerHour.put(hour, value);
			}
		} else {
			Integer divisor = this.R2Divisors.get(hour);
			if (divisor != null) {
				Double volume = this.R2VolumesPerHour.get(hour);
				volume = (((volume * divisor) + value) / (divisor + 1));
				divisor++;
				this.R2Divisors.put(hour, divisor);
				this.R2VolumesPerHour.put(hour, volume);
			} else {
				this.R2Divisors.put(hour, 1);
				this.R2VolumesPerHour.put(hour, value);
			}
		}
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder("Zählstelle:\t" + this.id + "\n - RICHTUNG 1 - : \n");
		for (Integer hour : this.R1VolumesPerHour.keySet()) {
			str.append("Std ").append(hour).append("\t").append(this.R1VolumesPerHour.get(hour)).append("\n");
		}
		str.append("\n -RICHTUNG 2 : \n");
		for (Integer hour : this.R2VolumesPerHour.keySet()) {
			str.append("Std ").append(hour).append("\t").append(this.R2VolumesPerHour.get(hour)).append("\n");
		}
		return str.toString();
	}

	protected enum Day {
		WEEKDAY, WEEKEND
	}


}

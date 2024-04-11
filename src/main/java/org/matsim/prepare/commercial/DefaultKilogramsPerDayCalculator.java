package org.matsim.prepare.commercial;

import java.util.Random;

public class DefaultKilogramsPerDayCalculator {
        private final Random rnd = new Random(1234L);
        private final int workingDays;
        private final double sample;

        public DefaultKilogramsPerDayCalculator(int workingDays, double sample) {
            this.workingDays = workingDays;
            this.sample = sample;
        }

        public int calculateKilogramsPerDay(double tonsPerYear) {
            double kilogramsPerDay = this.sample * tonsPerYear * 1000 / ((double)this.workingDays);
            kilogramsPerDay = Math.floor(kilogramsPerDay + this.rnd.nextDouble());
            return (int)kilogramsPerDay;
        }
    }

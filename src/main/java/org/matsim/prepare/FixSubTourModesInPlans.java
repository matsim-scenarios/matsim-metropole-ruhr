package org.matsim.prepare;

import org.matsim.application.prepare.population.FixSubtourModes;

public class FixSubTourModesInPlans {

    public static void main(String[] args) throws Exception {
        String[] argsFixSubToursInPlans = new String[]{
                "--input=plans.xml.gz",
                "--output=plans-fixed.xml.gz",
                "--all-plans",
                "--mass-conservation"
        };
        new FixSubtourModes().execute(argsFixSubToursInPlans);
    }


}

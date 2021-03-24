package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.AnalysisSummary;
import org.matsim.application.analysis.TravelTimeAnalysis;
import org.matsim.application.prepare.CreateNetworkFromSumo;
import org.matsim.application.prepare.CreateTransitScheduleFromGtfs;
import org.matsim.application.prepare.GenerateShortDistanceTrips;
import org.matsim.application.prepare.TrajectoryToPlans;
import picocli.CommandLine;

@CommandLine.Command(header = ":: Open Metropole Ruhr Scenario ::", version = RunMetropoleRuhrScenario.VERSION)
@MATSimApplication.Prepare({
        CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class
})
@MATSimApplication.Analysis({
        AnalysisSummary.class, TravelTimeAnalysis.class
})
public class RunMetropoleRuhrScenario extends MATSimApplication{

    static final String VERSION = "1.0";

    public static void main(String[] args) {
        MATSimApplication.run(RunMetropoleRuhrScenario.class, args);
    }

}

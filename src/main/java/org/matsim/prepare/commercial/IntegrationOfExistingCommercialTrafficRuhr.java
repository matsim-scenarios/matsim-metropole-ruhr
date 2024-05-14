package org.matsim.prepare.commercial;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.PopulationUtils;
import org.matsim.smallScaleCommercialTrafficGeneration.DefaultIntegrateExistingTrafficToSmallScaleCommercialImpl;
import org.matsim.smallScaleCommercialTrafficGeneration.GenerateSmallScaleCommercialTrafficDemand;
import org.matsim.smallScaleCommercialTrafficGeneration.TrafficVolumeGeneration;

import java.nio.file.Path;
import java.util.Map;

public class IntegrationOfExistingCommercialTrafficRuhr extends DefaultIntegrateExistingTrafficToSmallScaleCommercialImpl {

    private static final Logger log = LogManager.getLogger(IntegrationOfExistingCommercialTrafficRuhr.class);
    private final Path pathExistingPlans;
    private int countReductions;

    public IntegrationOfExistingCommercialTrafficRuhr(Path pathExistingPlans) {
        this.pathExistingPlans = pathExistingPlans;
        this.countReductions = 0;
    }

    @Override
    public void readExistingCarriersFromFolder(Scenario scenario, double sampleScenario,
                                               Map<String, Map<Id<Link>, Link>> linksPerZone) {
        log.warn("Existing plans will not be added to the plans you generate now!");
    }

    @Override
    public void reduceDemandBasedOnExistingCarriers(Scenario scenario, Map<String, Map<Id<Link>, Link>> linksPerZone,
                                                    String smallScaleCommercialTrafficType,
                                                    Map<TrafficVolumeGeneration.TrafficVolumeKey, Object2DoubleMap<Integer>> trafficVolumePerTypeAndZone_start,
                                                    Map<TrafficVolumeGeneration.TrafficVolumeKey, Object2DoubleMap<Integer>> trafficVolumePerTypeAndZone_stop) {
        // we only have existing freight traffic
        if (smallScaleCommercialTrafficType.equals(
                GenerateSmallScaleCommercialTrafficDemand.SmallScaleCommercialTrafficType.commercialPersonTraffic.toString()))
            return;

        log.info("Reducing the demand of '{}' based on the existing LTL trips!", smallScaleCommercialTrafficType);
        log.warn("Existing long-haul trips and FTL trips will not be considered in the demand reduction!");
        log.warn(
                "Assuming that sample of the currently generating scenario is the same in the existing plans. The existing plans will not be sampled");
        Population population = PopulationUtils.readPopulation(pathExistingPlans.toString());

        for (Person freightPerson : population.getPersons().values()) {
            // we only reduce the volume of LTL trips and not for waste collection
            if (!PopulationUtils.getSubpopulation(freightPerson).equals(
                    "LTL_trips") || (int)freightPerson.getAttributes().getAttribute("goodsType") == 140)
                continue;
            Plan selectedPlan = freightPerson.getSelectedPlan();
            Id<Link> startLink = PopulationUtils.getFirstActivity(selectedPlan).getLinkId();
            String startZone = findZoneOfLink(linksPerZone, startLink);

            for (PlanElement planElement : freightPerson.getSelectedPlan().getPlanElements()) {
                if (planElement instanceof Activity activity) {
                    if (activity.getType().equals("pickup")) {
                        if (!activity.getLinkId().equals(startLink)) {
                            throw new RuntimeException(
                                    "Current version: The pickup activity should be at the same location as the start location of the trip!");
                        }
                        continue;
                    }
                    if (activity.getType().equals("delivery")) {
                        String stopZone = findZoneOfLink(linksPerZone, activity.getLinkId());
                        String modeORvehType = "vehTyp3"; //TODO
                        Integer purpose = 4; //TODO
                        try {
                            reduceVolumeForThisExistingJobElement(trafficVolumePerTypeAndZone_start, trafficVolumePerTypeAndZone_stop, modeORvehType,
                                purpose, startZone, stopZone);
                            countReductions++;
                        } catch (IllegalArgumentException e) {
                            log.warn(
                                    "For agent {} both locations of a shipment (startZone: {}, stopZone: {}) are not part of the zones. That's why the traffic volume was not reduces by this service.",
                                    freightPerson.getId(), startZone, stopZone);
                        }
                    }
                }
            }
        }
        log.info("Reduced the demand of '{}' based on the existing LTL trips! Number of reduced trips: {}",
                smallScaleCommercialTrafficType, countReductions);
    }
}


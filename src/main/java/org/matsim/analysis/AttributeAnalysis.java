package org.matsim.analysis;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AttributeAnalysis {


    public static void main (String args []) throws IOException {

        String openPopulationPath = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20230918_OpenData_Ruhr_300m/populaton.xml.gz";
        String oldPopulationPath = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz";
        String processedPopulationPath = "../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.plans.xml.gz";

        Population closedPopulation = PopulationUtils.readPopulation(oldPopulationPath);
        Population openPopulation = PopulationUtils.readPopulation(openPopulationPath);
        //PopulationUtils.readPopulation(processedPopulationPath);

        List<MapDifference> differenceAttributes = new ArrayList<>();
        List<List> differenceActivities = new ArrayList<>();

        for (Person personClosed: closedPopulation.getPersons().values()) {
            for(Person personOpen: openPopulation.getPersons().values()) {
                if (personClosed.getId().equals(personOpen.getId())) {
                    // first compare attributes
                    Map<String, Object> personClosedAttributes = personClosed.getAttributes().getAsMap();
                    Map<String, Object> personOpenAttributes = personOpen.getAttributes().getAsMap();
                    MapDifference<String, Object> diff = Maps.difference(personClosedAttributes, personOpenAttributes);
                    System.out.println(diff.toString());
                    if (diff.equals(false)) {
                        System.out.println("This is not supposed to happen");
                    }

                /*    //second activities
                    List<Activity> openActivities = PopulationUtils.getActivities(personClosed.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
                    List<Activity> closedActivities = PopulationUtils.getActivities(personOpen.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
                    List<String> differences = new ArrayList<>((CollectionUtils.removeAll(openActivities, closedActivities)));
                    differenceActivities.add(differences);
                    break;*/
                }
            }
        }

       /* System.out.println(differenceActivities.size());
        System.out.println(differenceAttributes.size());*/


     /*   BufferedWriter br = new BufferedWriter(new FileWriter("activites.txt"));
        for (String str : stringList) {
            br.write(str + System.lineSeparator());
        }
        br.close();*/



    }
}

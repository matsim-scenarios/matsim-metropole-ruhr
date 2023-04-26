package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.prepare.AdjustDemand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class DiffPop {
    public static void main(String[] args) throws IOException {

        var before = PopulationUtils.readPopulation(args[0]);
        var after = PopulationUtils.readPopulation(args[1]);

        var removed = before.getPersons().keySet().stream()
                .filter(id -> !after.getPersons().containsKey(id))
                .collect(Collectors.toSet());

        var added = after.getPersons().values().stream()
                .filter(person -> person.getId().toString().endsWith(AdjustDemand.PERSON_ID_SUFFIX))
                .map(Identifiable::getId)
                .collect(Collectors.toSet());

        var cloneOriginals = added.stream()
                .map(id -> id.toString().split(AdjustDemand.PERSON_ID_SUFFIX)[0])
                .map(Id::createPersonId)
                .collect(Collectors.toSet());

        var outputDir = Paths.get(args[2]);
        print(outputDir.resolve("removed.csv"), removed, before);
        print(outputDir.resolve("added.csv"), added, after);
        print(outputDir.resolve("originals.csv"), cloneOriginals, before);
        print(outputDir.resolve("before.csv"), before.getPersons().keySet(), before);
        print(outputDir.resolve("after.csv"), before.getPersons().keySet(), before);
    }

    private static void print(Path file, Set<Id<Person>> ids, Population source) {
        try (var writer = Files.newBufferedWriter(file);
             var printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("id", "x", "y"))) {
            for (var id : ids) {
                var person = source.getPersons().get(id);
                var activities = TripStructureUtils.getActivities(person.getSelectedPlan(), TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
                var homeAct = findHomeAct(activities);
                printer.printRecord(id, homeAct.getCoord().getX(), homeAct.getCoord().getY());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Activity findHomeAct(Collection<Activity> activities) {
        return activities.stream().filter(act -> act.getType().startsWith("home")).findAny().orElseThrow();
    }
}

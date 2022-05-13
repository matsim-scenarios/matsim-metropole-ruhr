package org.matsim.analysis;

import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Person2HomeCoord {

    private static final Logger log = Logger.getLogger(Person2HomeCoord.class);
    private static final String AREA_SHP_FILE = "../../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/dvg2_EPSG25832_Shape/dvg2krs_nw.shp";
    private static final String inputPlan = "../../runs-svn/rvr-ruhrgebiet/v1.2.1/036/036.output_plans.xml.gz";
    private static final String outputResult = "../../runs-svn/rvr-ruhrgebiet/v1.2.1/036/person2Home.csv";

    public static void main(String[] args) throws IOException {
        Population population = PopulationUtils.readPopulation(inputPlan);
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(AREA_SHP_FILE);
        Map<Person, Coord> person2homeLocation = new HashMap<>();
        Map<Coord, String> coord2Area = new HashMap<>();

        for (Person person : population.getPersons().values()) {
            Plan selectedPlan = person.getSelectedPlan();
            List<Activity> activities = TripStructureUtils.getActivities(selectedPlan, TripStructureUtils.StageActivityHandling.ExcludeStageActivities);
            for (Activity act : activities) {
                if (act.getType().contains("home")) {
                    person2homeLocation.put(person, act.getCoord());
                    coord2Area.computeIfAbsent(act.getCoord(), coord -> getArea(coord, features));
                    break;
                }
            }
        }
        Person2HomeCoord.write(";", person2homeLocation, coord2Area);
        log.info("done");
    }

    private static void write(String splitSymbol, Map<Person, Coord> person2homeLocation, Map<Coord, String> coord2Area) throws IOException {
        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(Person2HomeCoord.outputResult), StandardCharsets.UTF_8);
        BufferedWriter bw = new BufferedWriter(outputStreamWriter);
        bw.write("person" + splitSymbol + "home_x" + splitSymbol + "home_y" + splitSymbol + "area");
        person2homeLocation.forEach((person, coord) -> {
            try {
                bw.newLine();
                bw.write(person.getId().toString() + splitSymbol + coord.getX() + splitSymbol + coord.getY() + splitSymbol + coord2Area.get(coord));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        bw.close();
        log.info("writing done");
    }

    @Nullable
    private static String getArea(Coord coord, Collection<SimpleFeature> features) {
        Point p = MGC.coord2Point(coord);
        for (SimpleFeature feature : features) {
            Geometry defaultGeometry = (Geometry) feature.getDefaultGeometry();
            if (p.within(defaultGeometry)) {
                return (String) feature.getAttribute("GN");
            }
        }
        return "other";
    }
}



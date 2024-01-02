package org.matsim.analysis;


import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;

@CommandLine.Command(
        name = "analyze-population",
        description = "Extract the home location of the persons in the population file and write it into a csv"
)
public class Person2Home {

    public static void main(String[] args) throws IOException {
        String shapeFileZones = "../../shared-svn/projects/rvr-metropole-ruhr/data/shapeFiles/dvg2krs_ruhrgebiet-rvr/dvg2krs_ruhrgebiet-rvr.shp";
        Collection<SimpleFeature> features = ShapeFileReader.getAllFeatures(shapeFileZones);
        analyzeHomeLocation(PopulationUtils.readPopulation("../../respos/shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.plans.xml.gz"), features);
    }


    private static void analyzeHomeLocation(Population population, Collection<SimpleFeature> analyzedArea) throws IOException {
        CSVPrinter csvWriter = new CSVPrinter(new FileWriter("persons-home-locations.csv"), CSVFormat.TDF);
        csvWriter.printRecord("person", "home_x", "home_y", "home_location");

        // TODO: a similar functionality is already implemented in ExtractHomeCoordinates
        // this class will become obsolete, but a newer MATSim version is needed

        for (SimpleFeature feature : analyzedArea) {
            Geometry defaultGeometry = (Geometry) feature.getDefaultGeometry();

            for (Person p : population.getPersons().values()) {
                for (PlanElement planElement : p.getSelectedPlan().getPlanElements()) {
                    if (planElement instanceof Activity) {
                        String actType = ((Activity) planElement).getType();
                        if (actType.startsWith("home")) {
                            Coord homeCoord = ((Activity) planElement).getCoord();
                            if (MGC.coord2Point(homeCoord).within(defaultGeometry)) {
                                csvWriter.printRecord(p.getId().toString(),
                                        Double.toString(homeCoord.getX()), Double.toString(homeCoord.getY()), feature.getAttribute("GN").toString());
                            }
                        }
                    }
                }
            }
        }
    }

}


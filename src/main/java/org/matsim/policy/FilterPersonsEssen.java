package org.matsim.policy;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;

public class FilterPersonsEssen {

    public static void main(String[] args) {

        var geometry = findGeometry();
        var population = PopulationUtils.readPopulation("path/to/file");

        var outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        for (var person : population.getPersons().values()) {

            for (var element : person.getSelectedPlan().getPlanElements()) {

                if (element instanceof Activity) {
                    var activity = (Activity)element;
                    if (activity.getType().startsWith("home")) {

                        var point = MGC.coord2Point(activity.getCoord());

                        if (geometry.contains(point)) {
                            outputPopulation.addPerson(person);
                        }
                    }
                }
            }

           /* if ("m".equals(person.getAttributes().getAttribute("sex"))) {
                outputPopulation.addPerson(person);
            }

            */
        }

        PopulationUtils.writePopulation(outputPopulation, "path/to/output/population");
    }

    static Geometry findGeometry() {

        var features = ShapeFileReader.getAllFeatures("C:\\Users\\Janekdererste\\Downloads\\vg5000_12-31.utm32s.shape.ebenen\\vg5000_12-31.utm32s.shape.ebenen\\vg5000_ebenen_1231\\VG5000_KRS.shp");

        for (var feature : features) {

            var genValue = (String) feature.getAttribute("GEN");

            if ("Essen".equals(genValue)){
                System.out.println("Found feature Essen.");
                return (Geometry) feature.getDefaultGeometry();
            }
        }

        throw new RuntimeException("This didn't work.");
    }
}

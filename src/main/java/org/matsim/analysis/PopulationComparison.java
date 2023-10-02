package org.matsim.analysis;

import org.matsim.application.MATSimAppCommand;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.population.PopulationAttributeAnalysis;
import org.matsim.application.analysis.population.SubTourAnalysis;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import picocli.CommandLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class PopulationComparison  {


    private static final Logger log = LogManager.getLogger(PopulationComparison.class);
    public static void main(String [] args) {

        String openPopulation = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20230918_OpenData_Ruhr_300m/populaton.xml.gz";
        String oldPopulation = "../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz";
        String processedPopulation = "../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-25pct.plans.xml.gz";
        String shp ="../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20230918_OpenData_Ruhr_300m/dilutionArea.shp";

        CheckPopulation checkPopulation = new CheckPopulation();

        String[] argsNewPopulation = new String[]{
                openPopulation,
                "--shp", shp,
                "--input-crs", "EPSG:25832",
                "--shp-crs", "EPSG:25832"
        };

        checkPopulation.execute(argsNewPopulation);
        log.info("---------------");
        log.info("start check population analysis of old population");
        log.info("---------------");

        String[] argsOldPopulation = new String[]{
                oldPopulation,
                "--shp", shp,
                "--input-crs", "EPSG:25832",
                "--shp-crs", "EPSG:25832"
        };
        checkPopulation.execute(argsOldPopulation);

        log.info("---------------");
        log.info("start check population analysis of processed population");
        log.info("---------------");

        String[] argsProcessedPopulation = new String[]{
                processedPopulation,
                "--shp", shp,
                "--input-crs", "EPSG:25832",
                "--shp-crs", "EPSG:25832"
        };
        checkPopulation.execute(argsProcessedPopulation);

        //new PopulationAttributeAnalysis().execute("--population", "/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/population.xml.gz");

        //new SubTourAnalysis().execute();


    }

}

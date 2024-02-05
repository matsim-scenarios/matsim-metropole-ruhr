package org.matsim.run;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import com.google.inject.Inject;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.bicycle.BicycleConfigGroup;
import org.matsim.contrib.bicycle.BicycleModule;
import org.matsim.contrib.bicycle.Bicycles;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

public class RunQasimComparison extends RunMetropoleRuhrScenario {

    public static void main(String[] args) {
        MATSimApplication.run(RunQasimComparison.class, args);
    }

    @Override
    protected Config prepareConfig(Config config) {

        config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controler().setFirstIteration(0);
        config.controler().setLastIteration(0);

        config.transit().setUseTransit(false);
        config.transit().setTransitScheduleFile(null);
        config.transit().setVehiclesFile(null);

        BicycleConfigGroup bikeConfigGroup = ConfigUtils.addOrGetModule(config, BicycleConfigGroup.class);
        bikeConfigGroup.setBicycleMode(TransportMode.bike);

        return config;
    }

    @Override
    protected void prepareControler(Controler controler) {

        controler.addOverridingModule(new BicycleModule());
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addMobsimListenerBinding().to(MobsimTimer.class);
            }
        });
    }


    private static class MobsimTimer implements MobsimInitializedListener, MobsimBeforeCleanupListener {

        private Instant start;

        @Inject
        private Config config;

        @Inject
        private OutputDirectoryHierarchy outDir;

        @Override
        public void notifyMobsimInitialized(MobsimInitializedEvent e) {
            start = Instant.now();
        }

        @Override
        public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent e) {
            var now = Instant.now();
            var duration = Duration.between(start, now);
            var size = config.qsim().getNumberOfThreads();
            var filename = Paths.get(outDir.getOutputFilename("instrument-mobsim.csv"));
            try (var writer = Files.newBufferedWriter(filename); var p = new CSVPrinter(writer, createWriteFormat("timestamp", "func", "duration", "size"))) {
                p.printRecord(Instant.now().getNano(), "org.matsim.core.mobsim.qsim.run", duration.toNanos(), size);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        public static CSVFormat createWriteFormat(String... header) {
            return CSVFormat.DEFAULT.builder()
                    .setHeader(header)
                    .setSkipHeaderRecord(false)
                    .build();
        }
    }


}

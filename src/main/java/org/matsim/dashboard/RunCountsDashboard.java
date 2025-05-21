/* *********************************************************************** *
 * project: org.matsim.*
 * Controler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.dashboards;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.dashboard.EmissionsDashboard;
import org.matsim.simwrapper.dashboard.NoiseDashboard;
import org.matsim.simwrapper.dashboard.TrafficCountsDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;

@CommandLine.Command(
	name = "simwrapper",
	description = "Run additional analysis and create SimWrapper dashboard for existing run output."
)
public final class RunCountsDashboard implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(org.matsim.dashboards.RunCountsDashboard.class);

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which dashboards are to be generated.")
	private List<Path> inputPaths;

	@CommandLine.Mixin
	private final ShpOptions shp = new ShpOptions();


	public RunCountsDashboard(){
//		public constructor needed for testing purposes.
	}

	@Override
	public Integer call() throws Exception {

		for (Path runDirectory : inputPaths) {
			log.info("Running on {}", runDirectory);

			String configPath = ApplicationUtils.matchInput("config.xml", runDirectory).toString();
			Config config = ConfigUtils.loadConfig(configPath);
			SimWrapper sw = SimWrapper.create(config);

			SimWrapperConfigGroup simwrapperCfg = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);
			if (shp.isDefined()){
				simwrapperCfg.defaultParams().shp = shp.getShapeFile();
			}
			//skip default dashboards
			simwrapperCfg.defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

			//add dashboards
			sw.addDashboard(new TrafficCountsDashboard());



			try {
				sw.generate(runDirectory, true);
				sw.run(runDirectory);
			} catch (IOException e) {
				throw new InterruptedIOException();
			}
		}

		return 0;
	}

	public static void main(String[] args) {
		new org.matsim.dashboards.RunCountsDashboard().execute(args);

	}

}

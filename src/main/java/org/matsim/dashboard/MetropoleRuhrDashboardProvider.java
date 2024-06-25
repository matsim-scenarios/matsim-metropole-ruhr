package org.matsim.dashboard;

import org.matsim.application.prepare.network.CreateAvroNetwork;
import org.matsim.core.config.Config;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;

/**
 * Provider for default dashboards in the scenario.
 * Declared in META-INF/services
 */
public class MetropoleRuhrDashboardProvider implements DashboardProvider {

	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {

		TripDashboard trips = new TripDashboard("mode_share_ref.csv", "mode_share_per_dist_ref.csv", "mode_users_ref.csv")
			.setAnalysisArgs("--dist-groups", "0,1000,2000,5000,10000,20000,50000");

		// Large number of attributes is reduced to the most important ones
		simWrapper.getData().addGlobalArgs(
			CreateAvroNetwork.class, "--filter-properties", "allowed_speed,surface,type,bicycleInfrastructureSpeedFactor,bike,smoothness"
		);

		return List.of(trips);
	}

}

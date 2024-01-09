package org.matsim.run;

class KNRunMetropoleRuhrScenario{

	public static void main( String[] args ){
		args = new String[]{
				"--config:controler.lastIteration", "0"
//				, "--download-input"
				, "--config:plans.inputPlansFile=../../../../../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.plans.xml.gz"
//				,"--config:network.inputNetworkFile=../../../../../../public-svn/matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4.network_resolutionHigh-with-pt.xml.gz"
				, "--1pct"
				, "run"
		};

		RunMetropoleRuhrScenario.main( args );

	}

}

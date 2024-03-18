package org.matsim.run;

class RunOpenMetropoleRuhrScenario {

	public static void main(String[] args) {
		RunMetropoleRuhrScenario.main(new String[]{
			"--config", "scenarios/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-3pct.open-config.xml"
		});
	}

}

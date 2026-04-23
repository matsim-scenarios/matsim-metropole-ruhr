package org.matsim.run;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

public class MetropoleRuhrTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	@Disabled
	void test() {
		MATSimApplication.execute(MetropoleRuhrScenario.class, "--iterations", "1",
			"--config", "scenarios/metropole-ruhr-v2024.1/input/metropole-ruhr-v2024.1-1pct.config.xml",
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--output", utils.getOutputDirectory(),
			// This plans file contains only one agent, plan includes car and pt.
			"--config:plans.inputPlansFile", "../../../" + utils.getInputDirectory() + "plans1.metropole-ruhr.v2024.1.xml.gz",
			"--config:simwrapper.defaultDashboards=disabled");
	}
}

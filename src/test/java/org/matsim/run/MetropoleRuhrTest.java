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
	@Disabled // Enable once input comes from public-svn
	void test() {
		Config config = ConfigUtils.loadConfig(MetropoleRuhrScenario.CONFIG_PATH);
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).setDefaultDashboards(SimWrapperConfigGroup.Mode.disabled);

		// Setting the plans file to null and pass a local file with only one agent. Reduces test runtime.
		config.plans().setInputFile(null);

		MATSimApplication.execute(MetropoleRuhrScenario.class, config, "--iterations", "1",
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--output", utils.getOutputDirectory(),
			// This plans file contains only one agent, plan includes car and pt.
			"--config:plans.inputPlansFile", utils.getInputDirectory() + "/plans1.metropole-ruhr.v2024.1.xml",
			"--1pct");
	}
}

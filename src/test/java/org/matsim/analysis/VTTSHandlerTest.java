package org.matsim.analysis;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.testcases.MatsimTestUtils;

import java.io.File;
import java.io.IOException;


public class VTTSHandlerTest{

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();
	static VTTSHandler static_handler;
	static boolean isInitialized = false;

	VTTSHandler handler;

	@BeforeEach
	public void initialize(){
		//BeforeAll not working here because we need non-static variables
		if(VTTSHandlerTest.isInitialized){
			handler = VTTSHandlerTest.static_handler;
			return;
		}

		//Note, the only important part of the config is the score. Everything else (network, plans, ...) is redundant
		//This test uses a config which has all paths set to null
		Config ruhrConfig = ConfigUtils.loadConfig(utils.getPackageInputDirectory() + "metropole-ruhr-v2.0-3pct.config.xml");
		SnzActivities.addScoringParams(ruhrConfig);

		Scenario ruhrScenario = ScenarioUtils.loadScenario(ruhrConfig);

		handler = new VTTSHandler(ruhrScenario, new String[]{}, "interaction");
		EventsManager manager = EventsUtils.createEventsManager();
		manager.addHandler(handler);

		EventsUtils.readEvents(manager, utils.getPackageInputDirectory() + "events_reduced.xml.gz");

		VTTSHandlerTest.static_handler = handler;
		VTTSHandlerTest.isInitialized = true;
	}

	@Test
	public void testPrintVTTS() throws IOException {
		handler.printVTTS(utils.getOutputDirectory() + "testPrintVTTS");

		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTS"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTS"), "utf-8"),
			"Test of printVTTS() failed: Output does not match the reference-file!");
	}

	@Test
	public void testPrintCarVTTS() throws IOException {
		handler.printCarVTTS(utils.getOutputDirectory() + "testPrintCarVTTS");

		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintCarVTTS"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintCarVTTS"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
	}

	@Test
	public void testPrintVTTSMode() throws IOException {
		handler.printVTTS(utils.getOutputDirectory() + "testPrintVTTSMode1", TransportMode.pt);

		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTSMode1"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTSMode1"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");

		handler.printVTTS(utils.getOutputDirectory() + "testPrintVTTSMode2", TransportMode.bike);

		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTSMode2"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTSMode2"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
	}

	@Test
	public void testPrintAvgVTTSperPerson() throws IOException {
		handler.printAvgVTTSperPerson(utils.getOutputDirectory() + "testPrintAvgVTTSperPerson");

		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintAvgVTTSperPerson"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintAvgVTTSperPerson"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
	}

	@Test
	public void testPrintVTTSstatistics() throws IOException {
		handler.printVTTSstatistics(utils.getOutputDirectory() + "testPrintVTTSstatistics1", TransportMode.car, new Tuple<>(50.0, 60023.4));

/*
		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTSstatistics1"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTSstatistics1"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
*/

		handler.printVTTSstatistics(utils.getOutputDirectory() + "testPrintVTTSstatistics2", TransportMode.car, new Tuple<>(90.0, 10010.0));

/*
		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTSstatistics2"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTSstatistics2"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
*/

		handler.printVTTSstatistics(utils.getOutputDirectory() + "testPrintVTTSstatistics3", TransportMode.bike, new Tuple<>(50.0, 60023.4));

/*
		Assertions.assertEquals(
			FileUtils.readFileToString(new File(utils.getPackageInputDirectory() + "testPrintVTTSstatistics3"), "utf-8"),
			FileUtils.readFileToString(new File(utils.getOutputDirectory() + "testPrintVTTSstatistics3"), "utf-8"),
			"Test of printCarVTTS() failed: Output does not match the reference-file!");
*/
	}

	@Test
	public void testGetCarVTTS() {
		Assertions.assertEquals(6.88, handler.getCarVTTS(Id.createPersonId("freight_203812_0_FTL"), 400), 0.01);
		Assertions.assertEquals(0.156, handler.getCarVTTS(Id.createPersonId("commercialPersonTraffic_051081_2_0"), 50000), 0.001);
		Assertions.assertEquals(0.120, handler.getCarVTTS(Id.createPersonId("commercialPersonTraffic_051081_2_0"), 10000), 0.001);
	}

	/*
	@Test
	public void testGetAvgVTTSh() {
	}

	@Test
	public void testTestGetAvgVTTSh() {
	}

	@Test
	public void testApplyIncomeDependantScoring(){
		// TODO
	}*/
}

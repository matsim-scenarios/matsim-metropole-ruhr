package org.matsim.run.scoring;

import jakarta.inject.Singleton;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

/**
 * COPIED FROM BERLIN - DO NOT DEVELOP HERE. WILL BE DELETED AS SOON AS THIS FUNCTIONALITY IS MOVED THE MATSIM CORE.
 *
 * Module to bind components needed for advanced scoring functionality configured by {@link AdvancedScoringConfigGroup}.
 */
public class AdvancedScoringModule extends AbstractModule {

	@Override
	public void install() {

		ConfigUtils.addOrGetModule(getConfig(), AdvancedScoringConfigGroup.class);

		bind(ScoringParametersForPerson.class).to(IndividualPersonScoringParameters.class).in(Singleton.class);

		addControlerListenerBinding().to(AdvancedScoringOutputWriter.class).in(Singleton.class);

		bindScoringFunctionFactory().to(AdvancedScoringFunctionFactory.class).in(Singleton.class);
	}
}

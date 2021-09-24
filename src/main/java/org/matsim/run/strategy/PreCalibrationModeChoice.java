package org.matsim.run.strategy;

import org.matsim.core.config.groups.ChangeModeConfigGroup;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.replanning.modules.ChangeSingleLegMode;
import org.matsim.core.replanning.selectors.RandomPlanSelector;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;

import javax.inject.Inject;
import javax.inject.Provider;

public class PreCalibrationModeChoice implements Provider<PlanStrategy> {

	private final GlobalConfigGroup globalConfigGroup;
	private final QSimConfigGroup qSimConfigGroup;
	private final ChangeModeConfigGroup changeLegModeConfigGroup;
	private final Provider<TripRouter> tripRouterProvider;
	private final ActivityFacilities activityFacilities;
	private final TimeInterpretation timeInterpretation;

	@Inject
	PreCalibrationModeChoice(GlobalConfigGroup globalConfigGroup, QSimConfigGroup qSimConfigGroup, ChangeModeConfigGroup changeLegModeConfigGroup, ActivityFacilities activityFacilities, Provider<TripRouter> tripRouterProvider, TimeInterpretation timeInterpretation) {
		this.globalConfigGroup = globalConfigGroup;
		this.qSimConfigGroup = qSimConfigGroup;
		this.changeLegModeConfigGroup = changeLegModeConfigGroup;
		this.activityFacilities = activityFacilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
	}

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(new RandomPlanSelector<>());

		builder.addStrategyModule(new ChangeSingleLegMode(globalConfigGroup, changeLegModeConfigGroup));
		builder.addStrategyModule(new CachedReRoute(activityFacilities, tripRouterProvider, globalConfigGroup, qSimConfigGroup, timeInterpretation));

		return builder.build();
	}
}

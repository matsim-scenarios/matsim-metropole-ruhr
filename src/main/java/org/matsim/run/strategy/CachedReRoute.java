package org.matsim.run.strategy;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.population.algorithms.PersonAlgorithm;
import org.matsim.core.population.algorithms.PlanAlgorithm;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.replanning.modules.AbstractMultithreadedModule;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.vehicles.Vehicle;

import javax.inject.Provider;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CachedReRoute extends AbstractMultithreadedModule {

	private final ActivityFacilities facilities;
	private final Provider<TripRouter> tripRouterProvider;
	private final TimeInterpretation timeInterpretation;
	private final Set<String> mainModes;

	public CachedReRoute(ActivityFacilities facilities, Provider<TripRouter> tripRouterProvider, GlobalConfigGroup globalConfigGroup, QSimConfigGroup qSimConfigGroup, TimeInterpretation timeInterpretation) {
		super(globalConfigGroup);
		this.facilities = facilities;
		this.tripRouterProvider = tripRouterProvider;
		this.timeInterpretation = timeInterpretation;
		this.mainModes = new HashSet<>(qSimConfigGroup.getMainModes());
	}

	@Override
	public PlanAlgorithm getPlanAlgoInstance() {
		return new CachedPlanRouter(this.tripRouterProvider.get(), this.mainModes, this.facilities, this.timeInterpretation);
	}


	/**
	 * This was copied from the MATSim main repo and needs to be consolidated into the core.
	 */
	private static final class CachedPlanRouter implements PlanAlgorithm, PersonAlgorithm {

		private static final Logger log = Logger.getLogger(CachedPlanRouter.class);

		private final TripRouter tripRouter;
		private final Set<String> mainModes;
		private final ActivityFacilities facilities;
		private final TimeInterpretation timeInterpretation;

		/**
		 * Initialises an instance.
		 *
		 * @param tripRouter the {@link TripRouter} to use to route individual trips
		 * @param mainModes
		 * @param facilities the {@link ActivityFacilities} to which activities are refering.
		 *                   May be <tt>null</tt>: in this case, the router will be given facilities wrapping the
		 */
		public CachedPlanRouter(
				final TripRouter tripRouter, Set<String> mainModes, final ActivityFacilities facilities,
				final TimeInterpretation timeInterpretation) {
			this.tripRouter = tripRouter;
			this.mainModes = mainModes;
			this.facilities = facilities;
			this.timeInterpretation = timeInterpretation;
		}

		/**
		 * Gives access to the {@link TripRouter} used
		 * to compute routes.
		 *
		 * @return the internal TripRouter instance.
		 */
		@Deprecated // get TripRouter out of injection instead. kai, feb'16
		public TripRouter getTripRouter() {
			return tripRouter;
		}

		@Override
		public void run(final Plan plan) {
			final List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
			TimeTracker timeTracker = new TimeTracker(timeInterpretation);

			// currently, the whole plan is rerouted
			// rerouting only some parts throws strange exceptions later on
			if (trips.stream().anyMatch(this::needsReRoute))

				for (TripStructureUtils.Trip oldTrip : trips) {
					final String routingMode = TripStructureUtils.identifyMainMode(oldTrip.getTripElements());
					timeTracker.addActivity(oldTrip.getOriginActivity());

					if (log.isDebugEnabled()) log.debug("about to call TripRouter with routingMode=" + routingMode);
					final List<? extends PlanElement> newTrip = tripRouter.calcRoute( //
							routingMode, //
							FacilitiesUtils.toFacility(oldTrip.getOriginActivity(), facilities), //
							FacilitiesUtils.toFacility(oldTrip.getDestinationActivity(), facilities), //
							timeTracker.getTime().seconds(), //
							plan.getPerson(), //
							oldTrip.getTripAttributes() //
					);

					putVehicleFromOldTripIntoNewTripIfMeaningful(oldTrip, newTrip);
					TripRouter.insertTrip(
							plan,
							oldTrip.getOriginActivity(),
							newTrip,
							oldTrip.getDestinationActivity());
				}
		}

		private boolean needsReRoute(TripStructureUtils.Trip trip) {

			// reroute if there is no route or if something changed
			// reroute if the route should be a network route
			return trip.getLegsOnly().stream().anyMatch(
					t -> t.getRoute() == null || (mainModes.contains(t.getMode()) && !(t.getRoute() instanceof NetworkRoute))
			) || trip.getLegsOnly().stream().map(TripStructureUtils::getRoutingMode).collect(Collectors.toSet()).size() > 1;
		}

		/**
		 * If the old trip had vehicles set in its network routes, and it used a single vehicle,
		 * and if the new trip does not come with vehicles set in its network routes,
		 * then put the vehicle of the old trip into the network routes of the new trip.
		 *
		 * @param oldTrip The old trip
		 * @param newTrip The new trip
		 */
		private static void putVehicleFromOldTripIntoNewTripIfMeaningful(TripStructureUtils.Trip oldTrip, List<? extends PlanElement> newTrip) {
			Id<Vehicle> oldVehicleId = getUniqueVehicleId(oldTrip);
			if (oldVehicleId != null) {
				for (Leg leg : TripStructureUtils.getLegs(newTrip)) {
					if (leg.getRoute() instanceof NetworkRoute) {
						if (((NetworkRoute) leg.getRoute()).getVehicleId() == null) {
							((NetworkRoute) leg.getRoute()).setVehicleId(oldVehicleId);
						}
					}
				}
			}
		}

		private static Id<Vehicle> getUniqueVehicleId(TripStructureUtils.Trip trip) {
			Id<Vehicle> vehicleId = null;
			for (Leg leg : trip.getLegsOnly()) {
				if (leg.getRoute() instanceof NetworkRoute) {
					if (vehicleId != null && (!vehicleId.equals(((NetworkRoute) leg.getRoute()).getVehicleId()))) {
						return null; // The trip uses several vehicles.
					}
					vehicleId = ((NetworkRoute) leg.getRoute()).getVehicleId();
				}
			}
			return vehicleId;
		}

		@Override
		public void run(final Person person) {
			for (Plan plan : person.getPlans()) {
				run(plan);
			}
		}
	}

}

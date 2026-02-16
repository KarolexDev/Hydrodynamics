package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.Map;

/**
 * Abstract handler for network dynamics calculations.
 *
 * <p>Subclasses implement the specific physics for their domain (thermodynamics,
 * electricity, fluids, etc.) by providing flux calculations, state updates, and
 * convergence detection.
 *
 * @param <S> the ComponentState implementation for this physics domain
 * @param <F> the FluxVector implementation for this physics domain
 */
public abstract class DynamicsHandler<S extends ComponentState, F extends FluxVector> {

    /**
     * Calculates the ideal flux from source to target based on state differences.
     *
     * <p>This method computes the flux that would occur in the absence of any
     * edge constraints (e.g., from pressure/temperature gradients, voltage differences).
     *
     * <p>For thermodynamics, this might use modified Onsager reciprocal relations.
     * For electricity, Ohm's law. For fluids, pressure-driven flow equations.
     *
     * @param source the state of the component initiating the flux
     * @param target the state of the component receiving the flux
     * @param edgeLength the physical length/distance between components (for edges)
     * @return the ideal flux from source to target
     */
    public abstract F calculateIdealFlux(S source, S target, double edgeLength);

    /**
     * Updates a node's state based on net incoming fluxes from all neighbors.
     *
     * <p>For nodes with multiple connections, this must account for flux from
     * all directions to determine the new state.
     *
     * @param current the current state of the node
     * @param netFluxes map from neighbor position to net flux from that neighbor
     * @param dt time step in seconds
     * @return the updated state after applying all fluxes
     */
    public abstract S updateNodeState(S current, Map<Vector3i, F> netFluxes, double dt);

    /**
     * Updates an edge's state based on flux at both ends.
     *
     * <p>Edges typically have exactly two neighbors (start and end nodes).
     * The edge state changes based on the difference between influx and outflux.
     *
     * @param current the current state of the edge
     * @param fluxFromStart flux entering from the start node
     * @param fluxToEnd flux leaving to the end node
     * @param edgeLength the physical length of this edge
     * @param dt time step in seconds
     * @return the updated state after applying fluxes
     */
    public abstract S updateEdgeState(S current, F fluxFromStart, F fluxToEnd, 
                                      double edgeLength, double dt);

    /**
     * Returns {@code true} if the component is in equilibrium (no further updates needed).
     *
     * <p>For nodes, equilibrium means net flux ≈ 0 (or all fluxes balanced).
     * For edges, equilibrium means influx ≈ outflux (steady state flow).
     *
     * <p>Note: Equilibrium does NOT mean all fluxes are zero — a pipe with
     * steady flow through it is in equilibrium even though flux is non-zero.
     *
     * @param state the current state
     * @param netFluxes map of fluxes from/to neighbors
     * @return {@code true} if no further updates are needed
     */
    public abstract boolean isInEquilibrium(S state, Map<Vector3i, F> netFluxes);

    /**
     * Calculates the priority for an update based on state and flux magnitudes.
     *
     * <p>Lower priority values = more urgent updates.
     * <ul>
     *   <li>Priority 1: Large changes (>10% difference), update immediately</li>
     *   <li>Priority 10: Medium changes (1-10%), update soon</li>
     *   <li>Priority 100: Small changes (<1%), update eventually</li>
     *   <li>Priority MAX_VALUE: Negligible changes, skip update</li>
     * </ul>
     *
     * @param state the current state
     * @param netFluxes map of fluxes from/to neighbors
     * @return priority value (lower = more urgent)
     */
    public abstract int calculateUpdatePriority(S state, Map<Vector3i, F> netFluxes);

    /**
     * Calculates how many ticks to delay before scheduling a neighbor update.
     *
     * <p>This implements adaptive update propagation: large changes propagate
     * quickly (1 tick delay), small changes propagate slowly (10+ tick delay).
     *
     * @param fluxMagnitude the magnitude of flux change that would trigger the update
     * @param referenceState the state of the component for percentage calculations
     * @return number of ticks to delay (1 = next tick, higher = slower propagation)
     */
    public int calculatePropagationDelay(double fluxMagnitude, S referenceState) {
        double percentChange = fluxMagnitude / Math.max(referenceState.getMagnitude(), 1e-6);
        
        if (percentChange > 0.1)   return 1;   // >10% change: immediate
        if (percentChange > 0.01)  return 5;   // 1-10%: fast
        if (percentChange > 0.001) return 20;  // 0.1-1%: slow
        
        return Integer.MAX_VALUE; // <0.1%: don't propagate
    }

    /**
     * Optional: Estimates time to convergence based on current flux gradients.
     *
     * <p>This can be used as an optimization to skip updates for components
     * that are converging very slowly. Default implementation returns infinity
     * (no estimate available).
     *
     * @param state the current state
     * @param netFluxes map of fluxes from/to neighbors
     * @return estimated ticks until equilibrium, or {@link Double#POSITIVE_INFINITY} if unknown
     */
    public double estimateConvergenceTime(S state, Map<Vector3i, F> netFluxes) {
        return Double.POSITIVE_INFINITY;
    }
}

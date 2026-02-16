package com.example.exampleplugin.network;

/**
 * Represents a flux (flow) between two network components.
 *
 * <p>In thermodynamics, this might represent mass flow, energy flow, and particle flow.
 * In electricity, this might be current. In fluids, volumetric flow rate.
 *
 * <p>Fluxes are directional: a positive flux means flow from source to target,
 * negative means flow in the opposite direction.
 */
public interface FluxVector {

    /**
     * Returns the scalar magnitude of this flux (always non-negative).
     * Used for priority calculations and convergence detection.
     */
    double getMagnitude();

    /**
     * Creates a deep copy of this flux.
     */
    FluxVector copy();

    /**
     * Returns a flux with opposite direction but same magnitude.
     * Used when calculating net flux from both directions.
     */
    FluxVector negate();

    /**
     * Adds another flux to this one, component-wise.
     * Used for calculating net flux into a node.
     *
     * @param other the flux to add
     * @return a new FluxVector representing the sum
     */
    FluxVector add(FluxVector other);
}

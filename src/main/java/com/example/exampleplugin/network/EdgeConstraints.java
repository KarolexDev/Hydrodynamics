package com.example.exampleplugin.network;

/**
 * Represents physical constraints imposed by an edge in the network.
 *
 * <p>Examples:
 * <ul>
 *   <li>Maximum flow rate (e.g., a valve or decompressor)</li>
 *   <li>Thermal conductivity (for heat transfer)</li>
 *   <li>Electrical resistance</li>
 *   <li>Fluid viscosity effects</li>
 * </ul>
 *
 * <p>Edges apply these constraints to the ideal flux calculated from state differences,
 * producing the actual flux that occurs in practice.
 */
public interface EdgeConstraints {

    /**
     * Applies physical constraints to an ideal flux, returning the actual
     * flux that will occur given the edge's properties.
     *
     * <p>For example, a decompressor might clamp mass flow rate to a maximum value.
     * Thermal insulation might reduce heat flux by a factor.
     *
     * @param idealFlux the flux calculated from state gradients alone
     * @return the constrained flux that actually occurs
     */
    FluxVector applyLimits(FluxVector idealFlux);

    /**
     * Returns {@code true} if this edge imposes no constraints (ideal transmission).
     * Used as an optimization hint to skip constraint application.
     */
    default boolean isIdeal() {
        return false;
    }
}

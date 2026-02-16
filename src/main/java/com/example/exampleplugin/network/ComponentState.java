package com.example.exampleplugin.network;

/**
 * Represents the physical state of a network component (Node or Edge).
 *
 * <p>Implementations must be <b>immutable</b> or provide thread-safe copy methods,
 * as states may be read from multiple threads.
 *
 * <p>Example implementations:
 * <ul>
 *   <li>Thermodynamics: pressure, temperature, mole count, mass flow, energy flow, particle flow</li>
 *   <li>Electricity: voltage, current, resistance</li>
 *   <li>Fluids: volume, flow rate, viscosity</li>
 * </ul>
 */
public interface ComponentState {

    /**
     * Creates a deep copy of this state.
     * Required for thread-safe snapshots.
     */
    ComponentState copy();

    /**
     * Returns a scalar magnitude representing the "size" of this state.
     * Used for priority calculations and convergence detection.
     *
     * <p>For thermodynamics, this might be total energy or pressure.
     * For electricity, this might be voltage or power.
     *
     * @return a non-negative scalar value
     */
    double getMagnitude();

    /**
     * Returns {@code true} if this component should trigger an update
     * when the network starts or is loaded from disk.
     *
     * <p>Typically {@code true} for sources (generators, tanks with content)
     * and {@code false} for passive components (empty pipes).
     */
    default boolean triggersUpdateUponStart() {
        return false;
    }

    /**
     * Returns {@code true} if this component should trigger an update
     * when newly placed in the world.
     *
     * <p>Usually {@code true} for all components to ensure the network
     * recalculates after topology changes.
     */
    default boolean triggersUpdateUponPlacement() {
        return true;
    }
}

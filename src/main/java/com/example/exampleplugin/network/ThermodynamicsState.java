package com.example.exampleplugin.network;

import com.example.exampleplugin.network.dynamics.ComponentState;

/**
 * Example implementation of ComponentState for thermodynamics.
 *
 * <p>Represents a gas/fluid state with 6 variables:
 * <ul>
 *   <li>3 state variables: pressure, temperature, mole count</li>
 *   <li>3 flux variables: mass flow, energy flow, particle flow</li>
 * </ul>
 *
 * <p>The flux variables enable steady-state flow: a pipe can have constant
 * pressure/temperature but non-zero mass flow through it.
 */
public class ThermodynamicsState implements ComponentState {

    // State variables
    public final double pressure;      // Pa (Pascals)
    public final double temperature;   // K (Kelvin)
    public final double moleCount;     // mol

    // Flux variables (rates)
    public final double massFlowRate;     // kg/s
    public final double energyFlowRate;   // J/s (Watts)
    public final double particleFlowRate; // mol/s

    // Physical properties
    public final double volume;  // m³ (for nodes) or length (for edges)
    public final boolean isSource; // True for tanks, generators

    public ThermodynamicsState(double pressure, double temperature, double moleCount,
                               double massFlowRate, double energyFlowRate, 
                               double particleFlowRate, double volume, boolean isSource) {
        this.pressure = pressure;
        this.temperature = temperature;
        this.moleCount = moleCount;
        this.massFlowRate = massFlowRate;
        this.energyFlowRate = energyFlowRate;
        this.particleFlowRate = particleFlowRate;
        this.volume = volume;
        this.isSource = isSource;
    }

    @Override
    public ComponentState copy() {
        return new ThermodynamicsState(
            pressure, temperature, moleCount,
            massFlowRate, energyFlowRate, particleFlowRate,
            volume, isSource
        );
    }

    @Override
    public double getMagnitude() {
        // Use total energy as magnitude (pressure * volume + thermal energy)
        double R = 8.314; // Gas constant
        return pressure * volume + moleCount * R * temperature;
    }

    @Override
    public boolean triggersUpdateUponStart() {
        // Sources (tanks with content) trigger updates on start
        return isSource && moleCount > 1e-6;
    }

    @Override
    public boolean triggersUpdateUponPlacement() {
        // All components trigger updates when placed
        return true;
    }

    /**
     * Creates an empty state (vacuum).
     */
    public static ThermodynamicsState empty(double volume) {
        return new ThermodynamicsState(
            0.0, 293.15, 0.0,  // Atmospheric temp, no pressure
            0.0, 0.0, 0.0,
            volume, false
        );
    }

    /**
     * Creates a state with atmospheric conditions.
     */
    public static ThermodynamicsState atmospheric(double volume) {
        double pressure = 101325; // 1 atm in Pa
        double temperature = 293.15; // 20°C in K
        double R = 8.314;
        double moleCount = (pressure * volume) / (R * temperature);
        
        return new ThermodynamicsState(
            pressure, temperature, moleCount,
            0.0, 0.0, 0.0,
            volume, false
        );
    }

    @Override
    public String toString() {
        return String.format("Thermo[P=%.1f kPa, T=%.1f K, n=%.3f mol, flows=(%.3f, %.3f, %.3f)]",
            pressure / 1000, temperature, moleCount,
            massFlowRate, energyFlowRate, particleFlowRate);
    }
}

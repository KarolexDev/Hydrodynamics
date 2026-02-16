package com.example.exampleplugin.network;

import com.example.exampleplugin.network.dynamics.EdgeConstraints;
import com.example.exampleplugin.network.dynamics.FluxVector;

/**
 * Example EdgeConstraints implementation for simple flow rate limiting.
 *
 * <p>Useful for:
 * <ul>
 *   <li>Decompressors (limit mass flow rate)</li>
 *   <li>Valves (controllable flow restriction)</li>
 *   <li>Thermal insulators (reduced heat transfer)</li>
 * </ul>
 */
public class SimpleEdgeConstraints implements EdgeConstraints {

    private final double maxMassFlowRate;
    private final double maxEnergyFlowRate;
    private final double maxParticleFlowRate;

    /**
     * Creates constraints with unlimited flow (ideal edge).
     */
    public SimpleEdgeConstraints() {
        this(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * Creates constraints with specified limits.
     *
     * @param maxMassFlowRate maximum mass flow in kg/s
     * @param maxEnergyFlowRate maximum energy flow in W
     * @param maxParticleFlowRate maximum particle flow in mol/s
     */
    public SimpleEdgeConstraints(double maxMassFlowRate, 
                                 double maxEnergyFlowRate,
                                 double maxParticleFlowRate) {
        this.maxMassFlowRate = maxMassFlowRate;
        this.maxEnergyFlowRate = maxEnergyFlowRate;
        this.maxParticleFlowRate = maxParticleFlowRate;
    }

    @Override
    public FluxVector applyLimits(FluxVector idealFlux) {
        if (!(idealFlux instanceof ThermodynamicsFlux)) {
            return idealFlux; // Can't apply constraints to unknown flux type
        }

        ThermodynamicsFlux flux = (ThermodynamicsFlux) idealFlux;

        // Clamp each component to its maximum
        double limitedMass = clamp(flux.massFlow, maxMassFlowRate);
        double limitedEnergy = clamp(flux.energyFlow, maxEnergyFlowRate);
        double limitedParticles = clamp(flux.particleFlow, maxParticleFlowRate);

        return new ThermodynamicsFlux(limitedMass, limitedEnergy, limitedParticles);
    }

    @Override
    public boolean isIdeal() {
        return Double.isInfinite(maxMassFlowRate)
            && Double.isInfinite(maxEnergyFlowRate)
            && Double.isInfinite(maxParticleFlowRate);
    }

    /**
     * Clamps a value to [-limit, +limit], preserving sign.
     */
    private double clamp(double value, double limit) {
        if (value > limit) return limit;
        if (value < -limit) return -limit;
        return value;
    }

    /**
     * Creates a decompressor constraint (limits mass flow only).
     */
    public static SimpleEdgeConstraints decompressor(double maxMassFlow) {
        return new SimpleEdgeConstraints(
            maxMassFlow,
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY
        );
    }

    /**
     * Creates a thermal insulator constraint (limits energy flow only).
     */
    public static SimpleEdgeConstraints insulator(double thermalConductivity) {
        return new SimpleEdgeConstraints(
            Double.POSITIVE_INFINITY,
            thermalConductivity,
            Double.POSITIVE_INFINITY
        );
    }

    @Override
    public String toString() {
        return String.format("Constraints[mass=%.3f, energy=%.3f, particles=%.3f]",
            maxMassFlowRate, maxEnergyFlowRate, maxParticleFlowRate);
    }
}

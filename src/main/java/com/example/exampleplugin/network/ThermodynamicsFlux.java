package com.example.exampleplugin.network;

import com.example.exampleplugin.network.dynamics.FluxVector;

/**
 * Example implementation of FluxVector for thermodynamics.
 *
 * <p>Represents the flow of mass, energy, and particles between components.
 * Positive values indicate flow in the forward direction (source → target).
 */
public class ThermodynamicsFlux implements FluxVector {

    public final double massFlow;     // kg/s
    public final double energyFlow;   // J/s (Watts)
    public final double particleFlow; // mol/s

    public ThermodynamicsFlux(double massFlow, double energyFlow, double particleFlow) {
        this.massFlow = massFlow;
        this.energyFlow = energyFlow;
        this.particleFlow = particleFlow;
    }

    @Override
    public double getMagnitude() {
        // Use Euclidean norm of flux components
        return Math.sqrt(
            massFlow * massFlow + 
            energyFlow * energyFlow * 1e-6 +  // Scale energy for comparable magnitude
            particleFlow * particleFlow
        );
    }

    @Override
    public FluxVector copy() {
        return new ThermodynamicsFlux(massFlow, energyFlow, particleFlow);
    }

    @Override
    public FluxVector negate() {
        return new ThermodynamicsFlux(-massFlow, -energyFlow, -particleFlow);
    }

    @Override
    public FluxVector add(FluxVector other) {
        if (!(other instanceof ThermodynamicsFlux)) {
            throw new IllegalArgumentException("Cannot add incompatible flux types");
        }
        ThermodynamicsFlux o = (ThermodynamicsFlux) other;
        return new ThermodynamicsFlux(
            this.massFlow + o.massFlow,
            this.energyFlow + o.energyFlow,
            this.particleFlow + o.particleFlow
        );
    }

    /**
     * Creates a zero flux (no flow).
     */
    public static ThermodynamicsFlux zero() {
        return new ThermodynamicsFlux(0.0, 0.0, 0.0);
    }

    @Override
    public String toString() {
        return String.format("Flux[mass=%.4f kg/s, energy=%.2f W, particles=%.4f mol/s]",
            massFlow, energyFlow, particleFlow);
    }
}

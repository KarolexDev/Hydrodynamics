package com.example.exampleplugin.physics;

public class ThermodynamicsUtil {
    public static final float GAS_CONSTANT = 8.31446261815324f;
    private ThermodynamicsUtil() {
    }

    public <T extends EquationOfState> void equilibrate(float dt, T system1, T system2) {

        float thermal_conductivity = 1f;
        float entropy_per_particle = 1f;
        float viscosity = 1f;   // More like travel velocity across pipe

        float dN = viscosity * (system1.getP() - system2.getP());
        float dS = thermal_conductivity * (1 / system1.getT() - 1 / system2.getT()) + dN * entropy_per_particle;

        system1.addMolarity(dN * dt);
        system1.addEntropy(dS * dt);

        system2.addMolarity(-dN * dt);
        system2.addEntropy(-dS * dt);
    }
}

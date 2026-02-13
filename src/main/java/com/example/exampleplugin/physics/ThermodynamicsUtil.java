package com.example.exampleplugin.physics;

import com.example.exampleplugin.component.ThermodynamicEnsembleComponent;
import com.example.exampleplugin.physics.systems.IdealGas;

public class ThermodynamicsUtil {
    public static final float GAS_CONSTANT = 8.31446261815324f;
    public static final float DEFAULT_THERMAL_CONDUCTIVITY = 10f;
    public static final float DE_BROGLIE_PREFACTOR = 3.24e-10f;
    private ThermodynamicsUtil() {
        // Utility class
    }

    public static void equilibrate(float dt, ThermodynamicEnsembleComponent ensemble1, ThermodynamicEnsembleComponent ensemble2) {

        IdealGas system1 = ensemble1.data;
        IdealGas system2 = ensemble1.data;

        float thermal_conductivity = DEFAULT_THERMAL_CONDUCTIVITY;
        float entropy_per_particle = 1f;
        float viscosity = 1f;   // More like travel velocity across pipe

        float dN = viscosity * (system1.getPressure() - system2.getPressure());
        float dS = thermal_conductivity * (1 / system1.getTemperature() - 1 / system2.getTemperature()) + dN * entropy_per_particle;

        system1.addMolarity(dN * dt);
        system1.addEntropy(dS * dt);

        system2.addMolarity(-dN * dt);
        system2.addEntropy(-dS * dt);
    }

    public static void equilibrate_with_environment(float dt, ThermodynamicEnsembleComponent ensemble, float surroundingTemperature) {
        IdealGas system = ensemble.data;

        float thermal_conductivity = DEFAULT_THERMAL_CONDUCTIVITY;
        float entropy_per_particle = 1f;
        float viscosity = 1f;   // More like travel velocity across pipe

        float dS = thermal_conductivity * (1 / system.getTemperature() - 1 / surroundingTemperature);

        System.out.println("DIFFERENTIAL ENTROPY:\t" + dS);

        system.addEntropy(dS * dt);
    }
}

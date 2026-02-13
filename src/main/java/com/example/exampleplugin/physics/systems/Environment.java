package com.example.exampleplugin.physics.systems;

import com.example.exampleplugin.physics.EntropicSystem;

public final class Environment extends EntropicSystem {

    public static final float ENVIRONMENT_TEMPERATURE = 300f;
    public static final float ENVIRONMENT_PRESSURE = 1f;

    public Environment() {
        super(0f, 0f, 0f);
    }

    @Override
    public float getEntropy() {
        return 0f;
    }

    @Override
    public float getTemperature() {
        return ENVIRONMENT_TEMPERATURE;
    }

    @Override
    public float getPressure() {
        return ENVIRONMENT_PRESSURE;
    }

    @Override
    public float getChemicalPotential() {
        return 0f;
    }
}

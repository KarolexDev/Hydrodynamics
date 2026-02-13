package com.example.exampleplugin.physics.systems;

import com.example.exampleplugin.physics.EntropicSystem;

public class IdealGas extends EntropicSystem {

    private static final float TWO_THIRDS = 2f/3f;

    public IdealGas(float energy, float volume, float particleCount) {
        super(energy, volume, particleCount);
    }

    @Override
    public float getEntropy() {
        return this.getParticleCount() * GAS_CONSTANT * ((float) Math.log(this.getVolume() / this.getParticleCount()) + 1.5f * (float) Math.log(this.getTemperature()));
    }

    @Override
    public float getTemperature() {
        return TWO_THIRDS * this.getEnergy() / (this.getParticleCount() * GAS_CONSTANT);
    }

    @Override
    public float getPressure() {
        return TWO_THIRDS * this.getEnergy() / this.getVolume();
    }

    @Override
    public float getChemicalPotential() {
        float lambda = PLANCKS_CONSTANT / (float) Math.sqrt(2 * Math.PI * MEAN_ATMOSPHERIC_PARTICLE_MASS * BOLTZMANN_CONSTANT * this.getTemperature());
        return (float) (GAS_CONSTANT * Math.log(this.getParticleCount() / this.getVolume() * Math.pow(lambda, 3)) * this.getTemperature());
    }
}

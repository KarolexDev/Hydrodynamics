package com.example.exampleplugin.physics;

public abstract class EntropicSystem {

    public static final float GAS_CONSTANT = 8.31446261815324f;
    public static final float BOLTZMANN_CONSTANT = 1.380649e-23f;
    public static final float AVOGADRO_CONSTANT = 6.02214076e23f;
    public static final float PLANCKS_CONSTANT = 6.62607015e-34f;
    public static final float MEAN_ATMOSPHERIC_PARTICLE_MASS = 4.81069e-26f;

    private float energy;
    private float volume;
    private float particleCount;

    public EntropicSystem(float energy, float volume, float particleCount) {
        this.energy = energy;
        this.volume = volume;
        this.particleCount = particleCount;
    }

    public float getEnergy() { return this.energy; }
    public float getVolume() { return this.volume; }
    public float getParticleCount() { return this.particleCount; }

    public EntropicSystem addEnergy(float energy) {
        this.energy += energy;
        return this;
    }

    public EntropicSystem addVolume(float volume) {
        this.volume += volume;
        return this;
    }

    public EntropicSystem addParticles(float particleCount) {
        this.particleCount += particleCount;
        return this;
    }

    public abstract float getEntropy();
    public abstract float getTemperature();
    public abstract float getPressure();
    public abstract float getChemicalPotential();

    public <T extends EntropicSystem> EntropicSystem equilibrateWith(T otherSystem, float dt) {

        float alpha = 0.01f;

        float L_NN = 1f;
        float L_UU = 1000f;
        float L_UN =  alpha * (float) Math.sqrt(L_UU * L_NN);
        float L_NU = L_UN;
        // float L_UN = 2.5f * GAS_CONSTANT * 0.5f * (this.getTemperature() + otherSystem.getTemperature()) * .1f;

        // float dMuOverT = this.getChemicalPotential()/this.getTemperature() - otherSystem.getChemicalPotential()/otherSystem.getTemperature();
        float dMuOverT = this.getPressure() - otherSystem.getPressure();
        float dInvT = 1/this.getTemperature() - 1/otherSystem.getTemperature();

        float Tstar = 0.5f * (this.getTemperature() + otherSystem.getTemperature());
        float h = 2.5f * GAS_CONSTANT * Tstar;

        if (dt > 0.01f) { dt = 0.01f; }

        float dN = - L_NN * dMuOverT * dt + L_NU * dInvT * dt;
        float dU = - L_UU * dInvT * dt + L_UN * dMuOverT * dt;

        otherSystem.addEnergy(-dU).addParticles(-dN);
        return this.addEnergy(dU).addParticles(dN);
    }
}

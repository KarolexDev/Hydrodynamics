package com.example.exampleplugin.physics;

/**
 * Class for implementing thermodynamics based on the extensive variables entropy, volume and particle count.
 * Variables such as temperature and pressure are thus derived quantities that are calculated via the equation of state (EOS) of the respective system.
 */
public abstract class EquationOfState {
    public float entropy;
    public float volume;
    public float molarity;

    public float entropy_flow;
    public float volume_flow; // In almost all systems zero
    public float particle_flow;

    private boolean triggerUpdates = false;

    public EquationOfState(float entropy, float volume, float molarity) {
        this.entropy = entropy;
        this.volume = volume;
        this.molarity = molarity;

        this.entropy_flow = 0f;
        this.volume_flow = 0f;
        this.particle_flow = 0f;
    }

    protected abstract float getT();
    protected abstract float getP();
    protected abstract float getU();

    public float getEntropy() { return this.entropy; }
    public float getVolume() { return this.volume; }
    public float getMolarity() { return this.molarity; }

    public void addEntropy(float entropy) { this.entropy += entropy; }
    public void addVolume(float volume) { this.volume += volume; }
    public void addMolarity(float molarity) { this.molarity += molarity; }
}

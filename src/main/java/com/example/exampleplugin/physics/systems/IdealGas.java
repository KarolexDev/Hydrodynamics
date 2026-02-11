package com.example.exampleplugin.physics.systems;

import com.example.exampleplugin.physics.EquationOfState;
import com.hypixel.hytale.math.util.MathUtil;

import static com.example.exampleplugin.physics.ThermodynamicsUtil.GAS_CONSTANT;

public class IdealGas extends EquationOfState {
    private final float U0 = 1f;
    public IdealGas(float entropy, float volume, float molarity) {
        super(entropy, volume, molarity);
    }

    @Override
    protected float getT() {
        return 2 / (3 * this.molarity * GAS_CONSTANT) * this.getU();
    }

    @Override
    protected float getP() {
        return this.molarity * this.getT() / this.volume;
    }

    @Override
    protected float getU() {
        return (float) (this.U0 * this.molarity * Math.exp(2 * this.entropy / (3 * this.molarity * GAS_CONSTANT)) * Math.pow(this.volume, (double) -2 /3));
    }
}

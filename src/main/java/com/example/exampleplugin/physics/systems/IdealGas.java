package com.example.exampleplugin.physics.systems;

import com.example.exampleplugin.physics.EquationOfState;

import static com.example.exampleplugin.physics.ThermodynamicsUtil.DE_BROGLIE_PREFACTOR;
import static com.example.exampleplugin.physics.ThermodynamicsUtil.GAS_CONSTANT;

public class IdealGas extends EquationOfState {
    private final float U0 = 1f;
    public IdealGas(float entropy, float volume, float molarity) {
        super(entropy, volume, molarity);
    }

    @Override
    public float getTemperature() {
        float result = 2 / (3 * this.molarity * GAS_CONSTANT) * this.getInnerEnergy();
        System.out.println("CALCULATED TEMPERATURE:\t" + result);
        return result;
    }

    @Override
    public float getPressure() {
        return this.molarity * this.getTemperature() / this.volume;
    }

    @Override
    protected float getInnerEnergy() {
        float result = (float) (this.U0 * this.molarity * Math.exp(2 * this.entropy / (3 * this.molarity * GAS_CONSTANT)) * Math.pow(this.volume, (double) -2 /3));
        System.out.println("CALCULATED INNER ENERGY:\t" + result);
        return result;
    }

    public static float toEntropy(float temperature, float volume, float molarity) {
        float deBroglie = DE_BROGLIE_PREFACTOR / ((float) Math.sqrt(temperature));
        float result = (float) (molarity * GAS_CONSTANT * ((float) Math.log(volume / (molarity * Math.pow(deBroglie, 3))))); // + 2.5 * GAS_CONSTANT * molarity);
        System.out.println("CALCULATED ENTROPY:\t" + result);
        return result;
    }
}

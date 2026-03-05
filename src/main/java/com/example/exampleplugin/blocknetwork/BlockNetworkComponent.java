package com.example.exampleplugin.blocknetwork;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

public interface BlockNetworkComponent<C extends BlockNetworkComponent<C>> {

    C add(C flux);
    C del(C flux);

    C mergeComponents(C flux);
    C calculateFlux(float dt, C from, C to);
    C[] partition(int left_size, int right_size);
    C zero();

    // Wird auf dem Netzwerk-Thread aufgerufen nach jedem Update
    // Gibt true zurück wenn eine World-Aktion nötig ist
    boolean requiresWorldUpdate();

    // Wird auf dem World-Thread ausgeführt wenn requiresWorldUpdate() true war
    void onWorldUpdate(Vector3i pos, World world);

    // Erstellt eine Kopie für den Vorher-Nachher-Vergleich
    C copy();

    // Berechnet wie stark sich der Wert verändert hat (0.0 = keine Änderung, 1.0 = maximale Änderung)
    float changeRate(C previous);

    // Gibt die Wartezeit in Sekunden zurück basierend auf der Änderungsrate
// z.B.: changeRate < 0.001 → 1.0f Sekunden warten, changeRate > 0.1 → 0.0f sofort
    float computeDelay(float changeRate);

    boolean shouldMerge(C other);

    default boolean isPipe() { return false; };
}

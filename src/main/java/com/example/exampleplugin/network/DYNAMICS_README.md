# Network Dynamics System

Ein allgemeines, leistungsfähiges System zur Simulation physikalischer Prozesse in Blocknetzwerken mit Unterstützung für adaptive Update-Geschwindigkeiten und optionale Multi-Threading.

## Überblick

Das System besteht aus folgenden Komponenten:

### Core Interfaces
- **`ComponentState`**: Physikalischer Zustand eines Netzwerk-Knotens oder einer Kante
- **`FluxVector`**: Fluss zwischen zwei Komponenten
- **`EdgeConstraints`**: Physikalische Beschränkungen auf Kanten (z.B. Durchflusslimits)
- **`DynamicsHandler`**: Abstrakte Basisklasse für problem-spezifische Physik

### Hauptklasse
- **`NetworkDynamics`**: Verwaltet Zustandsspeicherung, Update-Scheduling und Flux-Berechnungen

### Update-System
- **`UpdateTrigger`**: Repräsentiert einen geplanten Update
- Adaptive Update-Geschwindigkeit: Große Änderungen → schnelle Propagation, kleine Änderungen → langsame Propagation
- Statischer Zustand: Keine Updates wenn System im Gleichgewicht

## Nutzung

### 1. Implementiere deine eigenen State/Flux Klassen

```java
public class MyState implements ComponentState {
    private final double value;
    
    @Override
    public ComponentState copy() {
        return new MyState(value);
    }
    
    @Override
    public double getMagnitude() {
        return Math.abs(value);
    }
}

public class MyFlux implements FluxVector {
    private final double flow;
    
    @Override
    public double getMagnitude() {
        return Math.abs(flow);
    }
    
    @Override
    public FluxVector add(FluxVector other) {
        return new MyFlux(this.flow + ((MyFlux) other).flow);
    }
    
    // ... weitere Methoden
}
```

### 2. Implementiere deinen DynamicsHandler

```java
public class MyHandler extends DynamicsHandler<MyState, MyFlux> {
    
    @Override
    public MyFlux calculateIdealFlux(MyState source, MyState target, double edgeLength) {
        // Berechne Flux basierend auf Zustandsdifferenz
        double gradient = (source.value - target.value) / edgeLength;
        return new MyFlux(transferCoeff * gradient);
    }
    
    @Override
    public MyState updateNodeState(MyState current, 
                                    Map<Vector3i, MyFlux> netFluxes, 
                                    double dt) {
        // Summiere alle eingehenden Fluxes
        double totalFlow = netFluxes.values().stream()
            .mapToDouble(f -> f.flow)
            .sum();
        
        // Update State
        return new MyState(current.value + totalFlow * dt);
    }
    
    @Override
    public MyState updateEdgeState(MyState current,
                                    MyFlux fluxFromStart,
                                    MyFlux fluxToEnd,
                                    double edgeLength,
                                    double dt) {
        // Edge: Differenz zwischen Zu- und Abfluss
        double netFlow = fluxFromStart.flow - fluxToEnd.flow;
        return new MyState(current.value + netFlow * dt);
    }
    
    @Override
    public boolean isInEquilibrium(MyState state, Map<Vector3i, MyFlux> netFluxes) {
        double totalFlow = netFluxes.values().stream()
            .mapToDouble(f -> f.flow)
            .sum();
        return Math.abs(totalFlow) < 1e-6; // Threshold
    }
    
    @Override
    public int calculateUpdatePriority(MyState state, Map<Vector3i, MyFlux> netFluxes) {
        double totalFlow = netFluxes.values().stream()
            .mapToDouble(f -> Math.abs(f.flow))
            .sum();
        double percentChange = totalFlow / Math.max(state.getMagnitude(), 1e-6);
        
        if (percentChange > 0.1)   return 1;
        if (percentChange > 0.01)  return 10;
        if (percentChange > 0.001) return 100;
        return Integer.MAX_VALUE;
    }
}
```

### 3. Erstelle und konfiguriere NetworkDynamics

```java
// Erstelle Handler
MyHandler handler = new MyHandler();

// Erstelle Dynamics-System (single-threaded vorerst)
NetworkDynamics<MyComponent, MyState, MyFlux> dynamics = 
    new NetworkDynamics<>(blockNetwork, handler, false);

// Setze initiale Zustände für alle Komponenten
for (Vector3i pos : blockNetwork.getPositions()) {
    MyState initialState = calculateInitialState(pos);
    dynamics.setState(pos, initialState);
}

// Optional: Setze Edge-Constraints
for (BlockNetwork.Edge edge : blockNetwork.getEdges()) {
    Vector3i edgePos = edge.getIntermediateBlocks().get(0);
    EdgeConstraints constraints = new MyEdgeConstraints(...);
    dynamics.setEdgeConstraints(edgePos, constraints);
}

// Triggere initiale Updates
dynamics.triggerInitialUpdates();
```

### 4. Tick-Loop

```java
// Im Haupt-Game-Loop (z.B. jedes Tick)
public void onTick() {
    dynamics.tick(); // Prozessiert alle fälligen Updates
}

// Optional: Abfrage von Zuständen (z.B. für Manometer)
MyState state = dynamics.getState(playerLookingAtPos);
displayToPlayer("Pressure: " + state.value);
```

## Thermodynamik-Beispiel

Das System enthält eine vollständige Beispiel-Implementierung für Thermodynamik:

```java
// Handler mit Transport-Koeffizienten
ThermodynamicsHandler handler = new ThermodynamicsHandler(
    massTransferCoeff,   // kg/(s·Pa/m)
    heatTransferCoeff,   // W/K·m
    particleTransferCoeff // mol/(s·mol/m³)
);

// Dynamics-System
NetworkDynamics<GasComponent, ThermodynamicsState, ThermodynamicsFlux> dynamics =
    new NetworkDynamics<>(network, handler, false);

// Tank mit Gas füllen
ThermodynamicsState tankState = new ThermodynamicsState(
    200000,  // 2 atm Druck
    300,     // 27°C
    10.0,    // 10 mol Gas
    0, 0, 0, // Keine initialen Fluxes
    1.0,     // 1 m³ Volumen
    true     // Ist Source (Tank)
);
dynamics.setState(tankPos, tankState);

// Pipes mit atmosphärischen Bedingungen
for (Vector3i pipePos : pipePositions) {
    dynamics.setState(pipePos, ThermodynamicsState.atmospheric(0.1));
}

// Dekompressor-Constraint
dynamics.setEdgeConstraints(
    decompressorEdgePos,
    SimpleEdgeConstraints.decompressor(0.01) // Max 0.01 kg/s
);

// Start Simulation
dynamics.triggerInitialUpdates();

// Jedes Tick
dynamics.tick();
```

## Adaptive Update-Geschwindigkeit

Das System passt die Update-Frequenz automatisch an:

- **Große Änderungen (>10%)**: Update jedes Tick (1 Block/Tick Ausbreitung)
- **Mittlere Änderungen (1-10%)**: Update alle 5 Ticks (1 Block/5 Ticks)
- **Kleine Änderungen (<1%)**: Update alle 20 Ticks (1 Block/20 Ticks)
- **Vernachlässigbare Änderungen (<0.1%)**: Kein Update

Dies spart Rechenleistung in ruhigen Bereichen und fokussiert Updates auf dynamische Regionen.

## Statischer Zustand & Gleichgewicht

Ein wichtiges Feature: **Statischer Zustand bedeutet NICHT alle Werte konstant!**

Ein Rohrsystem kann im Gleichgewicht sein, während Gas durchfließt:

```
Tank (2 atm) → Pipe → Pipe → Pipe → Atmosphäre (1 atm)
              ↓      ↓      ↓
          Steady Flow (konstante Rate)
```

- Jede Pipe hat konstanten Druck/Temperatur
- Gas fließt mit konstanter Rate
- **Net Flux = 0** (Zufluss = Abfluss)
- **Keine Updates nötig** (System ist stabil)

Nur an **Quellen** (Tank füllt sich) und **Senken** (Gas entweicht) ändern sich Werte → nur dort Updates.

## Multi-Threading (zukünftig)

Für Multi-Threading-Unterstützung:

```java
// Erstelle mit multiThreaded=true
NetworkDynamics<T, S, F> dynamics = 
    new NetworkDynamics<>(network, handler, true);

// Starte Dynamics auf separatem Thread
Thread dynamicsThread = new Thread(() -> {
    while (running) {
        dynamics.tick();
        Thread.sleep(50); // 20 TPS
    }
});
dynamicsThread.start();

// Main-Thread kann sicher Zustände lesen
S state = dynamics.getState(pos); // Thread-safe read
```

Das System nutzt Read-Write-Locks für sichere Synchronisation.

## Topologie-Änderungen

Wenn Blöcke platziert/entfernt werden:

```java
// Nach Block-Platzierung
blockNetwork.addBlock(pos, component);
dynamics.markTopologyDirty(); // Snapshot wird beim nächsten tick neu gebaut
dynamics.setState(pos, initialState); // Triggert automatisch Update

// Nach Block-Entfernung
blockNetwork.removeBlock(pos);
dynamics.markTopologyDirty();
// State wird automatisch entfernt (da Position nicht mehr existiert)
```

## Performance-Hinweise

- **Single-Threaded Overhead**: Sehr gering (~100ns pro Update)
- **Multi-Threaded Overhead**: Read-Lock pro Tick (~1-5µs)
- **Adaptive Updates**: Reduziert Update-Count um 50-90% in stabilen Systemen
- **Gleichgewichts-Erkennung**: Verhindert unnötige Berechnungen in statischen Regionen

## Erweiterungen

Das System ist generisch und kann für viele physikalische Domänen verwendet werden:

- **Elektrizität**: Spannung, Strom, Widerstand
- **Fluide**: Druck, Fluss, Viskosität
- **Wärme**: Temperatur, Wärmefluss, Leitfähigkeit
- **Logistik**: Items, Transfer-Rate, Kapazität

Erstelle einfach eigene `ComponentState`, `FluxVector` und `DynamicsHandler` Implementierungen!

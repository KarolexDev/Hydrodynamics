package com.karolex.hydrodynamics.util;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class UniqueSchedule<T> {

    private final TreeMap<Instant, T> sorted = new TreeMap<>();
    private final HashMap<T, Instant> timestamps = new HashMap<>();

    public void insert(T obj, Instant timestamp) {
        Instant existing = timestamps.get(obj);
        if (existing != null) sorted.remove(existing);

        timestamps.put(obj, timestamp);
        sorted.put(timestamp, obj);
    }

    public T peekEarliest() {
        Map.Entry<Instant, T> entry = sorted.firstEntry();
        return entry != null ? entry.getValue() : null;
    }

    public Instant peekEarliestTimestamp() {
        Map.Entry<Instant, T> entry = sorted.firstEntry();
        return entry != null ? entry.getKey() : Instant.MAX;
    }

    public T pollEarliest() {
        Map.Entry<Instant, T> entry = sorted.pollFirstEntry();
        if (entry == null) return null;
        timestamps.remove(entry.getValue());
        return entry.getValue();
    }

    public boolean isEmpty() {
        return sorted.isEmpty();
    }

    public void clear() {
        sorted.clear();
        timestamps.clear();
    }
}

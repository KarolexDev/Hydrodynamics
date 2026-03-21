package com.karolex.hydrodynamics.util;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class UniqueSchedule<T, S extends Comparable<S>> {

    private final TreeMap<S, T> sorted = new TreeMap<>();
    private final HashMap<T, S> timestamps = new HashMap<>();

    public void insert(T obj, S timestamp) {
        S existing = timestamps.get(obj);
        if (existing != null) sorted.remove(existing);

        timestamps.put(obj, timestamp);
        sorted.put(timestamp, obj);
    }

    public T peekEarliest() {
        Map.Entry<S, T> entry = sorted.firstEntry();
        return entry != null ? entry.getValue() : null;
    }

    public S peekEarliestTimestamp() {
        Map.Entry<S, T> entry = sorted.firstEntry();
        return entry != null ? entry.getKey() : null;
    }

    public T pollEarliest() {
        Map.Entry<S, T> entry = sorted.pollFirstEntry();
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
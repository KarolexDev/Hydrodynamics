package com.example.exampleplugin.network;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nullable;

/**
 * Represents a scheduled update for a network component.
 *
 * <p>Update triggers propagate through the network when state changes occur.
 * Each trigger specifies when and where an update should happen, allowing
 * for adaptive update rates based on the magnitude of change.
 */
public class UpdateTrigger implements Comparable<UpdateTrigger> {

    /** Position of the component to update (Node or Edge intermediate block). */
    public final Vector3i position;

    /** Position that triggered this update (for tracking propagation direction). */
    public final @Nullable Vector3i source;

    /** The tick number when this update should be executed. */
    public final int scheduledTick;

    /** Priority for tie-breaking when multiple updates occur at same tick. */
    public final int priority;

    public UpdateTrigger(Vector3i position, @Nullable Vector3i source, 
                         int scheduledTick, int priority) {
        this.position = position;
        this.source = source;
        this.scheduledTick = scheduledTick;
        this.priority = priority;
    }

    /**
     * Orders triggers by scheduled tick (earlier first), then by priority (higher first).
     */
    @Override
    public int compareTo(UpdateTrigger other) {
        int tickCompare = Integer.compare(this.scheduledTick, other.scheduledTick);
        if (tickCompare != 0) return tickCompare;
        
        // Higher priority = lower priority value (1 is more urgent than 10)
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public String toString() {
        return String.format("UpdateTrigger[pos=%s, tick=%d, prio=%d, from=%s]",
                position, scheduledTick, priority, source);
    }
}

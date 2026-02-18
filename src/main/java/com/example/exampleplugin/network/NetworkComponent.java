package com.example.exampleplugin.network;

public interface NetworkComponent {

    /**
     * Creates a component representing a segment of {@code length} blocks
     * (used when an edge or node needs a default/scaled component value).
     */
    <T extends NetworkComponent> T fromLength(int length);

    /**
     * Returns a new component that is the combination of this and
     * {@code otherComponent} (used when merging edges/nodes).
     */
    <T extends NetworkComponent> T add(NetworkComponent otherComponent);

    /**
     * Splits this component into two parts proportional to {@code a} and
     * {@code b} (used when splitting an edge at an intermediate block).
     *
     * @param a size of the first segment
     * @param b size of the second segment
     * @return a Pair where {@code left} belongs to the first segment and
     *         {@code right} belongs to the second segment
     */
    <T extends NetworkComponent> NetworkUtil.Pair<T, T> partition(int a, int b);

    /**
     * Returns a new component that represents this component after removing
     * {@code otherComponent} from it (used when detaching a sub-segment,
     * e.g. when a block is removed and the remainder must be recalculated).
     */
    <T extends NetworkComponent> T del(NetworkComponent otherComponent);
}

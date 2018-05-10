package com.danbunnell.smartlightremote.common;

/**
 * A signal filtering strategy
 */
public interface SignalFilter {
    /**
     * Filters a signal
     * @param vector the vector to filter
     * @return       the filtered vector
     */
    float filter(float vector);
}

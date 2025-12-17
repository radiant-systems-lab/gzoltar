/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 *
 * GZoltar is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * GZoltar is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with GZoltar. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package com.gzoltar.asm.runtime;

/**
 * Global probe array for mPRS edge coverage.
 * This class is accessed by instrumented code.
 */
public class GlobalProbes {

    /** Probe array - each element corresponds to an edge */
    public static boolean[] probes;

    /** Total number of probes (edges) */
    private static int probeCount = 0;

    /**
     * Initialize the probe array with the given size.
     * Must be called before running any instrumented code.
     */
    public static void init(int size) {
        probeCount = size;
        probes = new boolean[size];
    }

    /**
     * Reset all probes to false.
     * Call this before each test execution.
     */
    public static void reset() {
        if (probes != null) {
            for (int i = 0; i < probes.length; i++) {
                probes[i] = false;
            }
        }
    }

    /**
     * Get the probe array.
     */
    public static boolean[] getProbes() {
        return probes;
    }

    /**
     * Get the number of probes.
     */
    public static int getProbeCount() {
        return probeCount;
    }

    /**
     * Get a copy of the current probe state.
     */
    public static boolean[] snapshot() {
        if (probes == null) {
            return new boolean[0];
        }
        boolean[] copy = new boolean[probes.length];
        System.arraycopy(probes, 0, copy, 0, probes.length);
        return copy;
    }

    /**
     * Count how many probes are set to true.
     */
    public static int countCovered() {
        if (probes == null) {
            return 0;
        }
        int count = 0;
        for (boolean probe : probes) {
            if (probe) count++;
        }
        return count;
    }
}

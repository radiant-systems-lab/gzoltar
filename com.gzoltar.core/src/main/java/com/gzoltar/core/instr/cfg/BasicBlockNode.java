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
package com.gzoltar.core.instr.cfg;

import java.util.Objects;

/**
 * Represents a node in the Control Flow Graph, corresponding to a Basic Block.
 */
public class BasicBlockNode {
    /** Sequential identifier of the block within a method's CFG (0-based). */
    public final int id;

    /** The bytecode offset of the first instruction in the block. */
    public final int offset;

    /** A descriptive name for debugging purposes. */
    public final String name;

    public BasicBlockNode(int id, int offset, String name) {
        this.id = id;
        this.offset = offset;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BasicBlockNode that = (BasicBlockNode) o;
        return offset == that.offset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset);
    }
}

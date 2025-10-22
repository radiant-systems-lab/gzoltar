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
package com.gzoltar.core.instr.granularity;

public interface IGranularity {

  /**
   *
   * @param index
   * @param instrumentationSize
   * @return
   */
  public boolean instrumentAtIndex(final int index, final int instrumentationSize);

  /**
   *
   * @return
   */
  public boolean stopInstrumenting();

  /**
   * Gets a unique suffix to append to the node name for disambiguation.
   * This is useful when multiple instrumentation points exist on the same line.
   *
   * @return A suffix string (e.g., "#PRS5"), or empty string if no suffix is needed
   */
  public String getNodeSuffix();

}

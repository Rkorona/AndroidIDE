/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.google.googlejavaformat.java.guava;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Iterator;
import java.util.stream.Collector;

/**
 * @author Akash Yadav
 */
public class ImmutableListUtils {

  public static <E> Collector<E, ?, ImmutableList<E>> toImmutableList() {
    return ImmutableList.toImmutableList();
  }

  public static <E> Collector<E, ?, ImmutableSet<E>> toImmutableSet() {
    return ImmutableSet.toImmutableSet();
  }

  /**
   * Returns an immutable list containing the given elements, in order.
   *
   * @throws NullPointerException if {@code elements} contains a null element
   */
  public static <E> ImmutableList<E> copyOf(Iterator<? extends E> elements) {
    return ImmutableList.copyOf(elements);
  }
}

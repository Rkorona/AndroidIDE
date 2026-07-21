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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.stream.BaseStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author Akash Yadav
 */
public class Streams {

  public static <T extends Object> Stream<T> concat(Stream<? extends T>... streams) {
    boolean isParallel = false;
    int characteristics = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.NONNULL;
    long estimatedSize = 0L;
    ImmutableList.Builder<Spliterator<? extends T>> splitrsBuilder = ImmutableList.builder();
    for (Stream<? extends T> stream : streams) {
      isParallel |= stream.isParallel();
      Spliterator<? extends T> splitr = stream.spliterator();
      splitrsBuilder.add(splitr);
      characteristics &= splitr.characteristics();
      estimatedSize = LongMath.saturatedAdd(estimatedSize, splitr.estimateSize());
    }
    return StreamSupport.stream(
            CollectSpliteratorUtils.flatMap(
                splitrsBuilder.build().spliterator(),
                splitr -> (Spliterator<T>) splitr,
                characteristics,
                estimatedSize),
            isParallel)
        .onClose(() -> closeAll(streams));
  }

  private static void closeAll(BaseStream<?, ?>[] toClose) {
    RuntimeException exception = null;
    for (BaseStream<?, ?> stream : toClose) {
      try {
        stream.close();
      } catch (RuntimeException e) {
        if (exception == null) {
          exception = e;
        } else {
          exception.addSuppressed(e);
        }
      }
    }
    if (exception != null) {
      throw exception;
    }
  }

  public static <T extends Object, R extends Object> Stream<R> mapWithIndex(
      Stream<T> stream, FunctionWithIndex<? super T, ? extends R> function) {
    checkNotNull(stream);
    checkNotNull(function);
    boolean isParallel = stream.isParallel();
    Spliterator<T> fromSpliterator = stream.spliterator();

    if (!fromSpliterator.hasCharacteristics(Spliterator.SUBSIZED)) {
      Iterator<T> fromIterator = Spliterators.iterator(fromSpliterator);
      return StreamSupport.stream(
              new AbstractSpliterator<R>(
                  fromSpliterator.estimateSize(),
                  fromSpliterator.characteristics() & (Spliterator.ORDERED | Spliterator.SIZED)) {
                long index = 0;

                @Override
                public boolean tryAdvance(Consumer<? super R> action) {
                  if (fromIterator.hasNext()) {
                    action.accept(function.apply(fromIterator.next(), index++));
                    return true;
                  }
                  return false;
                }
              },
              isParallel)
          .onClose(stream::close);
    }
    class Splitr extends MapWithIndexSpliterator<Spliterator<T>, R, Splitr> implements Consumer<T> {

      T holder;

      Splitr(Spliterator<T> splitr, long index) {
        super(splitr, index);
      }

      @Override
      public void accept(T t) {
        this.holder = t;
      }

      @Override
      public boolean tryAdvance(Consumer<? super R> action) {
        if (fromSpliterator.tryAdvance(this)) {
          try {
            action.accept(function.apply(holder, index++));
            return true;
          } finally {
            holder = null;
          }
        }
        return false;
      }

      @Override
      Splitr createSplit(Spliterator<T> from, long i) {
        return new Splitr(from, i);
      }
    }
    return StreamSupport.stream(new Splitr(fromSpliterator, 0), isParallel).onClose(stream::close);
  }

  private abstract static class MapWithIndexSpliterator<
      F extends Spliterator<?>,
      R extends Object,
      S extends MapWithIndexSpliterator<F, R, S>>
      implements Spliterator<R> {

    final F fromSpliterator;
    long index;

    MapWithIndexSpliterator(F fromSpliterator, long index) {
      this.fromSpliterator = fromSpliterator;
      this.index = index;
    }

    abstract S createSplit(F from, long i);

    @Override
    public S trySplit() {
      Spliterator<?> splitOrNull = fromSpliterator.trySplit();
      if (splitOrNull == null) {
        return null;
      }
      @SuppressWarnings("unchecked")
      F split = (F) splitOrNull;
      S result = createSplit(split, index);
      this.index += split.getExactSizeIfKnown();
      return result;
    }

    @Override
    public long estimateSize() {
      return fromSpliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return fromSpliterator.characteristics()
          & (Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED);
    }
  }

  public interface FunctionWithIndex<T extends Object, R extends Object> {
    R apply(T from, long index);
  }
}

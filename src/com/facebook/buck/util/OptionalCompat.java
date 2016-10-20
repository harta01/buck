/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.util;

import com.google.common.collect.ImmutableSet;

import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Transitional helper functions for {@link java.util.Optional} that mimics functionality of
 * {@link com.google.common.base.Optional}.
 *
 * Avoid using these functions for new code.
 */
public final class OptionalCompat {
  private OptionalCompat() {}

  /**
   * @see com.google.common.base.Optional#asSet()
   */
  public static final <T> ImmutableSet<T> asSet(Optional<T> optional) {
    if (optional.isPresent()) {
      return ImmutableSet.of(optional.get());
    } else {
      return ImmutableSet.of();
    }
  }

  /**
   * @see com.google.common.base.Optional#presentInstances(Iterable)
   */
  public static final <T> Iterable<T> presentInstances(
      Iterable<? extends Optional<T>> optionals) {
    return (
        StreamSupport.stream(optionals.spliterator(), false)
            .filter(Optional::isPresent)
            .map(Optional::get)
    )::iterator;
  }
}
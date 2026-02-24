/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arakelian.json;

import java.util.Optional;
import java.util.Set;

import org.immutables.value.Value;

/**
 * Immutable configuration options for {@link JsonFilter}, specifying which fields to include or
 * exclude, whether to pretty-print the output, and an optional callback for custom processing.
 */
@Value.Immutable(copy = false)
public abstract class JsonFilterOptions {
    /** Constructs a new {@code JsonFilterOptions}. */
    protected JsonFilterOptions() {
    }
    /**
     * Returns the optional callback for custom processing during filtering.
     *
     * @return the callback, or {@code null} if none
     */
    @Nullable
    public abstract JsonFilterCallback getCallback();

    /**
     * Returns the set of field paths to exclude from the output.
     *
     * @return the set of excluded paths, or {@code null} if none
     */
    @Nullable
    public abstract Set<String> getExcludes();

    /**
     * Returns the set of field paths to include in the output.
     *
     * @return the set of included paths, or {@code null} if none
     */
    @Nullable
    public abstract Set<String> getIncludes();

    /**
     * Returns an optional flag indicating whether the output should be pretty-printed.
     *
     * @return an {@link Optional} containing the pretty-print flag
     */
    public abstract Optional<Boolean> getPretty();

    /**
     * Returns {@code true} if a callback has been configured.
     *
     * @return {@code true} if a callback is set
     */
    public final boolean hasCallback() {
        return getCallback() != null;
    }

    /**
     * Returns {@code true} if any exclude paths have been configured.
     *
     * @return {@code true} if excludes are set
     */
    public final boolean hasExcludes() {
        final Set<String> excludes = getExcludes();
        return excludes != null && excludes.size() != 0;
    }

    /**
     * Returns {@code true} if any include paths have been configured.
     *
     * @return {@code true} if includes are set
     */
    public final boolean hasIncludes() {
        final Set<String> includes = getIncludes();
        return includes != null && includes.size() != 0;
    }

    /**
     * Returns {@code true} if no includes, excludes, or callback have been configured.
     *
     * @return {@code true} if no filtering options are set
     */
    public final boolean isEmpty() {
        return !hasIncludes() && !hasExcludes() && !hasCallback();
    }

    /**
     * Returns true if an identity-transform is requested, e.g. includes and excludes are empty but
     * caller still wants filter applied for pretty printing.
     *
     * @return true if an identity-transform is requested
     */
    @Value.Default
    public boolean isIdentityTransform() {
        return false;
    }
}

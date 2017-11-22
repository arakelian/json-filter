/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

@Value.Immutable(copy = false)
public abstract class JsonFilterOptions {
    @Nullable
    public abstract JsonFilterCallback getCallback();

    @Nullable
    public abstract Set<String> getExcludes();

    @Nullable
    public abstract Set<String> getIncludes();

    public abstract Optional<Boolean> getPretty();

    public final boolean hasCallback() {
        return getCallback() != null;
    }

    public final boolean hasExcludes() {
        final Set<String> excludes = getExcludes();
        return excludes != null && excludes.size() != 0;
    }

    public final boolean hasIncludes() {
        final Set<String> includes = getIncludes();
        return includes != null && includes.size() != 0;
    }

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

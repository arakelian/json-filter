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

import java.io.IOException;

/**
 * Callback interface for receiving notifications during JSON filtering. Implementations can inject
 * custom logic at the start and end of JSON objects.
 */
public interface JsonFilterCallback {
    /**
     * Called immediately after the start of a JSON object is written to the output.
     *
     * @param filter the filter currently processing the JSON
     * @throws IOException if an I/O error occurs
     */
    @SuppressWarnings("unused")
    public default void afterStartObject(final JsonFilter filter) throws IOException {
    }

    /**
     * Called immediately before the end of a JSON object is written to the output.
     *
     * @param filter the filter currently processing the JSON
     * @throws IOException if an I/O error occurs
     */
    @SuppressWarnings("unused")
    public default void beforeEndObject(final JsonFilter filter) throws IOException {
    }
}

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.classfile;

import static java.util.Objects.requireNonNull;

/// A non-fatal physical-planning or emitted-code condition that callers may choose to reject.
///
/// @param category stable warning classification
/// @param location source-like class or method location
/// @param message human-readable explanation
public record CompilationWarning(Category category, String location, String message)
{
    public CompilationWarning
    {
        requireNonNull(category, "category is null");
        if (requireNonNull(location, "location is null").isBlank()) {
            throw new IllegalArgumentException("location is blank");
        }
        if (requireNonNull(message, "message is null").isBlank()) {
            throw new IllegalArgumentException("message is blank");
        }
    }

    /// Categories of non-fatal compilation outcomes.
    public enum Category
    {
        /// JVM optimization thresholds were unavailable, so conservative defaults were used.
        JIT_THRESHOLD_FALLBACK,
        /// An emitted method remains above the optimizing-compilation target.
        HUGE_METHOD,
    }
}

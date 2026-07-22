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

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import java.lang.management.ManagementFactory;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/// Immutable hard and optimization budgets used by physical planning.
///
/// @param hardMethodCodeLimit maximum permitted emitted `Code` attribute size
/// @param targetMethodCodeLimit optimization target above which splitting is attempted
/// @param maxInlineSize ordinary HotSpot inlining-size guidance
/// @param frequentInlineSize hot-call-site HotSpot inlining-size guidance
/// @param jitThresholdFallback whether the target limit uses a fallback because the JVM threshold
///         is not observable
public record CompilationPolicy(
        int hardMethodCodeLimit,
        int targetMethodCodeLimit,
        int maxInlineSize,
        int frequentInlineSize,
        boolean jitThresholdFallback)
{
    private static final int JVM_METHOD_CODE_LIMIT = 65_535;

    public CompilationPolicy
    {
        requireRange(hardMethodCodeLimit, 1, JVM_METHOD_CODE_LIMIT, "hardMethodCodeLimit");
        requireRange(targetMethodCodeLimit, 1, hardMethodCodeLimit, "targetMethodCodeLimit");
        requireRange(maxInlineSize, 1, hardMethodCodeLimit, "maxInlineSize");
        requireRange(frequentInlineSize, maxInlineSize, hardMethodCodeLimit, "frequentInlineSize");
    }

    /// Derives a policy from observable HotSpot options, using conservative fallbacks when an
    /// option is unavailable.
    public static CompilationPolicy defaults()
    {
        return DefaultsHolder.DEFAULTS;
    }

    private static CompilationPolicy loadDefaults()
    {
        Optional<String> dontCompileHugeMethods = vmOption("DontCompileHugeMethods");
        Optional<String> hugeMethodLimit = vmOption("HugeMethodLimit");
        boolean hugeMethodsDisabled = dontCompileHugeMethods.map(Boolean::parseBoolean).orElse(true);
        int hugeLimit = hugeMethodLimit.map(Integer::parseInt).orElse(8_000);
        int target = hugeMethodsDisabled ? Math.max(1, hugeLimit * 9 / 10) : JVM_METHOD_CODE_LIMIT;
        return new CompilationPolicy(
                JVM_METHOD_CODE_LIMIT,
                target,
                vmOption("MaxInlineSize").map(Integer::parseInt).orElse(35),
                vmOption("FreqInlineSize").map(Integer::parseInt).orElse(325),
                hugeMethodsDisabled && hugeMethodLimit.isEmpty());
    }

    private static final class DefaultsHolder
    {
        private static final CompilationPolicy DEFAULTS = loadDefaults();

        private DefaultsHolder() {}
    }

    /// Creates a builder initialized from [CompilationPolicy#defaults()].
    public static Builder builder()
    {
        return new Builder(defaults());
    }

    private static Optional<String> vmOption(String name)
    {
        try {
            CompositeData option = (CompositeData) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                    "getVMOption",
                    new Object[] {name},
                    new String[] {String.class.getName()});
            return Optional.of((String) option.get("value"));
        }
        catch (JMException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name)
    {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("%s must be between %s and %s: %s".formatted(name, minimum, maximum, value));
        }
    }

    /// Mutable, set-once overrides for a default compilation policy.
    public static final class Builder
    {
        private int hardMethodCodeLimit;
        private int targetMethodCodeLimit;
        private int maxInlineSize;
        private int frequentInlineSize;
        private boolean jitThresholdFallback;
        private boolean hardMethodCodeLimitSet;
        private boolean targetMethodCodeLimitSet;
        private boolean maxInlineSizeSet;
        private boolean frequentInlineSizeSet;

        private Builder(CompilationPolicy defaults)
        {
            requireNonNull(defaults, "defaults is null");
            hardMethodCodeLimit = defaults.hardMethodCodeLimit();
            targetMethodCodeLimit = defaults.targetMethodCodeLimit();
            maxInlineSize = defaults.maxInlineSize();
            frequentInlineSize = defaults.frequentInlineSize();
            jitThresholdFallback = defaults.jitThresholdFallback();
        }

        public Builder hardMethodCodeLimit(int hardMethodCodeLimit)
        {
            if (hardMethodCodeLimitSet) {
                throw new IllegalStateException("hard method code limit is already set");
            }
            this.hardMethodCodeLimit = hardMethodCodeLimit;
            hardMethodCodeLimitSet = true;
            return this;
        }

        public Builder targetMethodCodeLimit(int targetMethodCodeLimit)
        {
            if (targetMethodCodeLimitSet) {
                throw new IllegalStateException("target method code limit is already set");
            }
            this.targetMethodCodeLimit = targetMethodCodeLimit;
            targetMethodCodeLimitSet = true;
            jitThresholdFallback = false;
            return this;
        }

        public Builder maxInlineSize(int maxInlineSize)
        {
            if (maxInlineSizeSet) {
                throw new IllegalStateException("max inline size is already set");
            }
            this.maxInlineSize = maxInlineSize;
            maxInlineSizeSet = true;
            return this;
        }

        public Builder frequentInlineSize(int frequentInlineSize)
        {
            if (frequentInlineSizeSet) {
                throw new IllegalStateException("frequent inline size is already set");
            }
            this.frequentInlineSize = frequentInlineSize;
            frequentInlineSizeSet = true;
            return this;
        }

        public CompilationPolicy build()
        {
            return new CompilationPolicy(hardMethodCodeLimit, targetMethodCodeLimit, maxInlineSize, frequentInlineSize, jitThresholdFallback);
        }
    }
}

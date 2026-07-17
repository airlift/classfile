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

import java.lang.reflect.AccessFlag;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.joining;

final class ModelRendering
{
    private ModelRendering() {}

    static String access(Set<AccessFlag> access, AccessFlag... excluded)
    {
        Set<AccessFlag> excludedFlags = Set.copyOf(Arrays.asList(excluded));
        String value = access.stream()
                .filter(flag -> !excludedFlags.contains(flag))
                .sorted(comparingInt(Enum::ordinal))
                .map(flag -> flag.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .collect(joining(" "));
        return value.isEmpty() ? "" : value + " ";
    }

    static String indent(String value, String prefix)
    {
        return value.lines().map(line -> prefix + line).collect(joining("\n"));
    }
}

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

final class Identity
{
    private Identity() {}

    /// Tests object identity explicitly. DOM ownership and rewrite detection use
    /// identity because equal reusable nodes remain distinct attachment instances.
    @SuppressWarnings("ReferenceEquality")
    static boolean same(Object left, Object right)
    {
        return left == right;
    }

    static boolean different(Object left, Object right)
    {
        return !same(left, right);
    }
}

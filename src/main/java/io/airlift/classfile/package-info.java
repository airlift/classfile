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

/// Declarative Java class generation built on the JDK Class-File API.
///
/// Classes are authored as reusable logical models with [ClassDefinition], [MethodDefinition],
/// [CodeBlock], structured control flow, and fluent [BytecodeExpression] values. A
/// [ClassCompiler] validates and lowers those models for a specific [CompilationTarget], applying
/// automatic physical planning without mutating the logical model.
///
/// Generated classes are defined with [StandardClassDefiner] or [HiddenClassDefiner].
package io.airlift.classfile;

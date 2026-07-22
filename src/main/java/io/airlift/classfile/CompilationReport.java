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

import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashSet;
import java.util.List;

import static java.util.Objects.requireNonNull;

/// Measurements and optimization warnings for the physical classes produced by a compilation.
///
/// The report describes emitted artifacts, including compiler-generated helper classes and
/// methods, rather than only the authored logical model.
///
/// @param policy policy used for physical planning
/// @param classes measurements for every emitted physical class
/// @param warnings non-fatal conditions encountered during planning or after emission
public record CompilationReport(CompilationPolicy policy, List<ClassInfo> classes, List<CompilationWarning> warnings)
{
    public CompilationReport
    {
        requireNonNull(policy, "policy is null");
        classes = List.copyOf(requireNonNull(classes, "classes is null"));
        warnings = List.copyOf(new LinkedHashSet<>(requireNonNull(warnings, "warnings is null")));
    }

    static CompilationReport measure(CompilationPolicy policy, List<CompiledUnit.Artifact> artifacts, List<CompilationWarning> warnings)
    {
        return new CompilationReport(policy, artifacts.stream()
                .map(CompiledUnit.Artifact::classInfo)
                .toList(), warnings);
    }

    static ClassInfo measure(ClassDesc type, byte[] classfile, java.lang.classfile.ClassModel model)
    {
        requireNonNull(type, "type is null");
        requireNonNull(classfile, "classfile is null");
        requireNonNull(model, "model is null");
        List<MethodInfo> methods = model.methods().stream()
                .map(method -> {
                    CodeAttribute code = method.code()
                            .filter(CodeAttribute.class::isInstance)
                            .map(CodeAttribute.class::cast)
                            .orElse(null);
                    return new MethodInfo(
                            method.methodName().stringValue(),
                            method.methodTypeSymbol(),
                            code == null ? 0 : code.codeLength(),
                            code == null ? 0 : code.maxStack(),
                            code == null ? 0 : code.maxLocals());
                })
                .toList();
        return new ClassInfo(
                type,
                classfile.length,
                model.constantPool().size(),
                model.constantPool().bootstrapMethodCount(),
                model.fields().size(),
                methods);
    }

    /// Measurements for one emitted physical class.
    public record ClassInfo(
            ClassDesc type,
            int classfileBytes,
            int constantPoolCount,
            int bootstrapMethodCount,
            int fieldCount,
            List<MethodInfo> methods)
    {
        public ClassInfo
        {
            requireNonNull(type, "type is null");
            methods = List.copyOf(requireNonNull(methods, "methods is null"));
        }
    }

    /// Measurements for one emitted method body. Methods without code report zero code, stack, and
    /// local sizes.
    public record MethodInfo(String name, MethodTypeDesc type, int codeBytes, int maxStack, int maxLocals)
    {
        public MethodInfo
        {
            requireNonNull(name, "name is null");
            requireNonNull(type, "type is null");
        }
    }
}

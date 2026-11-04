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

import io.airlift.classfile.tool.ClassFileDiagnostics;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.util.Objects.requireNonNull;

/// Explains a JVM rejection of generated classes with the JDK verifier.
///
/// Compilation does not verify emitted classes; the JVM checks each class when it is defined and
/// linked. When it rejects a class a definer is defining, this runs the verifier over every class
/// defined with it, because HotSpot may report a defective superclass while defining or linking a
/// subclass.
final class ClassVerification
{
    private ClassVerification() {}

    /// Returns the verification failure that explains the JVM's rejection, or empty when the
    /// verifier finds no error and the rejection should be rethrown unchanged. A failure of the
    /// diagnosis itself is added to the rejection as suppressed.
    ///
    /// @param target the target the classes were compiled for
    /// @param classfiles supplies the classfiles defined together, in emission order; collecting
    ///         them is part of the diagnosis
    /// @param rejection the JVM's error, kept as the cause of the returned failure
    static Optional<CompilationException> explainRejection(CompilationTarget target, Supplier<Map<ClassDesc, byte[]>> classfiles, LinkageError rejection)
    {
        requireNonNull(target, "target is null");
        requireNonNull(classfiles, "classfiles is null");
        requireNonNull(rejection, "rejection is null");
        try {
            Map<ClassDesc, byte[]> definedClassfiles = classfiles.get();
            Set<ClassDesc> interfaces = new LinkedHashSet<>();
            Map<ClassDesc, ClassDesc> superClasses = new LinkedHashMap<>();
            for (byte[] classfile : definedClassfiles.values()) {
                java.lang.classfile.ClassModel model = ClassFile.of().parse(classfile);
                ClassDesc type = model.thisClass().asSymbol();
                if (model.flags().has(INTERFACE)) {
                    interfaces.add(type);
                }
                else {
                    superClasses.put(type, model.superclass().orElseThrow().asSymbol());
                }
            }
            // Generated types resolve first, as they did during compilation, so the verifier
            // neither misses them nor loads a class that is still being defined.
            ClassHierarchyResolver hierarchyResolver = ClassHierarchyResolver.of(interfaces, superClasses)
                    .orElse(target.hierarchyResolver())
                    .cached();
            ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchyResolver));

            for (Map.Entry<ClassDesc, byte[]> entry : definedClassfiles.entrySet()) {
                if (!classFile.verify(entry.getValue()).isEmpty()) {
                    return Optional.of(new CompilationException(
                            "Verification failed for " + entry.getKey().displayName() + ":\n" + ClassFileDiagnostics.disassemble(entry.getValue()),
                            rejection));
                }
            }
        }
        catch (RuntimeException | Error e) {
            rejection.addSuppressed(e);
        }
        return Optional.empty();
    }
}

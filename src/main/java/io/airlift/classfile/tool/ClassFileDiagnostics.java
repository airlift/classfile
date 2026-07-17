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
package io.airlift.classfile.tool;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/// Produces readable diagnostics for emitted classfiles using the JDK Class-File API.
public final class ClassFileDiagnostics
{
    private ClassFileDiagnostics() {}

    /// Runs JDK classfile verification and returns every reported verification error.
    public static List<VerifyError> verify(byte[] classfile)
    {
        return List.copyOf(ClassFile.of().verify(requireNonNull(classfile, "classfile is null")));
    }

    /// Renders class metadata, method code, and verification results as readable text.
    public static String disassemble(byte[] classfile)
    {
        requireNonNull(classfile, "classfile is null");
        ClassModel model = ClassFile.of().parse(classfile);
        StringBuilder output = new StringBuilder();
        output.append("class ").append(qualifiedName(model.thisClass().asSymbol()))
                .append(" version ").append(model.majorVersion()).append('.').append(model.minorVersion())
                .append(" flags ").append(model.flags().flags()).append('\n');
        model.superclass().ifPresent(superclass -> output.append("  extends ").append(superclass.asSymbol().displayName()).append('\n'));
        if (!model.interfaces().isEmpty()) {
            output.append("  implements ")
                    .append(model.interfaces().stream().map(entry -> entry.asSymbol().displayName()).collect(joining(", ")))
                    .append('\n');
        }
        model.findAttribute(Attributes.record()).ifPresent(attribute -> attribute.components().forEach(component -> output.append("record component ")
                .append(component.descriptorSymbol().displayName()).append(' ')
                .append(component.name().stringValue()).append('\n')));
        model.fields().forEach(field -> output.append("field ")
                .append(field.flags().flags()).append(' ')
                .append(field.fieldTypeSymbol().displayName()).append(' ')
                .append(field.fieldName().stringValue()).append('\n'));
        model.methods().forEach(method -> appendMethod(output, method));

        List<VerifyError> errors = ClassFile.of().verify(model);
        output.append(errors.isEmpty() ? "verification: OK\n" : "verification errors:\n");
        errors.forEach(error -> output.append("  ").append(error).append('\n'));
        return output.toString();
    }

    /// Writes the binary classfile and its text disassembly under the supplied root using the
    /// class's internal name.
    public static DumpFiles dump(Path root, byte[] classfile)
            throws IOException
    {
        requireNonNull(root, "root is null");
        requireNonNull(classfile, "classfile is null");
        ClassModel model = ClassFile.of().parse(classfile);
        Path classPath = root.resolve(model.thisClass().asInternalName() + ".class");
        Path textPath = root.resolve(model.thisClass().asInternalName() + ".class.txt");
        Path parent = classPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(classPath, classfile);
        Files.writeString(textPath, disassemble(classfile), StandardCharsets.UTF_8);
        return new DumpFiles(classPath, textPath);
    }

    private static void appendMethod(StringBuilder output, MethodModel method)
    {
        output.append("method ")
                .append(method.flags().flags()).append(' ')
                .append(method.methodName().stringValue())
                .append(method.methodTypeSymbol().displayDescriptor()).append('\n');
        method.code().ifPresent(code -> {
            if (code instanceof CodeAttribute attribute) {
                output.append("  code maxStack=").append(attribute.maxStack())
                        .append(" maxLocals=").append(attribute.maxLocals())
                        .append(" length=").append(attribute.codeLength()).append('\n');
            }
            output.append(indent(code.toDebugString(), "    ")).append('\n');
        });
    }

    private static String indent(String value, String prefix)
    {
        return value.lines().map(line -> prefix + line).collect(joining("\n"));
    }

    private static String qualifiedName(ClassDesc type)
    {
        return type.packageName().isEmpty() ? type.displayName() : type.packageName() + "." + type.displayName();
    }

    public record DumpFiles(Path classFile, Path textFile)
    {
        public DumpFiles
        {
            requireNonNull(classFile, "classFile is null");
            requireNonNull(textFile, "textFile is null");
        }
    }
}

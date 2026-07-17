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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.airlift.classfile.BytecodeExpressions.constantInt;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.reflect.AccessFlag.FINAL;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;
import static org.assertj.core.api.Assertions.assertThat;

class TestClassFileDiagnostics
{
    @TempDir
    Path temporaryDirectory;

    @Test
    void testDisassembleVerifyAndDump()
            throws Exception
    {
        ClassDesc type = ClassDesc.of("io.airlift.classfile.diagnostic.GeneratedDiagnostic");
        ClassDefinition classDefinition = ClassDefinition.define(type).access(PUBLIC, FINAL).sourceFile("GeneratedDiagnostic.java");
        MethodDefinition method = classDefinition.method("compute", CD_int).access(PUBLIC, STATIC);
        method.body().append(constantInt(3).add(constantInt(7)).ret());
        ClassModel model = classDefinition.build();
        byte[] classfile = ClassCompiler.forTarget(CompilationTarget.forClassLoader(getClass().getClassLoader()))
                .compileClass(model)
                .classfile();

        assertThat(model.toString())
                .startsWith("public final class GeneratedDiagnostic {")
                .contains("public static int compute()")
                .contains("return (3 + 7);")
                .endsWith("}");

        assertThat(ClassFileDiagnostics.verify(classfile)).isEmpty();
        assertThat(ClassFileDiagnostics.disassemble(classfile))
                .contains("class io.airlift.classfile.diagnostic.GeneratedDiagnostic")
                .contains("method [PUBLIC, STATIC] compute()int")
                .containsIgnoringCase("iadd")
                .endsWith("verification: OK\n");

        ClassFileDiagnostics.DumpFiles files = ClassFileDiagnostics.dump(temporaryDirectory, classfile);
        assertThat(files.classFile()).isEqualTo(temporaryDirectory.resolve("io/airlift/classfile/diagnostic/GeneratedDiagnostic.class"));
        assertThat(files.textFile()).isEqualTo(temporaryDirectory.resolve("io/airlift/classfile/diagnostic/GeneratedDiagnostic.class.txt"));
        assertThat(Files.readAllBytes(files.classFile())).isEqualTo(classfile);
        assertThat(Files.readString(files.textFile())).contains("verification: OK");
    }
}

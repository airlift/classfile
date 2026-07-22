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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestDependencyPolicy
{
    private static final Set<String> PUBLIC_EXPRESSION_FACTORY_NAMES = Set.of(
            "boundConstant",
            "boundMethodHandle",
            "classData",
            "constantBoolean",
            "constantClass",
            "constantDouble",
            "constantDynamic",
            "constantFalse",
            "constantFloat",
            "constantInt",
            "constantLong",
            "constantNull",
            "constantNumber",
            "constantString",
            "constantTrue",
            "defaultValue",
            "getStatic",
            "inlineIf",
            "invokeDynamic",
            "invokeStatic",
            "newArray",
            "newInstance",
            "setStatic");

    @Test
    void testMainPackageIsJdkOnly()
            throws IOException
    {
        Path sourceRoot = Path.of("src/main/java/io/airlift/classfile");
        List<String> forbiddenImports;
        try (var files = Files.walk(sourceRoot)) {
            forbiddenImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> readLines(path).stream())
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> !line.startsWith("import java.") &&
                            !line.startsWith("import javax.") &&
                            !line.startsWith("import static java.") &&
                            !line.startsWith("import io.airlift.classfile.") &&
                            !line.startsWith("import static io.airlift.classfile."))
                    .toList();
        }
        assertThat(forbiddenImports).isEmpty();
    }

    @Test
    void testPublicApiDoesNotExposeLegacyOrThirdPartyTypes()
            throws IOException
    {
        Path classesRoot = Path.of("target/classes");
        Path packageRoot = classesRoot.resolve("io/airlift/classfile");
        List<String> forbiddenSignatures;
        try (var files = Files.walk(packageRoot)) {
            forbiddenSignatures = files
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(path -> className(classesRoot, path))
                    .map(TestDependencyPolicy::loadClass)
                    .filter(type -> Modifier.isPublic(type.getModifiers()))
                    .flatMap(type -> Stream.of(
                                    Arrays.stream(type.getDeclaredConstructors())
                                            .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                                            .map(Constructor::toGenericString),
                                    Arrays.stream(type.getDeclaredMethods())
                                            .filter(method -> Modifier.isPublic(method.getModifiers()))
                                            .map(Method::toGenericString),
                                    Arrays.stream(type.getDeclaredFields())
                                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                                            .map(Field::toGenericString))
                            .flatMap(Function.identity()))
                    .filter(TestDependencyPolicy::containsForbiddenType)
                    .toList();
        }
        assertThat(forbiddenSignatures).isEmpty();
    }

    @Test
    void testPublicExpressionFactoriesAreRootsOrReceiverlessOperations()
    {
        Set<String> publicFactories = Arrays.stream(BytecodeExpressions.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicFactories).isEqualTo(PUBLIC_EXPRESSION_FACTORY_NAMES);
    }

    @Test
    void testRemovedConveniencesStayRemoved()
    {
        assertThat(publicParameterTypes(CodeBlock.Builder.class, "comment"))
                .containsExactly(List.of(String.class));
        assertThat(publicParameterTypes(MethodDefinition.class, "comment"))
                .containsExactly(List.of(String.class));
        assertThat(publicParameterTypes(MethodDefinition.class, "parameter"))
                .isEmpty();
        assertThat(Arrays.stream(BytecodeExpression.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("invoke"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(Iterable.class::isAssignableFrom))
                .isTrue();
        assertThat(Stream.concat(
                        Arrays.stream(ClassDefinition.class.getDeclaredMethods()),
                        Arrays.stream(MethodDefinition.class.getDeclaredMethods()))
                .map(Method::getName))
                .doesNotContain("interfaces", "annotation", "invisibleAnnotation");
    }

    @Test
    void testRuntimeDefinitionApiDoesNotExposeRawClassfiles()
    {
        assertThat(Arrays.stream(StandardClassDefiner.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .doesNotContain(byte[].class, Map.class);
        assertThat(Arrays.stream(HiddenClassDefiner.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .doesNotContain(byte[].class, Map.class);
    }

    @Test
    void testCompilationRequiresExplicitTarget()
    {
        assertThat(Arrays.stream(ClassCompiler.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("forTarget");
        assertThat(publicParameterTypes(ClassCompiler.class, "forTarget"))
                .containsExactly(List.of(CompilationTarget.class));
    }

    private static List<List<Class<?>>> publicParameterTypes(Class<?> type, String methodName)
    {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals(methodName))
                .map(method -> List.of(method.getParameterTypes()))
                .toList();
    }

    private static List<String> readLines(Path path)
    {
        try {
            return Files.readAllLines(path, UTF_8);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String className(Path root, Path classFile)
    {
        return root.relativize(classFile).toString()
                .replace(File.separatorChar, '.')
                .replaceFirst("\\.class$", "");
    }

    private static Class<?> loadClass(String name)
    {
        try {
            return Class.forName(name, false, TestDependencyPolicy.class.getClassLoader());
        }
        catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean containsForbiddenType(String signature)
    {
        return signature.contains("org.objectweb.asm") ||
                signature.contains("com.google") ||
                signature.contains("io.airlift.bytecode.") ||
                signature.contains("io.airlift.bytecode2.") ||
                signature.contains("java.lang.classfile.CodeBuilder") ||
                signature.contains("java.lang.classfile.CodeElement") ||
                signature.contains("java.lang.classfile.instruction.");
    }
}

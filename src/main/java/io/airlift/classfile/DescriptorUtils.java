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

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_byte;
import static java.lang.constant.ConstantDescs.CD_char;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_short;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.util.Objects.requireNonNull;

/// Conversions and validation helpers for JDK symbolic class and method descriptors.
public final class DescriptorUtils
{
    private DescriptorUtils() {}

    public static ClassDesc classDesc(Class<?> type)
    {
        requireNonNull(type, "type is null");
        return ClassDesc.ofDescriptor(type.descriptorString());
    }

    public static MethodTypeDesc methodType(Class<?> returnType, Class<?>... parameterTypes)
    {
        requireNonNull(parameterTypes, "parameterTypes is null");
        ClassDesc[] parameters = new ClassDesc[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            parameters[index] = classDesc(requireNonNull(parameterTypes[index], "parameterType is null"));
        }
        return MethodTypeDesc.of(classDesc(returnType), parameters);
    }

    public static MethodTypeDesc methodType(Method method)
    {
        requireNonNull(method, "method is null");
        return methodType(method.getReturnType(), method.getParameterTypes());
    }

    public static MethodTypeDesc methodType(MethodType methodType)
    {
        requireNonNull(methodType, "methodType is null");
        return MethodTypeDesc.of(
                classDesc(methodType.returnType()),
                methodType.parameterList().stream().map(DescriptorUtils::classDesc).toList());
    }

    public static MethodTypeDesc methodType(Constructor<?> constructor)
    {
        requireNonNull(constructor, "constructor is null");
        return methodType(void.class, constructor.getParameterTypes());
    }

    static void requireFieldType(ClassDesc type, String name)
    {
        requireNonNull(type, name + " is null");
        if (type.equals(CD_void)) {
            throw new IllegalArgumentException(name + " is void");
        }
    }

    static String requireFieldName(String name)
    {
        return requireUnqualifiedName(name, false);
    }

    static String requireMethodName(String name)
    {
        requireUnqualifiedName(name, true);
        if ((name.indexOf('<') >= 0 || name.indexOf('>') >= 0) && !name.equals("<init>") && !name.equals("<clinit>")) {
            throw new IllegalArgumentException("Invalid method name: " + name);
        }
        return name;
    }

    private static String requireUnqualifiedName(String name, boolean method)
    {
        if (requireNonNull(name, "name is null").isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '.' || character == ';' || character == '[' || character == '/') {
                throw new IllegalArgumentException("Invalid %s name: %s".formatted(method ? "method" : "field", name));
            }
        }
        return name;
    }

    static boolean isReference(ClassDesc type)
    {
        return !type.isPrimitive();
    }

    static boolean isIntLike(ClassDesc type)
    {
        return type.equals(CD_boolean) || isNarrowInteger(type);
    }

    static boolean isNarrowInteger(ClassDesc type)
    {
        return type.equals(CD_byte) || type.equals(CD_char) || type.equals(CD_short) || type.equals(CD_int);
    }

    static boolean isIntegral(ClassDesc type)
    {
        return isIntLike(type) || type.equals(CD_long);
    }

    static boolean isNumeric(ClassDesc type)
    {
        return isIntegral(type) || type.equals(CD_float) || type.equals(CD_double);
    }

    static boolean isBoxedPrimitive(ClassDesc type)
    {
        return primitiveType(type).isPresent();
    }

    static ClassDesc boxedType(ClassDesc primitiveType)
    {
        if (primitiveType.equals(CD_boolean)) {
            return ClassDesc.of("java.lang.Boolean");
        }
        if (primitiveType.equals(CD_byte)) {
            return ClassDesc.of("java.lang.Byte");
        }
        if (primitiveType.equals(CD_char)) {
            return ClassDesc.of("java.lang.Character");
        }
        if (primitiveType.equals(CD_short)) {
            return ClassDesc.of("java.lang.Short");
        }
        if (primitiveType.equals(CD_int)) {
            return ClassDesc.of("java.lang.Integer");
        }
        if (primitiveType.equals(CD_long)) {
            return ClassDesc.of("java.lang.Long");
        }
        if (primitiveType.equals(CD_float)) {
            return ClassDesc.of("java.lang.Float");
        }
        if (primitiveType.equals(CD_double)) {
            return ClassDesc.of("java.lang.Double");
        }
        throw new IllegalArgumentException("Not a primitive value type: " + primitiveType.displayName());
    }

    static Optional<ClassDesc> primitiveType(ClassDesc boxedType)
    {
        if (boxedType.equals(ClassDesc.of("java.lang.Boolean"))) {
            return Optional.of(CD_boolean);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Byte"))) {
            return Optional.of(CD_byte);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Character"))) {
            return Optional.of(CD_char);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Short"))) {
            return Optional.of(CD_short);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Integer"))) {
            return Optional.of(CD_int);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Long"))) {
            return Optional.of(CD_long);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Float"))) {
            return Optional.of(CD_float);
        }
        if (boxedType.equals(ClassDesc.of("java.lang.Double"))) {
            return Optional.of(CD_double);
        }
        return Optional.empty();
    }

    static String simpleName(ClassDesc type)
    {
        requireNonNull(type, "type is null");
        return type.displayName();
    }

    static String quote(String value)
    {
        requireNonNull(value, "value is null");
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\b' -> builder.append("\\b");
                case '\t' -> builder.append("\\t");
                case '\n' -> builder.append("\\n");
                case '\f' -> builder.append("\\f");
                case '\r' -> builder.append("\\r");
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                default -> {
                    if (Character.isISOControl(codePoint)) {
                        String hex = Integer.toHexString(codePoint);
                        builder.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    }
                    else {
                        builder.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return builder.append('"').toString();
    }
}

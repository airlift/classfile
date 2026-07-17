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
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static io.airlift.classfile.DescriptorUtils.boxedType;
import static io.airlift.classfile.DescriptorUtils.classDesc;
import static io.airlift.classfile.DescriptorUtils.isIntLike;
import static io.airlift.classfile.DescriptorUtils.isIntegral;
import static io.airlift.classfile.DescriptorUtils.isNarrowInteger;
import static io.airlift.classfile.DescriptorUtils.isNumeric;
import static io.airlift.classfile.DescriptorUtils.isReference;
import static io.airlift.classfile.DescriptorUtils.methodType;
import static io.airlift.classfile.DescriptorUtils.quote;
import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
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

/// Static factories for expressions that do not naturally begin with an existing value.
///
/// This includes constants, static invocations, allocation, runtime bindings, and conditional
/// expressions. Operations on an existing value are normally written with the fluent methods on
/// [BytecodeExpression].
public final class BytecodeExpressions
{
    private BytecodeExpressions() {}

    public static BytecodeExpression constantTrue()
    {
        return constantBoolean(true);
    }

    public static BytecodeExpression constantFalse()
    {
        return constantBoolean(false);
    }

    public static BytecodeExpression constantBoolean(boolean value)
    {
        return core(new ExpressionNode.Constant(CD_boolean, value, Boolean.toString(value)));
    }

    public static BytecodeExpression constantInt(int value)
    {
        return core(new ExpressionNode.Constant(CD_int, value, Integer.toString(value)));
    }

    public static BytecodeExpression constantLong(long value)
    {
        return core(new ExpressionNode.Constant(CD_long, value, value + "L"));
    }

    public static BytecodeExpression constantFloat(float value)
    {
        return core(new ExpressionNode.Constant(CD_float, value, Float.isNaN(value) ? "NaNf" : value + "f"));
    }

    public static BytecodeExpression constantDouble(double value)
    {
        return core(new ExpressionNode.Constant(CD_double, value, Double.isNaN(value) ? "NaN" : Double.toString(value)));
    }

    public static BytecodeExpression constantNumber(Number value)
    {
        return switch (requireNonNull(value, "value is null")) {
            case Byte byteValue -> constantInt(byteValue.intValue());
            case Short shortValue -> constantInt(shortValue.intValue());
            case Integer integer -> constantInt(integer);
            case Long longValue -> constantLong(longValue);
            case Float floatValue -> constantFloat(floatValue);
            case Double doubleValue -> constantDouble(doubleValue);
            default -> throw new IllegalArgumentException("Unsupported numeric constant: " + value.getClass().getName());
        };
    }

    public static BytecodeExpression constantString(String value)
    {
        return core(new ExpressionNode.Constant(CD_String, requireNonNull(value, "value is null"), quote(value)));
    }

    public static BytecodeExpression constantClass(Class<?> value)
    {
        return constantClass(classDesc(value));
    }

    public static BytecodeExpression constantClass(ClassDesc value)
    {
        requireNonNull(value, "value is null");
        return core(new ExpressionNode.Constant(CD_Class, value, value.displayName() + ".class"));
    }

    public static BytecodeExpression constantNull(Class<?> type)
    {
        return constantNull(classDesc(type));
    }

    public static BytecodeExpression constantNull(ClassDesc type)
    {
        requireReference(type, "null type");
        return core(new ExpressionNode.Constant(type, null, "null"));
    }

    public static BytecodeExpression defaultValue(Class<?> type)
    {
        return defaultValue(classDesc(type));
    }

    public static BytecodeExpression defaultValue(ClassDesc type)
    {
        requireNonNull(type, "type is null");
        if (type.equals(CD_void)) {
            throw new IllegalArgumentException("void does not have a default value");
        }
        if (!type.isPrimitive()) {
            return constantNull(type);
        }
        if (type.equals(CD_long)) {
            return constantLong(0);
        }
        if (type.equals(CD_float)) {
            return constantFloat(0);
        }
        if (type.equals(CD_double)) {
            return constantDouble(0);
        }
        if (type.equals(CD_boolean)) {
            return constantFalse();
        }
        if (type.equals(CD_int)) {
            return constantInt(0);
        }
        return core(new ExpressionNode.Constant(type, 0, "0"));
    }

    public static BytecodeExpression constantDynamic(DynamicConstantDesc<?> constant)
    {
        requireNonNull(constant, "constant is null");
        return core(new ExpressionNode.DynamicConstant(constant.constantType(), constant));
    }

    /// Binds an object by identity through the runtime-data mechanism instead of encoding it in the
    /// constant pool. The declared type must be visible from the compilation target.
    public static BytecodeExpression boundConstant(Object value, Class<?> type)
    {
        return boundConstant(value, classDesc(type));
    }

    /// Binds an object by identity using a symbolic declared type that need not yet be loaded.
    public static BytecodeExpression boundConstant(Object value, ClassDesc type)
    {
        requireReference(type, "type");
        return core(new ExpressionNode.BoundConstant(type, requireNonNull(value, "value is null")));
    }

    /// Binds a method handle and adapts inaccessible signature types for the eventual compilation
    /// target.
    public static BoundMethodHandle boundMethodHandle(MethodHandle handle)
    {
        return new BoundMethodHandle(handle);
    }

    /// Reads the caller-supplied class-data value associated with the generated class.
    public static BytecodeExpression classData(Class<?> type)
    {
        return classData(classDesc(type));
    }

    /// Reads class data with a symbolic declared type that need not yet be loaded.
    public static BytecodeExpression classData(ClassDesc type)
    {
        requireReference(type, "type");
        DynamicConstantDesc<?> constant = DynamicConstantDesc.ofNamed(
                BootstrapDescriptors.classDataConstant(),
                "classData",
                type);
        return constantDynamic(constant);
    }

    static BytecodeExpression add(BytecodeExpression left, BytecodeExpression right)
    {
        return arithmetic("+", left, right);
    }

    static BytecodeExpression subtract(BytecodeExpression left, BytecodeExpression right)
    {
        return arithmetic("-", left, right);
    }

    static BytecodeExpression multiply(BytecodeExpression left, BytecodeExpression right)
    {
        return arithmetic("*", left, right);
    }

    static BytecodeExpression divide(BytecodeExpression left, BytecodeExpression right)
    {
        return arithmetic("/", left, right);
    }

    static BytecodeExpression remainder(BytecodeExpression left, BytecodeExpression right)
    {
        return arithmetic("%", left, right);
    }

    static BytecodeExpression bitwiseAnd(BytecodeExpression left, BytecodeExpression right)
    {
        return integral("&", left, right);
    }

    static BytecodeExpression bitwiseOr(BytecodeExpression left, BytecodeExpression right)
    {
        return integral("|", left, right);
    }

    static BytecodeExpression bitwiseXor(BytecodeExpression left, BytecodeExpression right)
    {
        return integral("^", left, right);
    }

    static BytecodeExpression shiftLeft(BytecodeExpression left, BytecodeExpression right)
    {
        return shift("<<", left, right);
    }

    static BytecodeExpression shiftRight(BytecodeExpression left, BytecodeExpression right)
    {
        return shift(">>", left, right);
    }

    static BytecodeExpression shiftRightUnsigned(BytecodeExpression left, BytecodeExpression right)
    {
        return shift(">>>", left, right);
    }

    static BytecodeExpression negate(BytecodeExpression value)
    {
        requireNumeric(value, "value");
        return core(new ExpressionNode.Unary(numericPromotion(value.type()), "-", value));
    }

    static BytecodeExpression lessThan(BytecodeExpression left, BytecodeExpression right)
    {
        return orderedCompare("<", left, right);
    }

    static BytecodeExpression lessThanOrEqual(BytecodeExpression left, BytecodeExpression right)
    {
        return orderedCompare("<=", left, right);
    }

    static BytecodeExpression greaterThan(BytecodeExpression left, BytecodeExpression right)
    {
        return orderedCompare(">", left, right);
    }

    static BytecodeExpression greaterThanOrEqual(BytecodeExpression left, BytecodeExpression right)
    {
        return orderedCompare(">=", left, right);
    }

    static BytecodeExpression equal(BytecodeExpression left, BytecodeExpression right)
    {
        return equality("==", left, right);
    }

    static BytecodeExpression notEqual(BytecodeExpression left, BytecodeExpression right)
    {
        return equality("!=", left, right);
    }

    static BytecodeExpression logicalAnd(BytecodeExpression left, BytecodeExpression right)
    {
        requireBoolean(left, "left");
        requireBoolean(right, "right");
        return core(new ExpressionNode.Binary(CD_boolean, "&&", left, right));
    }

    static BytecodeExpression logicalOr(BytecodeExpression left, BytecodeExpression right)
    {
        requireBoolean(left, "left");
        requireBoolean(right, "right");
        return core(new ExpressionNode.Binary(CD_boolean, "||", left, right));
    }

    static BytecodeExpression logicalNot(BytecodeExpression value)
    {
        requireBoolean(value, "value");
        return core(new ExpressionNode.Unary(CD_boolean, "!", value));
    }

    static BytecodeExpression isNull(BytecodeExpression value)
    {
        requireReference(value.type(), "value");
        return equal(value, constantNull(value.type()));
    }

    static BytecodeExpression isNotNull(BytecodeExpression value)
    {
        requireReference(value.type(), "value");
        return notEqual(value, constantNull(value.type()));
    }

    public static BytecodeExpression inlineIf(BytecodeExpression condition, BytecodeExpression ifTrue, BytecodeExpression ifFalse)
    {
        requireBoolean(condition, "condition");
        requireSameType(ifTrue, ifFalse, "conditional branches");
        return core(new ExpressionNode.InlineIf(ifTrue.type(), condition, ifTrue, ifFalse));
    }

    static BytecodeExpression cast(BytecodeExpression value, Class<?> type)
    {
        return cast(value, classDesc(type));
    }

    static BytecodeExpression cast(BytecodeExpression value, ClassDesc type)
    {
        requireNonNull(value, "value is null");
        requireNonNull(type, "type is null");
        if (type.equals(CD_void) || value.type().equals(CD_void)) {
            throw new IllegalArgumentException("void cannot be cast");
        }
        validateCast(value.type(), type);
        return core(new ExpressionNode.Cast(type, value));
    }

    static BytecodeExpression instanceOf(BytecodeExpression value, Class<?> testType)
    {
        return instanceOf(value, classDesc(testType));
    }

    static BytecodeExpression instanceOf(BytecodeExpression value, ClassDesc testType)
    {
        requireReference(requireNonNull(value, "value is null").type(), "value");
        requireReference(testType, "testType");
        return core(new ExpressionNode.InstanceOf(value, testType));
    }

    public static BytecodeExpression invokeStatic(Method method, BytecodeExpression... arguments)
    {
        requireNonNull(method, "method is null");
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException("method is not static: " + method);
        }
        return invokeStatic(classDesc(method.getDeclaringClass()), method.getName(), methodType(method), arguments);
    }

    public static BytecodeExpression invokeStatic(MethodDefinition method, BytecodeExpression... arguments)
    {
        requireNonNull(method, "method is null");
        if (!method.isStatic()) {
            throw new IllegalArgumentException("method is not static: " + method);
        }
        return invokeStatic(method.declaringType(), method.name(), method.methodType(), arguments);
    }

    public static BytecodeExpression invokeStatic(Class<?> owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invokeStatic(classDesc(owner), name, methodType, arguments);
    }

    public static BytecodeExpression invokeStatic(Class<?> owner, String name, Class<?> returnType, BytecodeExpression... arguments)
    {
        return invokeStatic(
                classDesc(owner),
                name,
                MethodTypeDesc.of(classDesc(returnType), Arrays.stream(arguments).map(BytecodeExpression::type).toList()),
                arguments);
    }

    public static BytecodeExpression invokeStatic(ClassDesc owner, String name, ClassDesc returnType, BytecodeExpression... arguments)
    {
        return invokeStatic(owner, name, MethodTypeDesc.of(returnType, Arrays.stream(arguments).map(BytecodeExpression::type).toList()), arguments);
    }

    public static BytecodeExpression invokeStatic(ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invoke(ExpressionNode.InvocationKind.STATIC, null, owner, name, methodType, arguments);
    }

    static BytecodeExpression invokeVirtual(BytecodeExpression target, ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invoke(ExpressionNode.InvocationKind.VIRTUAL, target, owner, name, methodType, arguments);
    }

    static BytecodeExpression invokeInterface(BytecodeExpression target, ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invoke(ExpressionNode.InvocationKind.INTERFACE, target, owner, name, methodType, arguments);
    }

    static BytecodeExpression invokeSpecial(BytecodeExpression target, ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        return invoke(ExpressionNode.InvocationKind.SPECIAL, target, owner, name, methodType, arguments);
    }

    public static BytecodeExpression invokeDynamic(DynamicCallSiteDesc callSite, BytecodeExpression... arguments)
    {
        requireNonNull(callSite, "callSite is null");
        List<BytecodeExpression> argumentList = copy(arguments);
        validateArguments(callSite.invocationType(), argumentList);
        return core(new ExpressionNode.InvokeDynamic(callSite.invocationType().returnType(), callSite, argumentList));
    }

    public static BytecodeExpression newInstance(Constructor<?> constructor, BytecodeExpression... arguments)
    {
        requireNonNull(constructor, "constructor is null");
        return newInstance(classDesc(constructor.getDeclaringClass()), methodType(constructor), arguments);
    }

    public static BytecodeExpression newInstance(Class<?> type, BytecodeExpression... arguments)
    {
        requireNonNull(type, "type is null");
        List<Constructor<?>> matches = Arrays.stream(type.getConstructors())
                .filter(constructor -> constructor.getParameterCount() == arguments.length)
                .filter(constructor -> parametersAccept(constructor.getParameterTypes(), arguments))
                .toList();
        if (matches.size() == 1) {
            return newInstance(matches.getFirst(), arguments);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No compatible public constructor found for " + type.getName() + "; provide an exact MethodTypeDesc");
        }
        throw new IllegalArgumentException("Constructor is ambiguous for " + type.getName() + "; provide an exact MethodTypeDesc");
    }

    public static BytecodeExpression newInstance(ClassDesc type, MethodTypeDesc constructorType, BytecodeExpression... arguments)
    {
        requireReference(type, "type");
        requireNonNull(constructorType, "constructorType is null");
        if (!constructorType.returnType().equals(CD_void)) {
            throw new IllegalArgumentException("constructor descriptor must return void: " + constructorType);
        }
        List<BytecodeExpression> argumentList = copy(arguments);
        validateArguments(constructorType, argumentList);
        return core(new ExpressionNode.NewInstance(type, constructorType, argumentList));
    }

    public static BytecodeExpression newArray(Class<?> arrayType, BytecodeExpression length)
    {
        return newArray(classDesc(arrayType), length);
    }

    public static BytecodeExpression newArray(Class<?> arrayType, int length)
    {
        return newArray(classDesc(arrayType), length);
    }

    public static BytecodeExpression newArray(ClassDesc arrayType, int length)
    {
        if (length < 0) {
            throw new IllegalArgumentException("length is negative: " + length);
        }
        return newArray(arrayType, constantInt(length));
    }

    public static BytecodeExpression newArray(ClassDesc arrayType, BytecodeExpression length)
    {
        requireArray(arrayType);
        requireIntLike(requireNonNull(length, "length is null"), "length");
        return core(new ExpressionNode.NewArray(arrayType, length, List.of()));
    }

    public static BytecodeExpression newArray(Class<?> arrayType, List<? extends BytecodeExpression> elements)
    {
        return newArray(classDesc(arrayType), elements);
    }

    public static BytecodeExpression newArray(ClassDesc arrayType, List<? extends BytecodeExpression> elements)
    {
        requireArray(arrayType);
        List<BytecodeExpression> copied = List.copyOf(requireNonNull(elements, "elements is null"));
        copied.forEach(element -> requireAssignable(arrayType.componentType(), element.type(), "array element"));
        return core(new ExpressionNode.NewArray(arrayType, null, copied));
    }

    static BytecodeExpression length(BytecodeExpression array)
    {
        requireArray(requireNonNull(array, "array is null").type());
        return core(new ExpressionNode.ArrayLength(array));
    }

    static BytecodeExpression getElement(BytecodeExpression array, BytecodeExpression index)
    {
        requireArray(requireNonNull(array, "array is null").type());
        requireIntLike(requireNonNull(index, "index is null"), "index");
        return core(new ExpressionNode.ArrayGet(array.type().componentType(), array, index));
    }

    static BytecodeExpression setElement(BytecodeExpression array, BytecodeExpression index, BytecodeExpression value)
    {
        requireArray(requireNonNull(array, "array is null").type());
        requireIntLike(requireNonNull(index, "index is null"), "index");
        requireAssignable(array.type().componentType(), requireNonNull(value, "value is null").type(), "array element");
        return core(new ExpressionNode.ArraySet(CD_void, array, index, value));
    }

    public static BytecodeExpression getStatic(Field field)
    {
        requireNonNull(field, "field is null");
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("field is not static: " + field);
        }
        return getStatic(classDesc(field.getDeclaringClass()), field.getName(), classDesc(field.getType()));
    }

    public static BytecodeExpression getStatic(Class<?> owner, String name)
    {
        return getStatic(publicField(owner, name));
    }

    public static BytecodeExpression getStatic(FieldDefinition field)
    {
        requireNonNull(field, "field is null");
        if (!field.isStatic()) {
            throw new IllegalArgumentException("field is not static: " + field);
        }
        return getStatic(field.declaringType(), field.name(), field.type());
    }

    public static BytecodeExpression getStatic(ClassDesc owner, String name, ClassDesc type)
    {
        DescriptorUtils.requireFieldType(type, "type");
        return core(new ExpressionNode.FieldGet(type, null, requireNonNull(owner, "owner is null"), requireName(name), true));
    }

    static BytecodeExpression getField(BytecodeExpression target, Field field)
    {
        requireNonNull(field, "field is null");
        if (Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("field is static: " + field);
        }
        return getField(target, classDesc(field.getDeclaringClass()), field.getName(), classDesc(field.getType()));
    }

    static BytecodeExpression getField(BytecodeExpression target, FieldDefinition field)
    {
        requireNonNull(field, "field is null");
        if (field.isStatic()) {
            throw new IllegalArgumentException("field is static: " + field);
        }
        return getField(target, field.declaringType(), field.name(), field.type());
    }

    static BytecodeExpression getField(BytecodeExpression target, ClassDesc owner, String name, ClassDesc type)
    {
        requireReference(requireNonNull(target, "target is null").type(), "target");
        DescriptorUtils.requireFieldType(type, "type");
        return core(new ExpressionNode.FieldGet(type, target, requireNonNull(owner, "owner is null"), requireName(name), false));
    }

    public static BytecodeExpression setStatic(FieldDefinition field, BytecodeExpression value)
    {
        requireNonNull(field, "field is null");
        if (!field.isStatic()) {
            throw new IllegalArgumentException("field is not static: " + field);
        }
        return setStatic(field.declaringType(), field.name(), field.type(), value);
    }

    public static BytecodeExpression setStatic(Field field, BytecodeExpression value)
    {
        requireNonNull(field, "field is null");
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("field is not static: " + field);
        }
        return setStatic(classDesc(field.getDeclaringClass()), field.getName(), classDesc(field.getType()), value);
    }

    public static BytecodeExpression setStatic(Class<?> owner, String name, BytecodeExpression value)
    {
        return setStatic(publicField(owner, name), value);
    }

    public static BytecodeExpression setStatic(ClassDesc owner, String name, ClassDesc type, BytecodeExpression value)
    {
        requireAssignable(type, requireNonNull(value, "value is null").type(), "field value");
        return core(new ExpressionNode.FieldSet(CD_void, null, owner, requireName(name), type, value, true));
    }

    static BytecodeExpression setField(BytecodeExpression target, Field field, BytecodeExpression value)
    {
        requireNonNull(field, "field is null");
        if (Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("field is static: " + field);
        }
        return setField(target, classDesc(field.getDeclaringClass()), field.getName(), classDesc(field.getType()), value);
    }

    static BytecodeExpression setField(BytecodeExpression target, FieldDefinition field, BytecodeExpression value)
    {
        requireNonNull(field, "field is null");
        if (field.isStatic()) {
            throw new IllegalArgumentException("field is static: " + field);
        }
        return setField(target, field.declaringType(), field.name(), field.type(), value);
    }

    static BytecodeExpression setField(BytecodeExpression target, ClassDesc owner, String name, ClassDesc type, BytecodeExpression value)
    {
        requireReference(requireNonNull(target, "target is null").type(), "target");
        requireAssignable(type, requireNonNull(value, "value is null").type(), "field value");
        return core(new ExpressionNode.FieldSet(CD_void, target, owner, requireName(name), type, value, false));
    }

    static BytecodeExpression setVariable(Variable variable, BytecodeExpression value)
    {
        requireNonNull(variable, "variable is null");
        requireAssignable(variable.type(), requireNonNull(value, "value is null").type(), "variable value");
        return core(new ExpressionNode.SetVariable(CD_void, variable, value));
    }

    static BytecodeExpression increment(Variable variable)
    {
        requireNonNull(variable, "variable is null");
        if (isIntLike(variable.type()) && !variable.type().equals(CD_boolean)) {
            return core(new ExpressionNode.Increment(CD_void, variable));
        }
        if (variable.type().equals(CD_long)) {
            return variable.set(variable.add(constantLong(1)));
        }
        throw new UnsupportedOperationException("Variable %s of type %s does not support incrementing"
                .formatted(variable.name(), variable.type().displayName()));
    }

    static BytecodeExpression pop(BytecodeExpression value)
    {
        requireNonNull(value, "value is null");
        if (value.type().equals(CD_void)) {
            return value;
        }
        return core(new ExpressionNode.Adapter(CD_void, "", value));
    }

    static BytecodeExpression ret(BytecodeExpression value)
    {
        requireNonNull(value, "value is null");
        if (value.type().equals(CD_void)) {
            throw new IllegalArgumentException("void expression cannot be returned as a value");
        }
        return core(new ExpressionNode.Adapter(CD_void, "return", value));
    }

    static BytecodeExpression ret()
    {
        return core(new ExpressionNode.Constant(CD_void, null, "return"));
    }

    static BytecodeExpression throwObject(BytecodeExpression value)
    {
        requireReference(requireNonNull(value, "value is null").type(), "value");
        return core(new ExpressionNode.Adapter(CD_void, "throw", value));
    }

    private static BytecodeExpression invoke(ExpressionNode.InvocationKind kind, BytecodeExpression target, ClassDesc owner, String name, MethodTypeDesc methodType, BytecodeExpression... arguments)
    {
        requireNonNull(kind, "kind is null");
        requireReference(owner, "owner");
        requireName(name);
        if (name.equals("<clinit>")) {
            throw new IllegalArgumentException("class initializer cannot be invoked");
        }
        if (name.equals("<init>")) {
            throw new IllegalArgumentException("constructor invocation is only valid as a CodeBlock operation");
        }
        requireNonNull(methodType, "methodType is null");
        if (kind != ExpressionNode.InvocationKind.STATIC) {
            requireReference(requireNonNull(target, "target is null").type(), "target");
        }
        List<BytecodeExpression> argumentList = copy(arguments);
        validateArguments(methodType, argumentList);
        return core(new ExpressionNode.Invoke(methodType.returnType(), kind, target, owner, name, methodType, argumentList));
    }

    private static BytecodeExpression arithmetic(String operator, BytecodeExpression left, BytecodeExpression right)
    {
        requireNumeric(left, "left");
        requireNumeric(right, "right");
        requireSamePromotedType(left, right, "operands");
        return core(new ExpressionNode.Binary(numericPromotion(left.type()), operator, left, right));
    }

    private static BytecodeExpression integral(String operator, BytecodeExpression left, BytecodeExpression right)
    {
        requireIntegral(left, "left");
        requireIntegral(right, "right");
        requireSamePromotedType(left, right, "operands");
        return core(new ExpressionNode.Binary(numericPromotion(left.type()), operator, left, right));
    }

    private static BytecodeExpression shift(String operator, BytecodeExpression left, BytecodeExpression right)
    {
        requireIntegral(left, "left");
        requireIntLike(right, "right");
        return core(new ExpressionNode.Binary(numericPromotion(left.type()), operator, left, right));
    }

    private static ClassDesc numericPromotion(ClassDesc type)
    {
        return type.equals(CD_byte) || type.equals(CD_char) || type.equals(CD_short) ? CD_int : type;
    }

    private static void requireSamePromotedType(BytecodeExpression left, BytecodeExpression right, String name)
    {
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
        if (!numericPromotion(left.type()).equals(numericPromotion(right.type()))) {
            throw new IllegalArgumentException(name + " have different promoted types: " + left.type().displayName() + " and " + right.type().displayName());
        }
    }

    private static BytecodeExpression orderedCompare(String operator, BytecodeExpression left, BytecodeExpression right)
    {
        requireNumeric(left, "left");
        requireNumeric(right, "right");
        requireSameType(left, right, "operands");
        return core(new ExpressionNode.Binary(CD_boolean, operator, left, right));
    }

    private static BytecodeExpression equality(String operator, BytecodeExpression left, BytecodeExpression right)
    {
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
        if (left.type().isPrimitive() || right.type().isPrimitive()) {
            requireSameType(left, right, "operands");
        }
        return core(new ExpressionNode.Binary(CD_boolean, operator, left, right));
    }

    static void validateArguments(MethodTypeDesc methodType, List<BytecodeExpression> arguments)
    {
        if (methodType.parameterCount() != arguments.size()) {
            throw new IllegalArgumentException("Expected %s arguments but found %s".formatted(methodType.parameterCount(), arguments.size()));
        }
        for (int index = 0; index < arguments.size(); index++) {
            requireAssignable(methodType.parameterType(index), arguments.get(index).type(), "argument " + index);
        }
    }

    private static boolean parametersAccept(Class<?>[] parameterTypes, BytecodeExpression[] arguments)
    {
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = parameterTypes[index];
            ClassDesc argumentType = arguments[index].type();
            if (parameterType.isPrimitive()) {
                if (!classDesc(parameterType).equals(argumentType) && !(isNarrowInteger(classDesc(parameterType)) && isNarrowInteger(argumentType))) {
                    return false;
                }
                continue;
            }
            if (argumentType.isPrimitive()) {
                return false;
            }
            try {
                if (!parameterType.isAssignableFrom(argumentType.resolveConstantDesc(MethodHandles.publicLookup()))) {
                    return false;
                }
            }
            catch (ReflectiveOperationException ignored) {
                if (!classDesc(parameterType).equals(argumentType)) {
                    return false;
                }
            }
        }
        return true;
    }

    static Field publicField(Class<?> owner, String name)
    {
        requireNonNull(owner, "owner is null");
        requireName(name);
        try {
            return owner.getField(name);
        }
        catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No public field %s.%s".formatted(owner.getName(), name), e);
        }
    }

    static void requireAssignable(ClassDesc target, ClassDesc source, String name)
    {
        requireNonNull(target, "target is null");
        requireNonNull(source, "source is null");
        if (target.isPrimitive() || source.isPrimitive()) {
            if (!target.equals(source) && !(isNarrowInteger(target) && isNarrowInteger(source))) {
                throw new IllegalArgumentException(name + " type " + source.displayName() + " is not assignable to " + target.displayName());
            }
        }
        // Symbolic descriptors do not provide hierarchy resolution. Reference
        // assignability is verified by the JVM at linkage or by an optional resolver.
    }

    private static void validateCast(ClassDesc source, ClassDesc target)
    {
        if (source.isPrimitive() && target.isPrimitive()) {
            if (source.equals(CD_boolean) || target.equals(CD_boolean)) {
                if (!source.equals(target)) {
                    throw invalidCast(source, target);
                }
            }
            return;
        }
        if (source.isPrimitive()) {
            if (!target.equals(boxedType(source)) && !target.equals(CD_Object)) {
                throw invalidCast(source, target);
            }
            return;
        }
        if (target.isPrimitive()) {
            if (!source.equals(boxedType(target)) && !source.equals(CD_Object)) {
                throw invalidCast(source, target);
            }
            return;
        }
        if (DescriptorUtils.isBoxedPrimitive(source) && DescriptorUtils.isBoxedPrimitive(target) && !source.equals(target)) {
            throw invalidCast(source, target);
        }
    }

    private static IllegalArgumentException invalidCast(ClassDesc source, ClassDesc target)
    {
        return new IllegalArgumentException("Type %s cannot be cast to %s".formatted(source.displayName(), target.displayName()));
    }

    private static void requireSameType(BytecodeExpression left, BytecodeExpression right, String name)
    {
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
        if (!left.type().equals(right.type())) {
            throw new IllegalArgumentException(name + " have different types: " + left.type().displayName() + " and " + right.type().displayName());
        }
    }

    private static void requireBoolean(BytecodeExpression expression, String name)
    {
        if (!requireNonNull(expression, name + " is null").type().equals(CD_boolean)) {
            throw new IllegalArgumentException(name + " is not boolean: " + expression.type().displayName());
        }
    }

    private static void requireNumeric(BytecodeExpression expression, String name)
    {
        if (!isNumeric(requireNonNull(expression, name + " is null").type()) || expression.type().equals(CD_boolean)) {
            throw new IllegalArgumentException(name + " is not numeric: " + expression.type().displayName());
        }
    }

    private static void requireIntegral(BytecodeExpression expression, String name)
    {
        if (!isIntegral(requireNonNull(expression, name + " is null").type()) || expression.type().equals(CD_boolean)) {
            throw new IllegalArgumentException(name + " is not integral: " + expression.type().displayName());
        }
    }

    private static void requireIntLike(BytecodeExpression expression, String name)
    {
        if (!isIntLike(requireNonNull(expression, name + " is null").type()) || expression.type().equals(CD_boolean)) {
            throw new IllegalArgumentException(name + " is not int-like: " + expression.type().displayName());
        }
    }

    private static void requireReference(ClassDesc type, String name)
    {
        requireNonNull(type, name + " is null");
        if (!isReference(type)) {
            throw new IllegalArgumentException(name + " is not a reference type: " + type.displayName());
        }
    }

    private static void requireArray(ClassDesc type)
    {
        requireNonNull(type, "type is null");
        if (!type.isArray()) {
            throw new IllegalArgumentException("type is not an array: " + type.displayName());
        }
    }

    private static String requireName(String name)
    {
        if (requireNonNull(name, "name is null").isBlank()) {
            throw new IllegalArgumentException("name is blank");
        }
        return name;
    }

    private static List<BytecodeExpression> copy(BytecodeExpression[] arguments)
    {
        return List.copyOf(Arrays.asList(requireNonNull(arguments, "arguments is null")));
    }

    private static BytecodeExpression core(ExpressionNode node)
    {
        return new CoreExpression(node);
    }
}

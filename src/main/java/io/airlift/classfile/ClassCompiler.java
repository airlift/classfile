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

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.classfile.TypeKind.DOUBLE;
import static java.lang.classfile.TypeKind.FLOAT;
import static java.lang.classfile.TypeKind.LONG;
import static java.lang.classfile.TypeKind.REFERENCE;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.INTERFACE;
import static java.lang.reflect.AccessFlag.SUPER;
import static java.util.Objects.requireNonNull;

/// Validates and compiles logical class models for a specific runtime linkage environment.
///
/// A compiler is immutable. Configuration methods return a new compiler, and compilation never
/// mutates the supplied logical model. [ClassCompiler#compileUnit(ClassModel)] additionally enables
/// companion classes and name-free physical linkage; the simpler compiled forms retain their
/// explicitly authored class boundaries.
public final class ClassCompiler
{
    private static final boolean[] NO_ALIAS_COPY_DECLARATIONS = {};

    private final CompilationTarget target;
    private final Optional<Object> classData;
    private final CompilationPolicy policy;
    private final boolean policySet;

    private ClassCompiler(CompilationTarget target, Optional<Object> classData, CompilationPolicy policy, boolean policySet)
    {
        this.target = requireNonNull(target, "target is null");
        this.classData = requireNonNull(classData, "classData is null");
        this.policy = requireNonNull(policy, "policy is null");
        this.policySet = policySet;
    }

    /// Creates a compiler for the exact linkage environment in which its output will be defined.
    public static ClassCompiler forTarget(CompilationTarget target)
    {
        return new ClassCompiler(target, Optional.empty(), CompilationPolicy.defaults(), false);
    }

    /// Supplies the caller-owned class-data value made available by
    /// [BytecodeExpressions#classData(Class)].
    ///
    /// The value is retained by the compiled artifact and may be set once.
    public ClassCompiler classData(Object classData)
    {
        if (this.classData.isPresent()) {
            throw new IllegalStateException("class data is already set");
        }
        return new ClassCompiler(target, Optional.of(requireNonNull(classData, "classData is null")), policy, policySet);
    }

    /// Replaces the physical-planning policy for this compiler. The policy may be set once.
    public ClassCompiler policy(CompilationPolicy policy)
    {
        if (policySet) {
            throw new IllegalStateException("compilation policy is already set");
        }
        return new ClassCompiler(target, classData, requireNonNull(policy, "policy is null"), true);
    }

    /// Compiles one logical model to one classfile.
    ///
    /// Large methods may be split into helper methods in the same class, but this form never
    /// creates companion classes. Use [ClassCompiler#compileUnit(ClassModel)] when physical helper
    /// classes and a compilation report are desired.
    public CompiledClass compileClass(ClassModel definition)
    {
        requireNonNull(definition, "definition is null");
        PlannedDefinition planned = plan(definition);
        ClassModel physicalDefinition = planned.model();
        LinkageContext linkage = new LinkageContext(target, List.of(physicalDefinition));
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(linkage.hierarchyResolver()));
        BindingCollector bindings = new BindingCollector();
        EmittedClass emitted = emitClassfile(physicalDefinition, bindings, classFile, linkage, classData, planned.lambdaImplementations());
        enforceHardMethodLimit(List.of(emitted.classInfo()));
        return new CompiledClass(
                target,
                physicalDefinition.type(),
                emitted.classfile(),
                new RuntimeData(classData, bindings.values()),
                emitted.classDataTypes(),
                emitted.lambdaFactoryRequired());
    }

    /// Compiles a primary logical model into a complete physical unit.
    ///
    /// The result may contain helper classes and name-free links introduced by automatic physical
    /// planning. Callers normally define the result as a unit and then retrieve its primary class.
    public CompiledUnit compileUnit(ClassModel definition)
    {
        return compileUnit(requireNonNull(definition, "definition is null"), List.of());
    }

    CompiledUnit compileUnit(ClassModel primary, List<ClassModel> auxiliaryClasses)
    {
        requireNonNull(primary, "primary is null");
        auxiliaryClasses = List.copyOf(requireNonNull(auxiliaryClasses, "auxiliaryClasses is null"));
        ArrayList<ClassModel> definitions = new ArrayList<>(auxiliaryClasses.size() + 1);
        definitions.addAll(auxiliaryClasses);
        definitions.add(primary);

        List<PlannedDefinition> plannedDefinitions = definitions.stream()
                .map(this::plan)
                .toList();
        Map<ClassDesc, Set<HiddenClassLinkage.MethodReference>> lambdaImplementations = plannedDefinitions.stream()
                .collect(Collectors.toMap(
                        definition -> definition.model().type(),
                        PlannedDefinition::lambdaImplementations));
        LinkageContext planningLinkage = new LinkageContext(target, plannedDefinitions.stream().map(PlannedDefinition::model).toList());

        ArrayList<ClassModel> physicalDefinitions = new ArrayList<>(definitions.size());
        for (PlannedDefinition definition : plannedDefinitions) {
            ClassSharder.Result sharded = ClassSharder.shard(definition.model(), definition.generatedMethods(), planningLinkage);
            physicalDefinitions.addAll(sharded.auxiliaries());
            physicalDefinitions.add(sharded.primary());
        }
        definitions = physicalDefinitions;

        LinkageContext linkage = new LinkageContext(target, definitions);
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(linkage.hierarchyResolver()));
        ArrayList<CompiledUnit.Artifact> artifacts = new ArrayList<>(definitions.size());
        LinkedHashMap<ClassDesc, Boolean> seen = new LinkedHashMap<>();
        for (ClassModel definition : definitions) {
            requireNonNull(definition, "definition is null");
            if (seen.putIfAbsent(definition.type(), true) != null) {
                throw new IllegalArgumentException("Class is defined more than once: " + definition.type().displayName());
            }
            BindingCollector bindings = new BindingCollector();
            EmittedClass emitted = emitClassfile(
                    definition,
                    bindings,
                    classFile,
                    linkage,
                    classData,
                    lambdaImplementations.getOrDefault(definition.type(), Set.of()));
            artifacts.add(new CompiledUnit.Artifact(
                    definition.type(),
                    emitted.classfile(),
                    classData,
                    bindings.bindings(),
                    emitted.classDataTypes(),
                    emitted.lambdaFactoryRequired(),
                    emitted.classInfo()));
        }
        ArrayList<CompilationWarning> warnings = new ArrayList<>();
        if (policy.jitThresholdFallback()) {
            warnings.add(new CompilationWarning(
                    CompilationWarning.Category.JIT_THRESHOLD_FALLBACK,
                    primary.type().displayName(),
                    "HugeMethodLimit is not observable on this JVM; using the 8,000-byte HotSpot fallback with a 10% safety margin"));
        }
        CompilationReport measured = enforceHardMethodLimit(artifacts, warnings);
        measured.classes().forEach(classInfo -> classInfo.methods().stream()
                .filter(method -> method.codeBytes() > policy.targetMethodCodeLimit())
                .forEach(method -> warnings.add(new CompilationWarning(
                        CompilationWarning.Category.HUGE_METHOD,
                        classInfo.type().displayName() + "." + method.name() + method.type().descriptorString(),
                        "Emitted method is %s bytes, above the %s-byte optimizing-compilation target"
                                .formatted(method.codeBytes(), policy.targetMethodCodeLimit())))));
        return new CompiledUnit(target, primary.type(), artifacts, new CompilationReport(policy, measured.classes(), warnings));
    }

    private PlannedDefinition plan(ClassModel definition)
    {
        ExpressionPlanner.Result expressions = ExpressionPlanner.plan(definition, policy, target.hiddenClass());
        StatementPlanner.Result statements = StatementPlanner.plan(expressions.model(), policy, Set.copyOf(expressions.generatedMethods()), target.hiddenClass());
        LinkedHashSet<String> generatedMethods = new LinkedHashSet<>(expressions.generatedMethods());
        generatedMethods.addAll(statements.generatedMethods());
        return new PlannedDefinition(statements.model(), Set.copyOf(generatedMethods), expressions.lambdaImplementations());
    }

    private record PlannedDefinition(
            ClassModel model,
            Set<String> generatedMethods,
            Set<HiddenClassLinkage.MethodReference> lambdaImplementations) {}

    /// Compiles explicitly authored nominal classes with shared runtime bindings.
    ///
    /// Symbolic references between the classes remain ordinary named class references. This form
    /// does not introduce companion classes; use [ClassCompiler#compileUnit(ClassModel)] for
    /// automatic physical class sharding.
    public CompiledClassBundle compileClassBundle(List<ClassModel> definitions)
    {
        definitions = List.copyOf(requireNonNull(definitions, "definitions is null"));
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("definitions is empty");
        }
        List<PlannedDefinition> plannedDefinitions = definitions.stream()
                .map(this::plan)
                .toList();
        Map<ClassDesc, Set<HiddenClassLinkage.MethodReference>> lambdaImplementations = plannedDefinitions.stream()
                .collect(Collectors.toMap(
                        definition -> definition.model().type(),
                        PlannedDefinition::lambdaImplementations));
        definitions = plannedDefinitions.stream()
                .map(PlannedDefinition::model)
                .toList();
        LinkageContext linkage = new LinkageContext(target, definitions);
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(linkage.hierarchyResolver()));

        BindingCollector bindings = new BindingCollector();
        LinkedHashMap<ClassDesc, byte[]> classfiles = new LinkedHashMap<>();
        LinkedHashSet<ClassDesc> classDataTypes = new LinkedHashSet<>();
        ArrayList<CompilationReport.ClassInfo> classInfos = new ArrayList<>(definitions.size());
        for (ClassModel definition : definitions) {
            requireNonNull(definition, "definition is null");
            if (classfiles.containsKey(definition.type())) {
                throw new IllegalArgumentException("Class is defined more than once: " + definition.type().displayName());
            }
            EmittedClass emitted = emitClassfile(
                    definition,
                    bindings,
                    classFile,
                    linkage,
                    classData,
                    lambdaImplementations.getOrDefault(definition.type(), Set.of()));
            classfiles.put(definition.type(), emitted.classfile());
            classDataTypes.addAll(emitted.classDataTypes());
            classInfos.add(emitted.classInfo());
        }
        enforceHardMethodLimit(classInfos);
        return new CompiledClassBundle(target, classfiles, new RuntimeData(classData, bindings.values()), classDataTypes);
    }

    private void enforceHardMethodLimit(List<CompilationReport.ClassInfo> classes)
    {
        classes.forEach(classInfo -> classInfo.methods().stream()
                .filter(method -> method.codeBytes() > policy.hardMethodCodeLimit())
                .findFirst()
                .ifPresent(method -> {
                    throw new CompilationException("Generated method %s.%s%s is %s bytes, above the configured hard limit of %s bytes"
                            .formatted(classInfo.type().displayName(), method.name(), method.type().descriptorString(), method.codeBytes(), policy.hardMethodCodeLimit()));
                }));
    }

    private CompilationReport enforceHardMethodLimit(List<CompiledUnit.Artifact> artifacts, List<CompilationWarning> warnings)
    {
        CompilationReport measured = CompilationReport.measure(policy, artifacts, warnings);
        enforceHardMethodLimit(measured.classes());
        return measured;
    }

    private EmittedClass emitClassfile(
            ClassModel definition,
            BindingCollector bindings,
            ClassFile classFile,
            LinkageContext linkage,
            Optional<Object> classData,
            Set<HiddenClassLinkage.MethodReference> lambdaImplementations)
    {
        try {
            linkage.requireGeneratedType(definition.type());
            Set<ClassDesc> classDataTypes = LinkageValidator.validate(definition, linkage, classData);
            byte[] classfile = buildClassfile(definition, bindings, classFile, linkage, Map.of());
            java.lang.classfile.ClassModel parsedClassfile = classFile.parse(classfile);
            CompilationReport.ClassInfo classInfo = CompilationReport.measure(definition.type(), classfile, parsedClassfile);

            Map<HiddenClassLinkage.MethodReference, Integer> lambdaBoundaryPadding = lambdaBoundaryPadding(classInfo, lambdaImplementations);
            if (!lambdaBoundaryPadding.isEmpty()) {
                classfile = buildClassfile(definition, bindings, classFile, linkage, lambdaBoundaryPadding);
                parsedClassfile = classFile.parse(classfile);
                classInfo = CompilationReport.measure(definition.type(), classfile, parsedClassfile);
            }
            List<VerifyError> errors = classFile.verify(parsedClassfile);
            if (!errors.isEmpty()) {
                throw new CompilationException("Verification failed for " + definition.type().displayName() + ":\n" + ClassFileDiagnostics.disassemble(classfile));
            }
            return new EmittedClass(
                    classfile,
                    classDataTypes,
                    bindings.lambdaFactoryRequired(),
                    classInfo);
        }
        catch (CompilationException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new CompilationException("Failed to compile %s:%n%s".formatted(definition.type().displayName(), definition), e);
        }
    }

    private static byte[] buildClassfile(
            ClassModel definition,
            BindingCollector bindings,
            ClassFile classFile,
            LinkageContext linkage,
            Map<HiddenClassLinkage.MethodReference, Integer> lambdaBoundaryPadding)
    {
        return classFile.build(definition.type(), classBuilder -> emitClass(
                definition,
                classBuilder,
                bindings,
                linkage,
                lambdaBoundaryPadding));
    }

    private Map<HiddenClassLinkage.MethodReference, Integer> lambdaBoundaryPadding(
            CompilationReport.ClassInfo classInfo,
            Set<HiddenClassLinkage.MethodReference> lambdaImplementations)
    {
        if (lambdaImplementations.isEmpty() || policy.frequentInlineSize() >= policy.targetMethodCodeLimit()) {
            return Map.of();
        }

        // Small hidden lambda targets must remain inlineable so their captures can be scalar
        // replaced. Targets in the upper half of HotSpot's hot-inlining window are large enough
        // to inflate an already nontrivial consumer compilation, so keep only those as ordinary
        // compiled method boundaries. Measure emitted Code rather than relying on the planner's
        // deliberately approximate size estimate.
        int boundaryTrigger = policy.maxInlineSize() + (policy.frequentInlineSize() - policy.maxInlineSize()) / 2;
        LinkedHashMap<HiddenClassLinkage.MethodReference, Integer> padding = new LinkedHashMap<>();
        for (CompilationReport.MethodInfo method : classInfo.methods()) {
            HiddenClassLinkage.MethodReference reference = new HiddenClassLinkage.MethodReference(method.name(), method.type());
            if (lambdaImplementations.contains(reference) &&
                    method.codeBytes() > boundaryTrigger &&
                    method.codeBytes() <= policy.frequentInlineSize()) {
                padding.put(reference, policy.frequentInlineSize() - method.codeBytes() + 1);
            }
        }
        return Map.copyOf(padding);
    }

    private static final class EmittedClass
    {
        private final byte[] classfile;
        private final Set<ClassDesc> classDataTypes;
        private final boolean lambdaFactoryRequired;
        private final CompilationReport.ClassInfo classInfo;

        private EmittedClass(byte[] classfile, Set<ClassDesc> classDataTypes, boolean lambdaFactoryRequired, CompilationReport.ClassInfo classInfo)
        {
            this.classfile = requireNonNull(classfile, "classfile is null");
            this.classDataTypes = Set.copyOf(requireNonNull(classDataTypes, "classDataTypes is null"));
            this.lambdaFactoryRequired = lambdaFactoryRequired;
            this.classInfo = requireNonNull(classInfo, "classInfo is null");
        }

        private byte[] classfile()
        {
            return classfile;
        }

        private Set<ClassDesc> classDataTypes()
        {
            return classDataTypes;
        }

        private boolean lambdaFactoryRequired()
        {
            return lambdaFactoryRequired;
        }

        private CompilationReport.ClassInfo classInfo()
        {
            return classInfo;
        }
    }

    private static void emitClass(
            ClassModel definition,
            ClassBuilder builder,
            BindingCollector bindings,
            LinkageContext linkage,
            Map<HiddenClassLinkage.MethodReference, Integer> lambdaBoundaryPadding)
    {
        builder.withFlags(classAccess(definition.access()))
                .withSuperclass(definition.superClass())
                .withInterfaceSymbols(definition.interfaces());
        definition.signature().ifPresent(signature -> builder.with(SignatureAttribute.of(signature)));
        definition.sourceFile().ifPresent(source -> builder.with(SourceFileAttribute.of(source)));
        if (!definition.visibleAnnotations().isEmpty()) {
            builder.with(RuntimeVisibleAnnotationsAttribute.of(definition.visibleAnnotations()));
        }
        if (!definition.invisibleAnnotations().isEmpty()) {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(definition.invisibleAnnotations()));
        }
        if (definition.kind() == ClassKind.RECORD) {
            builder.with(RecordAttribute.of(definition.recordComponents().stream()
                    .map(ClassCompiler::recordComponent)
                    .toList()));
        }

        for (FieldDefinition field : definition.fields()) {
            builder.withField(field.name(), field.type(), fieldBuilder -> {
                fieldBuilder.withFlags(access(field.access()));
                field.signature().ifPresent(signature -> fieldBuilder.with(SignatureAttribute.of(signature)));
                field.constantValue().ifPresent(value -> fieldBuilder.with(ConstantValueAttribute.of(constantValue(value))));
                if (!field.visibleAnnotations().isEmpty()) {
                    fieldBuilder.with(RuntimeVisibleAnnotationsAttribute.of(field.visibleAnnotations()));
                }
                if (!field.invisibleAnnotations().isEmpty()) {
                    fieldBuilder.with(RuntimeInvisibleAnnotationsAttribute.of(field.invisibleAnnotations()));
                }
            });
        }

        for (MethodDefinition.Model method : definition.methods()) {
            builder.withMethod(method.name(), method.methodType(), access(method.access()), methodBuilder -> {
                method.signature().ifPresent(signature -> methodBuilder.with(SignatureAttribute.of(signature)));
                if (!method.exceptions().isEmpty()) {
                    methodBuilder.with(ExceptionsAttribute.ofSymbols(method.exceptions()));
                }
                if (!method.visibleAnnotations().isEmpty()) {
                    methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(method.visibleAnnotations()));
                }
                if (!method.invisibleAnnotations().isEmpty()) {
                    methodBuilder.with(RuntimeInvisibleAnnotationsAttribute.of(method.invisibleAnnotations()));
                }
                if (!method.parameterMetadata().isEmpty()) {
                    methodBuilder.with(MethodParametersAttribute.of(method.parameterMetadata().stream()
                            .map(parameter -> MethodParameterInfo.of(
                                    Optional.of(parameter.parameter().name()),
                                    parameter.access().toArray(AccessFlag[]::new)))
                            .toList()));
                    if (method.parameterMetadata().stream().anyMatch(parameter -> !parameter.visibleAnnotations().isEmpty())) {
                        methodBuilder.with(RuntimeVisibleParameterAnnotationsAttribute.of(method.parameterMetadata().stream()
                                .map(Parameter.Metadata::visibleAnnotations)
                                .toList()));
                    }
                    if (method.parameterMetadata().stream().anyMatch(parameter -> !parameter.invisibleAnnotations().isEmpty())) {
                        methodBuilder.with(RuntimeInvisibleParameterAnnotationsAttribute.of(method.parameterMetadata().stream()
                                .map(Parameter.Metadata::invisibleAnnotations)
                                .toList()));
                    }
                }
                if (method.hasBody()) {
                    int padding = lambdaBoundaryPadding.getOrDefault(new HiddenClassLinkage.MethodReference(method.name(), method.methodType()), 0);
                    methodBuilder.withCode(code -> emitMethod(definition, method, code, bindings, linkage, padding));
                }
            });
        }
    }

    private static RecordComponentInfo recordComponent(RecordComponentDefinition component)
    {
        ArrayList<Attribute<?>> attributes = new ArrayList<>();
        component.signature().ifPresent(signature -> attributes.add(SignatureAttribute.of(signature)));
        if (!component.visibleAnnotations().isEmpty()) {
            attributes.add(RuntimeVisibleAnnotationsAttribute.of(component.visibleAnnotations()));
        }
        if (!component.invisibleAnnotations().isEmpty()) {
            attributes.add(RuntimeInvisibleAnnotationsAttribute.of(component.invisibleAnnotations()));
        }
        return RecordComponentInfo.of(component.name(), component.type(), attributes);
    }

    private static void emitMethod(ClassModel definition, MethodDefinition.Model method, CodeBuilder code, BindingCollector bindings, LinkageContext linkage, int jitBoundaryPadding)
    {
        // There is no public VM annotation for retaining a hidden lambda implementation as an
        // ordinary compilation boundary. Add only the measured padding needed to cross the
        // observed hot-call inline threshold; the separately compiled implementation optimizes
        // these harmless stack operations away.
        for (int emittedBytes = 0; emittedBytes < jitBoundaryPadding; emittedBytes += 2) {
            code.loadConstant(0).pop();
        }
        EmitContext context = new EmitContext(definition, method, code, bindings, linkage);
        emitBlock(method.body(), context, false);
        if (method.isClassInitializer()) {
            code.return_();
        }
    }

    private static void emitBlock(CodeBlock block, EmitContext context, boolean nested)
    {
        if (nested) {
            context.enterScope();
        }
        context.enterBlock(block);
        try {
            boolean[] aliasCopyDeclarations = aliasCopyDeclarations(block.statements());
            for (int index = 0; index < block.statements().size(); index++) {
                Statement statement = block.statements().get(index);
                if (statement instanceof Statements.InitializedDeclaration declaration &&
                        declaration.initializer() instanceof LocalValue source &&
                        aliasCopyDeclarations.length != 0 &&
                        aliasCopyDeclarations[index]) {
                    context.alias(declaration.variable(), source);
                    context.declare(declaration.variable());
                }
                else {
                    emitStatement(statement, context);
                }
            }
        }
        finally {
            context.exitBlock();
            if (nested) {
                context.exitScope();
            }
        }
    }

    private static boolean[] aliasCopyDeclarations(List<Statement> statements)
    {
        boolean hasCandidate = false;
        for (Statement statement : statements) {
            if (statement instanceof Statements.InitializedDeclaration declaration && declaration.initializer() instanceof LocalValue) {
                hasCandidate = true;
                break;
            }
        }
        if (!hasCandidate) {
            return NO_ALIAS_COPY_DECLARATIONS;
        }
        boolean[] aliases = new boolean[statements.size()];
        Set<Variable> writtenLater = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = statements.size() - 1; index >= 0; index--) {
            Statement statement = statements.get(index);
            if (statement instanceof Statements.InitializedDeclaration declaration &&
                    declaration.initializer() instanceof LocalValue source) {
                aliases[index] = declaration.variable().type().equals(source.type()) &&
                        !writtenLater.contains(declaration.variable()) &&
                        (!(source instanceof Variable sourceVariable) || !writtenLater.contains(sourceVariable));
            }
            visitVariableWrites(statement, variable -> {
                writtenLater.add(variable);
                return false;
            });
        }
        return aliases;
    }

    private static void emitStatement(Statement statement, EmitContext context)
    {
        switch (statement) {
            case BytecodeExpression expression -> emitExpressionStatement(expression, context);
            case Statements.ConstructorInvocation invocation -> emitConstructorInvocation(invocation, context);
            case Statements.Declaration declaration -> context.declare(declaration.variable());
            case Statements.Expression expression -> emitExpressionStatement(expression.expression(), context);
            case Statements.InitializedDeclaration declaration -> {
                context.declare(declaration.variable());
                emitExpressionInto(declaration.initializer(), declaration.variable(), context);
            }
            case Statements.Comment _ -> {}
            case Statements.LabelBinding binding -> context.code().labelBinding(context.labelTarget(binding.label()).label());
            case Statements.Jump jump -> {
                LabelTarget target = context.labelTarget(jump.target());
                emitFinalizers(context, target.finalizerDepth());
                context.code().goto_(target.label());
            }
            case CodeBlock block -> emitBlock(block, context, true);
            case IfStatement ifStatement -> emitIf(ifStatement, context);
            case ForLoop forLoop -> emitFor(forLoop, context);
            case WhileLoop whileLoop -> emitWhile(whileLoop, context);
            case DoWhileLoop doWhileLoop -> emitDoWhile(doWhileLoop, context);
            case LoopJump jump -> emitLoopJump(jump, context);
            case SwitchStatement switchStatement -> emitSwitch(switchStatement, context);
            case TryCatch tryCatch -> emitTryCatch(tryCatch, context);
        }
    }

    private static void emitConstructorInvocation(Statements.ConstructorInvocation invocation, EmitContext context)
    {
        context.load(context.method().thisVariable());
        emitArguments(invocation.arguments(), invocation.constructorType(), context);
        ClassDesc owner = switch (invocation.target()) {
            case SUPER -> context.definition().superClass();
            case THIS -> context.definition().type();
        };
        context.code().invokespecial(owner, "<init>", invocation.constructorType());
    }

    private static void emitExpressionStatement(BytecodeExpression expression, EmitContext context)
    {
        emitExpression(expression, context);
        if (!expression.type().equals(CD_void)) {
            if (kind(expression.type()).slotSize() == 2) {
                context.code().pop2();
            }
            else {
                context.code().pop();
            }
        }
    }

    private static void emitIf(IfStatement statement, EmitContext context)
    {
        Label falseLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        emitBranch(statement.condition(), false, falseLabel, context);
        emitBlock(statement.ifTrue(), context, true);
        if (!statement.ifFalse().isEmpty()) {
            context.code().goto_(end);
        }
        context.code().labelBinding(falseLabel);
        if (!statement.ifFalse().isEmpty()) {
            emitBlock(statement.ifFalse(), context, true);
            context.code().labelBinding(end);
        }
        context.code().nop();
    }

    private static void emitFor(ForLoop loop, EmitContext context)
    {
        context.enterScope();
        try {
            emitBlock(loop.initializer(), context, false);
            Label condition = context.code().newBoundLabel();
            Label update = context.code().newLabel();
            Label end = context.code().newLabel();
            context.enterLoop(loop.target(), end, update);
            try {
                emitBranch(loop.condition(), false, end, context);
                emitBlock(loop.body(), context, true);
                context.code().labelBinding(update);
                emitBlock(loop.update(), context, true);
                context.code().goto_(condition).labelBinding(end);
            }
            finally {
                context.exitLoop(loop.target());
            }
        }
        finally {
            context.exitScope();
        }
    }

    private static void emitWhile(WhileLoop loop, EmitContext context)
    {
        Label condition = context.code().newBoundLabel();
        Label end = context.code().newLabel();
        context.enterLoop(loop.target(), end, condition);
        try {
            emitBranch(loop.condition(), false, end, context);
            emitBlock(loop.body(), context, true);
            context.code().goto_(condition).labelBinding(end);
        }
        finally {
            context.exitLoop(loop.target());
        }
    }

    private static void emitDoWhile(DoWhileLoop loop, EmitContext context)
    {
        Label start = context.code().newBoundLabel();
        Label condition = context.code().newLabel();
        Label end = context.code().newLabel();
        context.enterLoop(loop.target(), end, condition);
        try {
            emitBlock(loop.body(), context, true);
            context.code().labelBinding(condition);
            emitBranch(loop.condition(), true, start, context);
            context.code().labelBinding(end);
        }
        finally {
            context.exitLoop(loop.target());
        }
    }

    private static void emitLoopJump(LoopJump jump, EmitContext context)
    {
        LoopLabels labels = context.loop(jump.target());
        emitFinalizers(context, labels.finalizerDepth());
        context.code().goto_(jump.kind() == LoopJump.Kind.BREAK ? labels.breakTarget() : labels.continueTarget());
    }

    private static void emitSwitch(SwitchStatement statement, EmitContext context)
    {
        emitExpression(statement.expression(), context);
        Label defaultLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        Map<Integer, Label> labels = new LinkedHashMap<>();
        List<SwitchCase> cases = statement.cases().stream()
                .map(caseValue -> {
                    Label label = context.code().newLabel();
                    labels.put(caseValue.key(), label);
                    return SwitchCase.of(caseValue.key(), label);
                })
                .toList();
        context.code().lookupswitch(defaultLabel, cases);
        for (SwitchStatement.Case caseValue : statement.cases()) {
            context.code().labelBinding(labels.get(caseValue.key()));
            emitBlock(caseValue.body(), context, true);
            context.code().goto_(end);
        }
        context.code().labelBinding(defaultLabel);
        emitBlock(statement.defaultCase(), context, true);
        context.code().labelBinding(end).nop();
    }

    private static void emitTryCatch(TryCatch statement, EmitContext context)
    {
        Label end = context.code().newLabel();
        CodeBlock finallyBlock = statement.finallyBlock().orElse(null);
        ProtectedRegion tryRegion = new ProtectedRegion(context.code());

        if (finallyBlock != null) {
            context.pushFinalizer(finallyBlock, tryRegion);
        }
        emitBlock(statement.tryBlock(), context, true);
        if (finallyBlock != null) {
            context.popFinalizer(finallyBlock);
        }
        tryRegion.finish();
        if (finallyBlock != null) {
            emitBlock(finallyBlock, context, true);
        }
        context.code().goto_(end);

        List<ProtectedRegion> catchRegions = new ArrayList<>();
        for (TryCatch.CatchClause catchClause : statement.catches()) {
            Label handler = context.code().newLabel();
            tryRegion.ranges().forEach(range -> context.code().exceptionCatch(range.start(), range.end(), handler, catchClause.exceptionType()));
            context.code().labelBinding(handler);
            context.enterScope();
            try {
                context.declare(catchClause.variable());
                context.store(catchClause.variable());
                ProtectedRegion catchRegion = new ProtectedRegion(context.code());
                if (finallyBlock != null) {
                    context.pushFinalizer(finallyBlock, catchRegion);
                }
                emitBlock(catchClause.body(), context, false);
                if (finallyBlock != null) {
                    context.popFinalizer(finallyBlock);
                }
                catchRegion.finish();
                catchRegions.add(catchRegion);
                if (finallyBlock != null) {
                    emitBlock(finallyBlock, context, true);
                }
                context.code().goto_(end);
            }
            finally {
                context.exitScope();
            }
        }

        if (finallyBlock != null) {
            Label finallyHandler = context.code().newLabel();
            tryRegion.ranges().forEach(range -> context.code().exceptionCatchAll(range.start(), range.end(), finallyHandler));
            catchRegions.stream()
                    .flatMap(region -> region.ranges().stream())
                    .forEach(range -> context.code().exceptionCatchAll(range.start(), range.end(), finallyHandler));
            context.code().labelBinding(finallyHandler);
            int throwableSlot = context.allocateTemporary(REFERENCE);
            context.code().astore(throwableSlot);
            emitBlock(finallyBlock, context, true);
            context.code().aload(throwableSlot).athrow();
            context.releaseTemporary(REFERENCE, throwableSlot);
        }
        context.code().labelBinding(end).nop();
    }

    private static void emitFinalizers(EmitContext context, int targetDepth)
    {
        int originalDepth = context.finalizerDepth();
        List<ProtectedRegion> suspendedRegions = new ArrayList<>();
        for (int index = originalDepth - 1; index >= targetDepth; index--) {
            FinalizerFrame frame = context.finalizer(index);
            frame.region().suspend();
            suspendedRegions.add(frame.region());
            context.setFinalizerDepth(index);
            emitBlock(frame.block(), context, true);
        }
        suspendedRegions.reversed().forEach(ProtectedRegion::resume);
        context.setFinalizerDepth(originalDepth);
    }

    private static void emitExpression(BytecodeExpression expression, EmitContext context)
    {
        switch (expression) {
            case LocalValue local -> context.load(local);
            case CoreExpression core -> emitNode(core.node(), context);
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                context.enterScope();
                try {
                    if (canEmitSyntheticValue(plan)) {
                        emitValueBlock(plan.setup(), (Variable) plan.value(), context, false);
                    }
                    else {
                        emitBlock(plan.setup(), context, false);
                        emitExpression(plan.value(), context);
                    }
                }
                finally {
                    context.exitScope();
                }
            }
        }
    }

    private static void emitNode(ExpressionNode node, EmitContext context)
    {
        switch (node) {
            case ExpressionNode.Constant constant -> emitConstant(constant, context);
            case ExpressionNode.Binary binary -> emitBinary(binary, context);
            case ExpressionNode.Unary unary -> emitUnary(unary, context);
            case ExpressionNode.Cast cast -> emitCast(cast, context);
            case ExpressionNode.InstanceOf instanceOf -> {
                emitExpression(instanceOf.value(), context);
                context.code().instanceOf(instanceOf.testType());
            }
            case ExpressionNode.InlineIf inlineIf -> emitInlineIf(inlineIf, context);
            case ExpressionNode.ArrayLength arrayLength -> {
                emitExpression(arrayLength.array(), context);
                context.code().arraylength();
            }
            case ExpressionNode.ArrayGet arrayGet -> {
                emitExpression(arrayGet.array(), context);
                emitExpression(arrayGet.index(), context);
                context.code().arrayLoad(arrayKind(arrayGet.type()));
            }
            case ExpressionNode.ArraySet arraySet -> {
                emitExpression(arraySet.array(), context);
                emitExpression(arraySet.index(), context);
                emitExpressionAs(arraySet.value(), arraySet.array().type().componentType(), context);
                context.code().arrayStore(arrayKind(arraySet.array().type().componentType()));
            }
            case ExpressionNode.NewArray newArray -> emitNewArray(newArray, context);
            case ExpressionNode.FieldGet fieldGet -> emitFieldGet(fieldGet, context);
            case ExpressionNode.FieldSet fieldSet -> emitFieldSet(fieldSet, context);
            case ExpressionNode.Invoke invoke -> emitInvoke(invoke, context);
            case ExpressionNode.NewInstance newInstance -> emitNewInstance(newInstance, context);
            case ExpressionNode.DynamicConstant dynamicConstant -> context.code().ldc(dynamicConstant.constant());
            case ExpressionNode.BoundConstant boundConstant -> {
                int index = context.bindings().bind(boundConstant.value());
                DynamicConstantDesc<?> constant = DynamicConstantDesc.ofNamed(
                        BootstrapDescriptors.bindingConstant(),
                        "binding",
                        boundConstant.type(),
                        index);
                context.code().ldc(constant);
            }
            case ExpressionNode.BoundMethodHandleInvocation invocation -> emitBoundMethodHandle(invocation, context);
            case ExpressionNode.LinkedMethodInvocation invocation -> emitLinkedMethod(invocation, context);
            case ExpressionNode.InvokeDynamic invokeDynamic -> {
                DynamicCallSiteDesc callSite = invokeDynamic.callSite();
                if (context.linkage().hiddenClass()) {
                    DynamicCallSiteDesc rewritten = HiddenClassLinkage.rewrite(callSite, context.definition().type());
                    if (!rewritten.equals(callSite)) {
                        context.bindings().requireLambdaFactory();
                    }
                    callSite = rewritten;
                }
                emitArguments(invokeDynamic.arguments(), callSite.invocationType(), context);
                context.code().invokedynamic(callSite);
            }
            case ExpressionNode.SetVariable setVariable -> {
                emitExpressionInto(setVariable.value(), setVariable.variable(), context);
            }
            case ExpressionNode.Increment increment -> context.increment(increment.variable());
            case ExpressionNode.Adapter adapter -> emitAdapter(adapter, context);
        }
    }

    private static void emitBoundMethodHandle(ExpressionNode.BoundMethodHandleInvocation invocation, EmitContext context)
    {
        CompilationTarget.AdaptedMethodHandle adapted = context.linkage().adapt(
                invocation.handle(),
                invocation.arguments().stream().map(BytecodeExpression::type).toList(),
                context.definition(),
                "method %s%s: %s".formatted(
                        context.method().name(),
                        context.method().methodType().descriptorString(),
                        invocation));
        int index = context.bindings().bind(adapted.handle(), invocation.handle(), adapted.type());
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
                BootstrapDescriptors.bindingCallSite(),
                "binding",
                DescriptorUtils.methodType(adapted.type()),
                index);
        emitArguments(invocation.arguments(), DescriptorUtils.methodType(adapted.type()), context);
        context.code().invokedynamic(callSite);
    }

    private static void emitExpressionInto(BytecodeExpression expression, Variable target, EmitContext context)
    {
        emitExpressionAs(expression, target.type(), context);
        context.store(target);
    }

    private static boolean canEmitSyntheticValue(ExpressionPlan plan)
    {
        if (!(plan.value() instanceof Variable result) ||
                !result.owner().equals(plan.setup().scope()) ||
                plan.setup().statements().stream().noneMatch(statement -> statement instanceof Statements.Declaration declaration && declaration.variable() == result)) {
            return false;
        }
        return terminallyAssigns(plan.setup(), result);
    }

    private static void emitValueBlock(CodeBlock block, Variable result, EmitContext context, boolean nested)
    {
        if (nested) {
            context.enterScope();
        }
        context.enterBlock(block);
        try {
            for (int index = 0; index < block.statements().size() - 1; index++) {
                Statement statement = block.statements().get(index);
                if (!(statement instanceof Statements.Declaration declaration && declaration.variable() == result)) {
                    emitStatement(statement, context);
                }
            }
            emitTerminalValue(block.statements().getLast(), result, context);
        }
        finally {
            context.exitBlock();
            if (nested) {
                context.exitScope();
            }
        }
    }

    private static void emitTerminalValue(Statement statement, Variable result, EmitContext context)
    {
        switch (statement) {
            case BytecodeExpression expression -> emitTerminalValue(expression, result, context);
            case Statements.Expression expression -> emitTerminalValue(expression.expression(), result, context);
            case CodeBlock block -> emitValueBlock(block, result, context, true);
            case IfStatement ifStatement -> {
                Label falseLabel = context.code().newLabel();
                Label end = context.code().newLabel();
                emitBranch(ifStatement.condition(), false, falseLabel, context);
                emitValueBlock(ifStatement.ifTrue(), result, context, true);
                context.code().goto_(end).labelBinding(falseLabel);
                emitValueBlock(ifStatement.ifFalse(), result, context, true);
                context.code().labelBinding(end);
            }
            default -> throw new IllegalArgumentException("Statement does not terminally assign " + result.name() + ": " + statement);
        }
    }

    private static void emitTerminalValue(BytecodeExpression expression, Variable result, EmitContext context)
    {
        ExpressionNode.SetVariable setVariable = (ExpressionNode.SetVariable) ((CoreExpression) expression).node();
        if (setVariable.variable() != result) {
            throw new IllegalArgumentException("Expression does not assign " + result.name() + ": " + expression);
        }
        emitExpressionAs(setVariable.value(), result.type(), context);
    }

    private static boolean terminallyAssigns(CodeBlock block, Variable variable)
    {
        if (block.statements().isEmpty()) {
            return false;
        }
        List<Statement> prefix = block.statements().subList(0, block.statements().size() - 1);
        if (prefix.stream().anyMatch(statement -> writesVariable(statement, variable))) {
            return false;
        }
        return terminallyAssigns(block.statements().getLast(), variable);
    }

    private static boolean terminallyAssigns(Statement statement, Variable variable)
    {
        return switch (statement) {
            case BytecodeExpression expression -> terminallyAssigns(expression, variable);
            case Statements.Expression expression -> terminallyAssigns(expression.expression(), variable);
            case CodeBlock block -> terminallyAssigns(block, variable);
            case IfStatement ifStatement -> ExpressionPlanner.locals(ifStatement.condition()).stream().noneMatch(local -> local == variable) &&
                    !writesVariable(ifStatement.condition(), variable) &&
                    terminallyAssigns(ifStatement.ifTrue(), variable) &&
                    terminallyAssigns(ifStatement.ifFalse(), variable);
            default -> false;
        };
    }

    private static boolean terminallyAssigns(BytecodeExpression expression, Variable variable)
    {
        if (!(expression instanceof CoreExpression core) ||
                !(core.node() instanceof ExpressionNode.SetVariable setVariable) ||
                setVariable.variable() != variable) {
            return false;
        }
        return ExpressionPlanner.locals(setVariable.value()).stream().noneMatch(local -> local == variable) &&
                !writesVariable(setVariable.value(), variable);
    }

    private static boolean writesVariable(Statement statement, Variable variable)
    {
        return visitVariableWrites(statement, written -> written == variable);
    }

    private static boolean writesVariable(BytecodeExpression expression, Variable variable)
    {
        return visitVariableWrites(expression, written -> written == variable);
    }

    private static boolean visitVariableWrites(Statement statement, Predicate<Variable> visitor)
    {
        return switch (statement) {
            case BytecodeExpression expression -> visitVariableWrites(expression, visitor);
            case Statements.Expression expression -> visitVariableWrites(expression.expression(), visitor);
            case Statements.InitializedDeclaration declaration -> visitVariableWrites(declaration.initializer(), visitor);
            case CodeBlock block -> visitVariableWrites(block, visitor);
            case IfStatement ifStatement -> visitVariableWrites(ifStatement.condition(), visitor) ||
                    visitVariableWrites(ifStatement.ifTrue(), visitor) ||
                    visitVariableWrites(ifStatement.ifFalse(), visitor);
            case ForLoop loop -> visitVariableWrites(loop.initializer(), visitor) ||
                    visitVariableWrites(loop.condition(), visitor) ||
                    visitVariableWrites(loop.update(), visitor) ||
                    visitVariableWrites(loop.body(), visitor);
            case WhileLoop loop -> visitVariableWrites(loop.condition(), visitor) || visitVariableWrites(loop.body(), visitor);
            case DoWhileLoop loop -> visitVariableWrites(loop.body(), visitor) || visitVariableWrites(loop.condition(), visitor);
            case SwitchStatement switchStatement -> visitVariableWrites(switchStatement.expression(), visitor) ||
                    switchStatement.cases().stream().anyMatch(caseValue -> visitVariableWrites(caseValue.body(), visitor)) ||
                    visitVariableWrites(switchStatement.defaultCase(), visitor);
            case TryCatch tryCatch -> visitVariableWrites(tryCatch.tryBlock(), visitor) ||
                    tryCatch.catches().stream().anyMatch(catchClause -> visitVariableWrites(catchClause.body(), visitor)) ||
                    tryCatch.finallyBlock().map(block -> visitVariableWrites(block, visitor)).orElse(false);
            case Statements.ConstructorInvocation invocation -> invocation.arguments().stream().anyMatch(argument -> visitVariableWrites(argument, visitor));
            case Statements.Declaration _,
                 Statements.Comment _,
                 Statements.Jump _,
                 Statements.LabelBinding _,
                 LoopJump _ -> false;
        };
    }

    private static boolean visitVariableWrites(CodeBlock block, Predicate<Variable> visitor)
    {
        return block.statements().stream().anyMatch(statement -> visitVariableWrites(statement, visitor));
    }

    private static boolean visitVariableWrites(BytecodeExpression expression, Predicate<Variable> visitor)
    {
        return switch (expression) {
            case LocalValue _ -> false;
            case SyntheticExpression synthetic -> {
                ExpressionPlan plan = synthetic.expansion(ExpansionContext.INSTANCE);
                yield visitVariableWrites(plan.setup(), visitor) || visitVariableWrites(plan.value(), visitor);
            }
            case CoreExpression core -> switch (core.node()) {
                case ExpressionNode.SetVariable setVariable -> visitor.test(setVariable.variable()) || visitVariableWrites(setVariable.value(), visitor);
                case ExpressionNode.Increment increment -> visitor.test(increment.variable());
                default -> core.node().children().stream().anyMatch(child -> visitVariableWrites(child, visitor));
            };
        };
    }

    private static void emitLinkedMethod(ExpressionNode.LinkedMethodInvocation invocation, EmitContext context)
    {
        int index = context.bindings().bind(invocation.method());
        DynamicConstantDesc<?> constant = DynamicConstantDesc.ofNamed(
                BootstrapDescriptors.bindingConstant(),
                "binding",
                ConstantDescs.CD_MethodHandle,
                index);
        context.code().ldc(constant);
        emitArguments(invocation.arguments(), invocation.method().type(), context);
        context.code().invokevirtual(
                ConstantDescs.CD_MethodHandle,
                "invokeExact",
                invocation.method().type());
    }

    private static void emitConstant(ExpressionNode.Constant constant, EmitContext context)
    {
        CodeBuilder code = context.code();
        if (constant.value() == null) {
            if (constant.type().equals(CD_void)) {
                emitFinalizers(context, 0);
                code.return_();
            }
            else {
                code.aconst_null();
            }
            return;
        }
        switch (constant.value()) {
            case Integer value -> code.loadConstant(value);
            case Boolean value -> code.loadConstant(value ? 1 : 0);
            case Long value -> code.loadConstant(value);
            case Float value -> code.loadConstant(value);
            case Double value -> code.loadConstant(value);
            case String value -> code.ldc(code.constantPool().stringEntry(value));
            case ClassDesc value -> code.loadConstant(value);
            default -> throw new CompilationException("Unsupported constant: " + constant.value());
        }
    }

    private static void emitBinary(ExpressionNode.Binary binary, EmitContext context)
    {
        switch (binary.operator()) {
            case "&&" -> emitLogicalAnd(binary, context);
            case "||" -> emitLogicalOr(binary, context);
            case "<", "<=", ">", ">=", "==", "!=" -> emitComparison(binary, context);
            default -> {
                emitExpression(binary.left(), context);
                emitExpression(binary.right(), context);
                emitBinaryInstruction(binary.operator(), kind(binary.left().type()), context.code());
            }
        }
    }

    private static void emitBinaryInstruction(String operator, TypeKind kind, CodeBuilder code)
    {
        switch (operator) {
            case "+" -> arithmetic(kind, code::iadd, code::ladd, code::fadd, code::dadd);
            case "-" -> arithmetic(kind, code::isub, code::lsub, code::fsub, code::dsub);
            case "*" -> arithmetic(kind, code::imul, code::lmul, code::fmul, code::dmul);
            case "/" -> arithmetic(kind, code::idiv, code::ldiv, code::fdiv, code::ddiv);
            case "%" -> arithmetic(kind, code::irem, code::lrem, code::frem, code::drem);
            case "&" -> integral(kind, code::iand, code::land);
            case "|" -> integral(kind, code::ior, code::lor);
            case "^" -> integral(kind, code::ixor, code::lxor);
            case "<<" -> integral(kind, code::ishl, code::lshl);
            case ">>" -> integral(kind, code::ishr, code::lshr);
            case ">>>" -> integral(kind, code::iushr, code::lushr);
            default -> throw new CompilationException("Unsupported binary operator: " + operator);
        }
    }

    private static void emitUnary(ExpressionNode.Unary unary, EmitContext context)
    {
        if (unary.prefix().equals("!")) {
            emitBooleanResult(context, trueLabel -> {
                emitExpression(unary.value(), context);
                context.code().ifeq(trueLabel);
            });
            return;
        }
        emitExpression(unary.value(), context);
        switch (kind(unary.type())) {
            case INT -> context.code().ineg();
            case LONG -> context.code().lneg();
            case FLOAT -> context.code().fneg();
            case DOUBLE -> context.code().dneg();
            case BOOLEAN, BYTE, CHAR, SHORT, REFERENCE, VOID -> throw new CompilationException("Unsupported negate type: " + unary.type());
        }
    }

    private static void emitCast(ExpressionNode.Cast cast, EmitContext context)
    {
        ClassDesc sourceType = cast.value().type();
        ClassDesc targetType = cast.type();
        emitExpression(cast.value(), context);
        if (!sourceType.isPrimitive() && !targetType.isPrimitive()) {
            context.code().checkcast(cast.type());
        }
        else if (sourceType.isPrimitive() && !targetType.isPrimitive()) {
            ClassDesc wrapper = DescriptorUtils.boxedType(sourceType);
            emitNarrowing(sourceType, context.code());
            context.code().invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, sourceType), false);
        }
        else if (!sourceType.isPrimitive()) {
            ClassDesc wrapper = DescriptorUtils.boxedType(targetType);
            context.code()
                    .checkcast(wrapper)
                    .invokevirtual(wrapper, targetType.displayName() + "Value", MethodTypeDesc.of(targetType));
        }
        else {
            TypeKind source = kind(sourceType);
            TypeKind target = kind(targetType);
            if (source != target) {
                context.code().conversion(source, target);
            }
            if (targetType.equals(ConstantDescs.CD_byte)) {
                context.code().i2b();
            }
            else if (targetType.equals(ConstantDescs.CD_char)) {
                context.code().i2c();
            }
            else if (targetType.equals(ConstantDescs.CD_short)) {
                context.code().i2s();
            }
        }
    }

    private static void emitInlineIf(ExpressionNode.InlineIf inlineIf, EmitContext context)
    {
        Label falseLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        emitBranch(inlineIf.condition(), false, falseLabel, context);
        emitExpression(inlineIf.ifTrue(), context);
        context.code().goto_(end).labelBinding(falseLabel);
        emitExpression(inlineIf.ifFalse(), context);
        context.code().labelBinding(end);
    }

    private static void emitNewArray(ExpressionNode.NewArray newArray, EmitContext context)
    {
        ClassDesc component = newArray.type().componentType();
        if (newArray.length() != null) {
            emitExpression(newArray.length(), context);
            emitArrayAllocation(component, context.code());
            return;
        }
        context.code().loadConstant(newArray.elements().size());
        emitArrayAllocation(component, context.code());
        for (int index = 0; index < newArray.elements().size(); index++) {
            context.code().dup().loadConstant(index);
            emitExpressionAs(newArray.elements().get(index), component, context);
            context.code().arrayStore(arrayKind(component));
        }
    }

    private static void emitArrayAllocation(ClassDesc component, CodeBuilder code)
    {
        if (component.isPrimitive()) {
            code.newarray(arrayKind(component));
        }
        else {
            code.anewarray(component);
        }
    }

    private static void emitFieldGet(ExpressionNode.FieldGet field, EmitContext context)
    {
        if (field.isStatic()) {
            context.code().getstatic(field.owner(), field.name(), field.type());
        }
        else {
            emitExpression(field.target(), context);
            context.code().getfield(field.owner(), field.name(), field.type());
        }
    }

    private static void emitFieldSet(ExpressionNode.FieldSet field, EmitContext context)
    {
        if (field.isStatic()) {
            emitExpressionAs(field.value(), field.fieldType(), context);
            context.code().putstatic(field.owner(), field.name(), field.fieldType());
        }
        else {
            emitExpression(field.target(), context);
            emitExpressionAs(field.value(), field.fieldType(), context);
            context.code().putfield(field.owner(), field.name(), field.fieldType());
        }
    }

    private static void emitInvoke(ExpressionNode.Invoke invoke, EmitContext context)
    {
        if (invoke.kind() != ExpressionNode.InvocationKind.STATIC) {
            emitExpression(invoke.target(), context);
        }
        emitArguments(invoke.arguments(), invoke.methodType(), context);
        switch (invoke.kind()) {
            case STATIC -> context.code().invokestatic(invoke.owner(), invoke.name(), invoke.methodType(), context.linkage().isInterface(invoke.owner()));
            case VIRTUAL_OR_INTERFACE -> {
                if (context.linkage().isInterface(invoke.owner())) {
                    context.code().invokeinterface(invoke.owner(), invoke.name(), invoke.methodType());
                }
                else {
                    context.code().invokevirtual(invoke.owner(), invoke.name(), invoke.methodType());
                }
            }
            case VIRTUAL -> context.code().invokevirtual(invoke.owner(), invoke.name(), invoke.methodType());
            case INTERFACE -> context.code().invokeinterface(invoke.owner(), invoke.name(), invoke.methodType());
            case SPECIAL -> context.code().invokespecial(invoke.owner(), invoke.name(), invoke.methodType(), context.linkage().isInterface(invoke.owner()));
        }
    }

    private static void emitNewInstance(ExpressionNode.NewInstance expression, EmitContext context)
    {
        context.code().new_(expression.type()).dup();
        emitArguments(expression.arguments(), expression.constructorType(), context);
        context.code().invokespecial(expression.type(), "<init>", expression.constructorType());
    }

    private static void emitAdapter(ExpressionNode.Adapter adapter, EmitContext context)
    {
        switch (adapter.keyword()) {
            case "" -> {
                emitExpression(adapter.value(), context);
                if (!adapter.value().type().equals(CD_void)) {
                    if (kind(adapter.value().type()).slotSize() == 2) {
                        context.code().pop2();
                    }
                    else {
                        context.code().pop();
                    }
                }
            }
            case "return" -> {
                emitExpressionAs(adapter.value(), context.method().methodType().returnType(), context);
                TypeKind kind = kind(adapter.value().type());
                int returnSlot = context.allocateTemporary(kind);
                context.code().storeLocal(kind, returnSlot);
                emitFinalizers(context, 0);
                context.code().loadLocal(kind, returnSlot);
                context.code().return_(kind(context.method().methodType().returnType()));
                context.releaseTemporary(kind, returnSlot);
            }
            case "throw" -> {
                emitExpression(adapter.value(), context);
                context.code().athrow();
            }
            default -> throw new CompilationException("Unsupported adapter: " + adapter.keyword());
        }
    }

    private static void emitLogicalAnd(ExpressionNode.Binary binary, EmitContext context)
    {
        Label falseLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        emitExpression(binary.left(), context);
        context.code().ifeq(falseLabel);
        emitExpression(binary.right(), context);
        context.code().ifeq(falseLabel).loadConstant(1).goto_(end).labelBinding(falseLabel).loadConstant(0).labelBinding(end);
    }

    private static void emitLogicalOr(ExpressionNode.Binary binary, EmitContext context)
    {
        Label trueLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        emitExpression(binary.left(), context);
        context.code().ifne(trueLabel);
        emitExpression(binary.right(), context);
        context.code().ifne(trueLabel).loadConstant(0).goto_(end).labelBinding(trueLabel).loadConstant(1).labelBinding(end);
    }

    private static void emitComparison(ExpressionNode.Binary binary, EmitContext context)
    {
        emitBooleanResult(context, trueLabel -> {
            emitExpression(binary.left(), context);
            emitExpression(binary.right(), context);
            emitComparisonBranch(binary.operator(), binary.left().type(), trueLabel, context.code());
        });
    }

    private static void emitBranch(BytecodeExpression condition, boolean branchOnTrue, Label target, EmitContext context)
    {
        if (condition instanceof CoreExpression core) {
            switch (core.node()) {
                case ExpressionNode.Constant constant when constant.value() instanceof Boolean value -> {
                    if (value == branchOnTrue) {
                        context.code().goto_(target);
                    }
                    return;
                }
                case ExpressionNode.Unary unary when unary.prefix().equals("!") -> {
                    emitBranch(unary.value(), !branchOnTrue, target, context);
                    return;
                }
                case ExpressionNode.Binary binary when binary.operator().equals("&&") -> {
                    if (!branchOnTrue) {
                        emitBranch(binary.left(), false, target, context);
                        emitBranch(binary.right(), false, target, context);
                    }
                    else {
                        Label falseLabel = context.code().newLabel();
                        emitBranch(binary.left(), false, falseLabel, context);
                        emitBranch(binary.right(), true, target, context);
                        context.code().labelBinding(falseLabel);
                    }
                    return;
                }
                case ExpressionNode.Binary binary when binary.operator().equals("||") -> {
                    if (branchOnTrue) {
                        emitBranch(binary.left(), true, target, context);
                        emitBranch(binary.right(), true, target, context);
                    }
                    else {
                        Label trueLabel = context.code().newLabel();
                        emitBranch(binary.left(), true, trueLabel, context);
                        emitBranch(binary.right(), false, target, context);
                        context.code().labelBinding(trueLabel);
                    }
                    return;
                }
                case ExpressionNode.Binary binary when isComparison(binary.operator()) -> {
                    if (emitNullComparisonBranch(binary, branchOnTrue, target, context)) {
                        return;
                    }
                    emitExpression(binary.left(), context);
                    emitExpression(binary.right(), context);
                    emitComparisonConditionBranch(binary.operator(), binary.left().type(), branchOnTrue, target, context.code());
                    return;
                }
                case ExpressionNode.InstanceOf instanceOf -> {
                    emitExpression(instanceOf.value(), context);
                    context.code().instanceOf(instanceOf.testType());
                    if (branchOnTrue) {
                        context.code().ifne(target);
                    }
                    else {
                        context.code().ifeq(target);
                    }
                    return;
                }
                default -> {}
            }
        }
        emitExpression(condition, context);
        if (branchOnTrue) {
            context.code().ifne(target);
        }
        else {
            context.code().ifeq(target);
        }
    }

    private static boolean emitNullComparisonBranch(ExpressionNode.Binary binary, boolean branchOnTrue, Label target, EmitContext context)
    {
        BytecodeExpression value;
        if (isNullConstant(binary.right())) {
            value = binary.left();
        }
        else if (isNullConstant(binary.left())) {
            value = binary.right();
        }
        else {
            return false;
        }
        emitExpression(value, context);
        boolean branchOnNull = binary.operator().equals("==") == branchOnTrue;
        if (branchOnNull) {
            context.code().ifnull(target);
        }
        else {
            context.code().ifnonnull(target);
        }
        return true;
    }

    private static boolean isNullConstant(BytecodeExpression expression)
    {
        return expression instanceof CoreExpression core &&
                core.node() instanceof ExpressionNode.Constant constant &&
                constant.value() == null;
    }

    private static boolean isComparison(String operator)
    {
        return switch (operator) {
            case "<", "<=", ">", ">=", "==", "!=" -> true;
            default -> false;
        };
    }

    private static String negateComparison(String operator)
    {
        return switch (operator) {
            case "<" -> ">=";
            case "<=" -> ">";
            case ">" -> "<=";
            case ">=" -> "<";
            case "==" -> "!=";
            case "!=" -> "==";
            default -> throw new CompilationException("Unsupported comparison: " + operator);
        };
    }

    private static void emitComparisonBranch(String operator, ClassDesc type, Label target, CodeBuilder code)
    {
        TypeKind kind = kind(type);
        if (kind == REFERENCE) {
            switch (operator) {
                case "==" -> code.if_acmpeq(target);
                case "!=" -> code.if_acmpne(target);
                default -> throw new CompilationException("Ordered reference comparison: " + operator);
            }
            return;
        }
        if (kind == LONG) {
            code.lcmp();
            branchZero(operator, target, code);
            return;
        }
        if (kind == FLOAT || kind == DOUBLE) {
            boolean greaterForm = operator.equals(">") || operator.equals(">=");
            if (kind == FLOAT) {
                if (greaterForm) {
                    code.fcmpl();
                }
                else {
                    code.fcmpg();
                }
            }
            else if (greaterForm) {
                code.dcmpl();
            }
            else {
                code.dcmpg();
            }
            branchZero(operator, target, code);
            return;
        }
        switch (operator) {
            case "<" -> code.if_icmplt(target);
            case "<=" -> code.if_icmple(target);
            case ">" -> code.if_icmpgt(target);
            case ">=" -> code.if_icmpge(target);
            case "==" -> code.if_icmpeq(target);
            case "!=" -> code.if_icmpne(target);
            default -> throw new CompilationException("Unsupported comparison: " + operator);
        }
    }

    private static void emitComparisonConditionBranch(String operator, ClassDesc type, boolean branchOnTrue, Label target, CodeBuilder code)
    {
        TypeKind kind = kind(type);
        if (kind != FLOAT && kind != DOUBLE) {
            emitComparisonBranch(branchOnTrue ? operator : negateComparison(operator), type, target, code);
            return;
        }

        // Preserve the comparison form selected for the authored operator. In particular, NaN must
        // branch to the false path for every ordered comparison; merely negating the operator would
        // also reverse fcmpg/fcmpl (or dcmpg/dcmpl) and produce the wrong result for NaN.
        boolean greaterForm = operator.equals(">") || operator.equals(">=");
        if (kind == FLOAT) {
            if (greaterForm) {
                code.fcmpl();
            }
            else {
                code.fcmpg();
            }
        }
        else if (greaterForm) {
            code.dcmpl();
        }
        else {
            code.dcmpg();
        }
        branchZero(branchOnTrue ? operator : negateComparison(operator), target, code);
    }

    private static void branchZero(String operator, Label target, CodeBuilder code)
    {
        switch (operator) {
            case "<" -> code.iflt(target);
            case "<=" -> code.ifle(target);
            case ">" -> code.ifgt(target);
            case ">=" -> code.ifge(target);
            case "==" -> code.ifeq(target);
            case "!=" -> code.ifne(target);
            default -> throw new CompilationException("Unsupported comparison: " + operator);
        }
    }

    private static void emitBooleanResult(EmitContext context, Consumer<Label> branchToTrue)
    {
        Label trueLabel = context.code().newLabel();
        Label end = context.code().newLabel();
        branchToTrue.accept(trueLabel);
        context.code().loadConstant(0).goto_(end).labelBinding(trueLabel).loadConstant(1).labelBinding(end);
    }

    private static void emitArguments(List<BytecodeExpression> arguments, MethodTypeDesc methodType, EmitContext context)
    {
        for (int index = 0; index < arguments.size(); index++) {
            emitExpressionAs(arguments.get(index), methodType.parameterType(index), context);
        }
    }

    private static void emitExpressionAs(BytecodeExpression expression, ClassDesc targetType, EmitContext context)
    {
        emitExpression(expression, context);
        // Narrow primitives occupy int stack/local values, so normalize whenever a declared type
        // becomes observable to another expression or method.
        emitNarrowing(targetType, context.code());
    }

    private static void emitNarrowing(ClassDesc type, CodeBuilder code)
    {
        if (type.equals(ConstantDescs.CD_byte)) {
            code.i2b();
        }
        else if (type.equals(ConstantDescs.CD_char)) {
            code.i2c();
        }
        else if (type.equals(ConstantDescs.CD_short)) {
            code.i2s();
        }
    }

    private static void arithmetic(TypeKind kind, Runnable intOp, Runnable longOp, Runnable floatOp, Runnable doubleOp)
    {
        switch (kind) {
            case INT -> intOp.run();
            case LONG -> longOp.run();
            case FLOAT -> floatOp.run();
            case DOUBLE -> doubleOp.run();
            case BOOLEAN, BYTE, CHAR, SHORT, REFERENCE, VOID -> throw new CompilationException("Unsupported arithmetic kind: " + kind);
        }
    }

    private static void integral(TypeKind kind, Runnable intOp, Runnable longOp)
    {
        switch (kind) {
            case INT -> intOp.run();
            case LONG -> longOp.run();
            case BOOLEAN, BYTE, CHAR, SHORT, FLOAT, DOUBLE, REFERENCE, VOID -> throw new CompilationException("Unsupported integral kind: " + kind);
        }
    }

    private static TypeKind kind(ClassDesc type)
    {
        return TypeKind.from(type).asLoadable();
    }

    private static TypeKind arrayKind(ClassDesc componentType)
    {
        return TypeKind.from(componentType);
    }

    private static int classAccess(Iterable<AccessFlag> flags)
    {
        int access = access(flags);
        if ((access & INTERFACE.mask()) == 0) {
            access |= SUPER.mask();
        }
        return access;
    }

    private static int access(Iterable<AccessFlag> flags)
    {
        int access = 0;
        for (AccessFlag flag : flags) {
            access |= flag.mask();
        }
        return access;
    }

    private static ConstantDesc constantValue(Object value)
    {
        return switch (value) {
            case Boolean booleanValue -> booleanValue ? 1 : 0;
            case Byte byteValue -> byteValue.intValue();
            case Character character -> (int) character;
            case Short shortValue -> shortValue.intValue();
            case Integer integer -> integer;
            case Long longValue -> longValue;
            case Float floatValue -> floatValue;
            case Double doubleValue -> doubleValue;
            case String string -> string;
            default -> throw new CompilationException("Unsupported field constant: " + value);
        };
    }

    private record LocalBinding(TypeKind kind, int slot) {}

    private record LoopLabels(Label breakTarget, Label continueTarget, int finalizerDepth) {}

    private record LabelTarget(Label label, int finalizerDepth) {}

    private record ProtectedRange(Label start, Label end) {}

    private record FinalizerFrame(CodeBlock block, ProtectedRegion region) {}

    private static final class ProtectedRegion
    {
        private final CodeBuilder code;
        private final ArrayList<ProtectedRange> ranges = new ArrayList<>();
        private Optional<Label> start = Optional.empty();

        private ProtectedRegion(CodeBuilder code)
        {
            this.code = code;
            resume();
        }

        private void suspend()
        {
            if (start.isEmpty()) {
                throw new IllegalStateException("Protected region is already suspended");
            }
            Label end = code.newBoundLabel();
            ranges.add(new ProtectedRange(start.orElseThrow(), end));
            start = Optional.empty();
        }

        private void resume()
        {
            if (start.isPresent()) {
                throw new IllegalStateException("Protected region is already active");
            }
            start = Optional.of(code.newBoundLabel());
            // Ensure every segment is a valid non-empty exception-table range.
            code.nop();
        }

        private void finish()
        {
            suspend();
        }

        private List<ProtectedRange> ranges()
        {
            if (start.isPresent()) {
                throw new IllegalStateException("Protected region is still active");
            }
            return List.copyOf(ranges);
        }
    }

    private static final class BindingCollector
    {
        private final Map<Object, Integer> indexes = new IdentityHashMap<>();
        private final Map<MethodHandle, Map<MethodType, Integer>> adaptedHandleIndexes = new IdentityHashMap<>();
        private final Map<CompiledUnit.LinkedMethod, Integer> linkedMethodIndexes = new HashMap<>();
        private final ArrayList<CompiledUnit.Binding> bindings = new ArrayList<>();
        private boolean lambdaFactoryRequired;

        private void requireLambdaFactory()
        {
            lambdaFactoryRequired = true;
        }

        private boolean lambdaFactoryRequired()
        {
            return lambdaFactoryRequired;
        }

        private int bind(Object value)
        {
            return indexes.computeIfAbsent(requireNonNull(value, "value is null"), _ -> {
                bindings.add(new CompiledUnit.ValueBinding(value));
                return bindings.size() - 1;
            });
        }

        private int bind(CompiledUnit.LinkedMethod method)
        {
            return linkedMethodIndexes.computeIfAbsent(requireNonNull(method, "method is null"), _ -> {
                bindings.add(new CompiledUnit.MethodBinding(method));
                return bindings.size() - 1;
            });
        }

        private int bind(MethodHandle adaptedHandle, MethodHandle originalHandle, MethodType adaptedType)
        {
            Map<MethodType, Integer> indexes = adaptedHandleIndexes.computeIfAbsent(originalHandle, _ -> new HashMap<>());
            return indexes.computeIfAbsent(adaptedType, _ -> bind(adaptedHandle));
        }

        private List<Object> values()
        {
            return bindings.stream()
                    .map(binding -> switch (binding) {
                        case CompiledUnit.ValueBinding value -> value.value();
                        case CompiledUnit.MethodBinding method -> throw new IllegalStateException("Generated method link requires a compiled unit: " + method.method());
                    })
                    .toList();
        }

        private List<CompiledUnit.Binding> bindings()
        {
            return List.copyOf(bindings);
        }
    }

    private static final class EmitContext
    {
        private final ClassModel definition;
        private final MethodDefinition.Model method;
        private final CodeBuilder code;
        private final BindingCollector bindingCollector;
        private final LinkageContext linkage;
        private final Map<LocalValue, LocalBinding> bindings = new IdentityHashMap<>();
        private final Set<LocalValue> predeclaredAliases = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<LocalValue> nonOwningBindings = Collections.newSetFromMap(new IdentityHashMap<>());
        private final ArrayDeque<List<LocalValue>> scopes = new ArrayDeque<>();
        private final Map<Object, LoopLabels> loops = new IdentityHashMap<>();
        private final ArrayDeque<Map<CodeLabel, LabelTarget>> labelScopes = new ArrayDeque<>();
        private final ArrayDeque<Integer> freeOneSlotLocals = new ArrayDeque<>();
        private final ArrayDeque<Integer> freeTwoSlotLocals = new ArrayDeque<>();
        private final ArrayList<FinalizerFrame> finalizers = new ArrayList<>();
        private int finalizerDepth;

        private EmitContext(ClassModel definition, MethodDefinition.Model method, CodeBuilder code, BindingCollector bindings, LinkageContext linkage)
        {
            this.definition = requireNonNull(definition, "definition is null");
            this.method = requireNonNull(method, "method is null");
            this.code = code;
            this.bindingCollector = bindings;
            this.linkage = linkage;
            scopes.push(new ArrayList<>());
            if (!method.isStatic()) {
                bind(method.thisVariable(), new LocalBinding(REFERENCE, code.receiverSlot()));
            }
            for (int index = 0; index < method.parameters().size(); index++) {
                Parameter parameter = method.parameters().get(index);
                bind(parameter, new LocalBinding(kind(parameter.type()), code.parameterSlot(index)));
            }
        }

        private ClassModel definition()
        {
            return definition;
        }

        private MethodDefinition.Model method()
        {
            return method;
        }

        private CodeBuilder code()
        {
            return code;
        }

        private void enterBlock(CodeBlock block)
        {
            Map<CodeLabel, LabelTarget> labels = new IdentityHashMap<>();
            block.labels().forEach(label -> labels.put(label, new LabelTarget(code.newLabel(), finalizerDepth)));
            labelScopes.push(labels);
        }

        private void exitBlock()
        {
            labelScopes.pop();
        }

        private LabelTarget labelTarget(CodeLabel target)
        {
            for (Map<CodeLabel, LabelTarget> labels : labelScopes) {
                LabelTarget labelTarget = labels.get(target);
                if (labelTarget != null) {
                    return labelTarget;
                }
            }
            throw new CompilationException("Label is not in scope: " + target.name());
        }

        private BindingCollector bindings()
        {
            return bindingCollector;
        }

        private LinkageContext linkage()
        {
            return linkage;
        }

        private void enterScope()
        {
            scopes.push(new ArrayList<>());
        }

        private void exitScope()
        {
            for (LocalValue variable : scopes.pop()) {
                LocalBinding binding = bindings.remove(variable);
                predeclaredAliases.remove(variable);
                if (!nonOwningBindings.remove(variable)) {
                    release(binding);
                }
            }
        }

        private void declare(Variable variable)
        {
            if (predeclaredAliases.remove(variable)) {
                return;
            }
            if (bindings.containsKey(variable)) {
                throw new CompilationException("Variable is already bound: " + variable.name());
            }
            TypeKind kind = kind(variable.type());
            bind(variable, new LocalBinding(kind, allocate(kind)));
        }

        private void alias(Variable alias, LocalValue target)
        {
            if (bindings.containsKey(alias)) {
                throw new CompilationException("Variable is already bound: " + alias.name());
            }
            LocalBinding targetBinding = resolve(target);
            if (kind(alias.type()) != targetBinding.kind()) {
                throw new CompilationException("Alias type does not match target: " + alias.name());
            }
            bindings.put(alias, targetBinding);
            scopes.getFirst().add(alias);
            predeclaredAliases.add(alias);
            nonOwningBindings.add(alias);
        }

        private void bind(LocalValue variable, LocalBinding binding)
        {
            bindings.put(variable, binding);
            scopes.getFirst().add(variable);
        }

        private void load(LocalValue variable)
        {
            LocalBinding binding = resolve(variable);
            code.loadLocal(binding.kind(), binding.slot());
        }

        private void store(LocalValue variable)
        {
            LocalBinding binding = resolve(variable);
            code.storeLocal(binding.kind(), binding.slot());
        }

        private void increment(Variable variable)
        {
            LocalBinding binding = resolve(variable);
            code.iinc(binding.slot(), 1);
            if (variable.type().equals(ConstantDescs.CD_byte) ||
                    variable.type().equals(ConstantDescs.CD_char) ||
                    variable.type().equals(ConstantDescs.CD_short)) {
                code.loadLocal(binding.kind(), binding.slot());
                emitNarrowing(variable.type(), code);
                code.storeLocal(binding.kind(), binding.slot());
            }
        }

        private LocalBinding resolve(LocalValue variable)
        {
            LocalBinding binding = bindings.get(variable);
            if (binding == null) {
                throw new CompilationException("Variable is not in scope: " + variable.name());
            }
            return binding;
        }

        private int allocate(TypeKind kind)
        {
            ArrayDeque<Integer> free = kind.slotSize() == 2 ? freeTwoSlotLocals : freeOneSlotLocals;
            return free.isEmpty() ? code.allocateLocal(kind) : free.pop();
        }

        private int allocateTemporary(TypeKind kind)
        {
            return allocate(kind);
        }

        private void releaseTemporary(TypeKind kind, int slot)
        {
            release(new LocalBinding(kind, slot));
        }

        private void release(LocalBinding binding)
        {
            (binding.kind().slotSize() == 2 ? freeTwoSlotLocals : freeOneSlotLocals).push(binding.slot());
        }

        private void enterLoop(Object target, Label breakTarget, Label continueTarget)
        {
            if (loops.put(target, new LoopLabels(breakTarget, continueTarget, finalizerDepth)) != null) {
                throw new CompilationException("Loop target is already active");
            }
        }

        private void exitLoop(Object target)
        {
            loops.remove(target);
        }

        private LoopLabels loop(Object target)
        {
            LoopLabels labels = loops.get(target);
            if (labels == null) {
                throw new CompilationException("Loop jump is outside its target loop");
            }
            return labels;
        }

        private void pushFinalizer(CodeBlock finalizer, ProtectedRegion region)
        {
            if (finalizerDepth != finalizers.size()) {
                throw new IllegalStateException("Cannot push finalizer while finalizers are suspended");
            }
            finalizers.add(new FinalizerFrame(finalizer, region));
            finalizerDepth++;
        }

        private void popFinalizer(CodeBlock finalizer)
        {
            if (finalizerDepth != finalizers.size() || finalizers.removeLast().block() != finalizer) {
                throw new IllegalStateException("Finalizer stack is corrupt");
            }
            finalizerDepth--;
        }

        private int finalizerDepth()
        {
            return finalizerDepth;
        }

        private FinalizerFrame finalizer(int index)
        {
            return finalizers.get(index);
        }

        private void setFinalizerDepth(int depth)
        {
            finalizerDepth = depth;
        }
    }
}

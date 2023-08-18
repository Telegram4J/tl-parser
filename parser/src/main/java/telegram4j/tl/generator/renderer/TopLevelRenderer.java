/*
 * Copyright 2023 Telegram4J
 *
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
package telegram4j.tl.generator.renderer;

import telegram4j.tl.generator.Preconditions;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.PROCESSING;

@SuppressWarnings("unchecked")
public class TopLevelRenderer extends BaseClassRenderer<CharSequence> {

    protected final SortedSet<String> imports = new TreeSet<>(); // qualified name to simple name
    protected final SortedSet<String> staticImports = new TreeSet<>();
    protected final Map<String, String> simpleNames = new HashMap<>();

    protected TopLevelRenderer(ClassRef name, ClassRenderer.Kind kind, CharSink out) {
        super(name, kind, out);
    }

    private void addType0(TypeRef type) {
        if (type instanceof PrimitiveTypeRef || type.equals(name)) return;

        if (type instanceof ArrayRef a) {
            addType0(a.component);
        } else if (type instanceof ClassRef c) {
            String simpleName = c.name;

            if (c.name.equals(name.name) && !c.packageName.equals(name.packageName) ||
                    c.packageName.equals("java.lang") ||
                    c.packageName.isEmpty() ||
                    simpleNames.containsKey(simpleName)) {
                return;
            }

            if (simpleNames.putIfAbsent(simpleName, c.packageName) == null) {
                // TODO handle collisions via enclosing class name
                imports.add(c.qualifiedName());
            }
        } else if (type instanceof ParameterizedTypeRef p) {
            addType0(p.rawType);
            for (TypeRef typeArgument : p.typeArguments) {
                addType0(typeArgument);
            }
        } else if (type instanceof TypeVariableRef t) {
            for (TypeRef bound : t.bounds) {
                addType0(bound);
            }
        } else if (type instanceof WildcardTypeRef w) {
            if (w.upperBound != null) addType0(w.upperBound);
            if (w.lowerBound != null) addType0(w.lowerBound);
        } else if (type instanceof AnnotatedTypeRef a) {
            addType0(a.type);
            for (TypeRef ann : a.annotations) {
                addType0(ann);
            }
        }
    }

    private void resolve(CharSink out, TypeRef type, boolean varargs) {
        String varargsSuffix = varargs ? "..." : "";

        // no qualification is needed
        if (type instanceof PrimitiveTypeRef || type.equals(name)) {
            out.append(type).append(varargsSuffix);
            return;
        }

        if (type instanceof ArrayRef a) {

            resolve(out, a.component, false);

            String arraySuffix = varargs
                    ? "[]".repeat(a.dimensions - 1) + "..."
                    : "[]".repeat(a.dimensions);

            out.append(arraySuffix);
        } else if (type instanceof ClassRef c) {
            String simpleName = c.name;

            if (c.name.equals(name.name) && !c.packageName.equals(name.packageName)) {
                out.append(c.qualifiedName()).append(varargsSuffix);
                return;
            }

            String pckg = simpleNames.get(simpleName);
            if ((c.packageName.equals("java.lang") ||
                c.packageName.isEmpty()) &&
                (pckg == null || pckg.equals(c.packageName))) {
                out.append(simpleName).append(varargsSuffix);
                return;
            }

            if (pckg != null && !pckg.equals(c.packageName)) { // fix name collision
                out.append(c.qualifiedName()).append(varargsSuffix);
                return;
            }

            out.append(simpleName).append(varargsSuffix);
        } else if (type instanceof ParameterizedTypeRef p) {

            resolve(out, p.rawType, false);
            if (!p.typeArguments.isEmpty()) {
                out.append('<');
                for (int i = 0, n = p.typeArguments.size(); i < n; i++) {
                    resolve(out, p.typeArguments.get(i), false);

                    if (i != n - 1) {
                        out.append(", ").lw();
                    }
                }
                out.append('>');
            }

            out.append(varargsSuffix);
        } else if (type instanceof AnnotatedTypeRef a) {

            for (ClassRef annotation : a.annotations) {
                out.append('@');
                resolve(out, annotation, false);
                out.append(' ');
            }

            resolve(out, a.type, varargs);
        } else if (type instanceof TypeVariableRef t) {

            out.append(t.name);
            if (!t.bounds.isEmpty()) {
                out.append('<');
                for (int i = 0, n = t.bounds.size(); i < n; i++) {
                    TypeRef bound = t.bounds.get(i);

                    if (i == 0) {
                        out.append(" extends ");
                    } else {
                        out.append(" & ");
                    }

                    resolve(out, bound, false);
                }
                out.append('>');
            }
        } else if (type instanceof WildcardTypeRef w) {

            if (w.lowerBound != null) {
                out.append("? super ");

                resolve(out, w.lowerBound, false);
            } else if (w.upperBound != null) {
                out.append("? extends ");

                resolve(out, w.upperBound, false);
            } else {
                out.append('?');
            }
        } else {
            throw new IllegalArgumentException("Unknown TypeRef: " + type);
        }
    }

    @Override
    protected void appendType(CharSink out, TypeRef type, boolean vararg) {
        addType0(type);

        resolve(out, type, vararg);
    }

    @Override
    protected void appendType(CharSink out, TypeRef type) {
        addType0(type);

        resolve(out, type, false);
    }

    @Override
    public TopLevelRenderer addStaticImports(String... staticImports) {
        Collections.addAll(this.staticImports, staticImports);
        return this;
    }

    @Override
    public TopLevelRenderer addStaticImport(String staticImport) {
        staticImports.add(staticImport);
        return this;
    }

    // writers

    @Override
    public TopLevelRenderer addTypeVariables(TypeVariableRef first, TypeVariableRef... rest) {
        return (TopLevelRenderer) super.addTypeVariables(first, rest);
    }

    @Override
    public TopLevelRenderer addTypeVariables(Collection<TypeVariableRef> types) {
        return (TopLevelRenderer) super.addTypeVariables(types);
    }

    @Override
    public TopLevelRenderer addAnnotation(AnnotationRenderer renderer) {
        return (TopLevelRenderer) super.addAnnotation(renderer);
    }

    @Override
    public TopLevelRenderer addAnnotation(Class<? extends Annotation> annotation) {
        return (TopLevelRenderer) super.addAnnotation(annotation);
    }

    @Override
    public TopLevelRenderer addAnnotations(Iterable<Class<? extends Annotation>> annotations) {
        return (TopLevelRenderer) super.addAnnotations(annotations);
    }

    @Override
    public TopLevelRenderer addModifiers(Modifier... modifiers) {
        return (TopLevelRenderer) super.addModifiers(modifiers);
    }

    @Override
    public TopLevelRenderer addSuperType(Type type) {
        return (TopLevelRenderer) super.addSuperType(type);
    }

    @Override
    public TopLevelRenderer addInterface(Type type) {
        return (TopLevelRenderer) super.addInterface(type);
    }

    @Override
    public TopLevelRenderer addInterfaces(List<? extends Type> types) {
        return (TopLevelRenderer) super.addInterfaces(types);
    }

    @Override
    public TopLevelRenderer addInterfaces(Type first, Type... rest) {
        return (TopLevelRenderer) super.addInterfaces(first, rest);
    }

    @Override
    public TopLevelRenderer addAttribute(Type type, String name) {
        return (TopLevelRenderer) super.addAttribute(type, name);
    }

    @Override
    public TopLevelRenderer addAttribute(Type type, String name, CharSequence format, Object... args) {
        return (TopLevelRenderer) super.addAttribute(type, name, format, args);
    }

    @Override
    public InitializerRenderer<TopLevelRenderer> addStaticInitializer() {
        Preconditions.requireState(kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM,
                "Static initializers is not allowed in " + kind);
        completeStage(PROCESSING);
        requireStage(PROCESSING);
        return addPending(new InitializerRenderer<>(this, true));
    }

    @Override
    public InitializerRenderer<TopLevelRenderer> addInitializer() {
        Preconditions.requireState(kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM,
                "Static initializers is not allowed in " + kind);
        completeStage(PROCESSING);
        requireStage(PROCESSING);
        return addPending(new InitializerRenderer<>(this, false));
    }

    @Override
    public FieldRenderer<TopLevelRenderer> addField(Type type, String name) {
        return (FieldRenderer<TopLevelRenderer>) super.addField(type, name);
    }

    @Override
    public FieldRenderer<TopLevelRenderer> addField(Type type, String name, Modifier... modifiers) {
        return (FieldRenderer<TopLevelRenderer>) super.addField(type, name, modifiers);
    }

    @Override
    public ExecutableRenderer<TopLevelRenderer> addConstructor() {
        return (ExecutableRenderer<TopLevelRenderer>) super.addConstructor();
    }

    @Override
    public ExecutableRenderer<TopLevelRenderer> addConstructor(Modifier... modifiers) {
        return (ExecutableRenderer<TopLevelRenderer>) super.addConstructor(modifiers);
    }

    @Override
    public MethodRenderer<TopLevelRenderer> addMethod(Type returnType, String name) {
        return (MethodRenderer<TopLevelRenderer>) super.addMethod(returnType, name);
    }

    @Override
    public MethodRenderer<TopLevelRenderer> addMethod(Type returnType, String name, Modifier... modifiers) {
        return (MethodRenderer<TopLevelRenderer>) super.addMethod(returnType, name, modifiers);
    }

    @Override
    public TopLevelRenderer addConstant(CharSequence name, CharSequence format, Object... args) {
        return (TopLevelRenderer) super.addConstant(name, format, args);
    }

    @Override
    public TopLevelRenderer addConstant(CharSequence name) {
        return (TopLevelRenderer) super.addConstant(name);
    }

    public ClassRenderer<TopLevelRenderer> addType(String name, ClassRenderer.Kind kind) {
        completeStage(PROCESSING);
        requireStage(PROCESSING);
        return addPending(new ClassRenderer<>(this.name.nested(name), kind, this, this));
    }

    @Override
    public CharSequence complete() {
        if (stage != Stage.COMPLETE) {
            completePending();
            complete0();

            stage = Stage.COMPLETE;
            postProcess();
        }
        return out.asStringBuilder();
    }

    private void postProcess() {
        StringBuilder header = new StringBuilder();
        if (!name.packageName.isEmpty()) {
            header.append("package ");
            header.append(name.packageName);
            header.append(";\n\n");
        }

        if (!imports.isEmpty()) {
            for (String anImport : imports) {
                header.append("import ");
                header.append(anImport);
                header.append(";\n");
            }
            header.append('\n');
        }

        if (!staticImports.isEmpty()) {
            for (String staticImport : staticImports) {
                header.append("import static ");
                header.append(staticImport);
                header.append(";\n");
            }

            header.append('\n');
        }

        imports.clear();
        staticImports.clear();
        simpleNames.clear();

        out.buf.insert(0, header);
    }
}

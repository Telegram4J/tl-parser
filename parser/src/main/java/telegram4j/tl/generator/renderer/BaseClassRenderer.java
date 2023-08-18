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
import telegram4j.tl.generator.SourceNames;

import javax.lang.model.element.Modifier;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;

import static telegram4j.tl.generator.renderer.CompletableRenderer.Stage.*;

abstract class BaseClassRenderer<P>
        implements CompletableRenderer<P>, AnnotatedRenderer<P> {
    protected static final Stage BEGIN = mandatory(-2, "BEGIN"),
            SUPER_TYPE = optional(2, "SUPER_TYPE"),
            INTERFACES = optional(3, "INTERFACES"),
            PERMITS = optional(4, "PERMITS"),
            CONSTANTS = optional(5, "CONSTANTS");

    public final ClassRef name;
    public final ClassRenderer.Kind kind;

    protected final CharSink out;

    protected CompletableRenderer.Stage stage = BEGIN;
    protected Deque<CompletableRenderer<?>> pending = new LinkedList<>();
    protected CompletableRenderer<?> prev;

    protected BaseClassRenderer(ClassRef name, ClassRenderer.Kind kind, CharSink out) {
        this.name = name;
        this.kind = kind;
        this.out = out;
    }

    protected void complete(BaseCompletableRenderer<?> comp) {
        if (!(prev instanceof SeparableRenderer) ||
            ((SeparableRenderer<?>) prev).separate(comp)) {
            out.ln();
        }

        prev = comp;
        out.appendRaw(comp.out);
    }

    protected void completePending() {
        CompletableRenderer<?> curr, prev = this.prev;
        while ((curr = pending.pollLast()) != null) {
            if (curr.stage() != COMPLETE && (!(curr instanceof SeparableRenderer) ||
                ((SeparableRenderer<?>) curr).separate(prev))) {
                out.ln();
            }

            curr.complete();
            prev = curr;
        }
    }

    protected void completeStage(Stage required) {
        do {
            // [ separate (only in nested classes) ]
            // [ annotations ]
            // [ modifiers ] [keyword & name] <[ type variables ]> [ super type] [ interfaces ]
            // [ processing ]
            if (stage == BEGIN) {
                stage = ANNOTATIONS;
                separate0();
            } else if (stage == ANNOTATIONS || stage == MODIFIERS) {
                stage = required;

                out.append(kind.asKeyword());
                out.append(' ');
                out.append(name.name);
                if (required == PROCESSING) {
                    out.incIndent().append(" {")/*.ln()*/;
                }
            } else if (stage == TYPE_VARIABLES) { // optional
                stage = required;

                out.append('>');

                if (required == PROCESSING) {
                    out.incIndent().append(" {")/*.ln()*/;
                }
            } else if (stage == SUPER_TYPE) { // optional
                stage = required;

                out.incIndent().append(" {")/*.ln()*/;
            } else if (stage == INTERFACES) { // optional
                stage = required;

                if (required != PERMITS)
                    out.incIndent().append(" {")/*.ln()*/;
            } else if (stage == PERMITS) { // optional
                stage = required;

                out.incIndent().append(" {")/*.ln()*/;
            } else if (stage == CONSTANTS) {
                stage = PROCESSING;

                out.append(';').ln();
            }
        } while (stage != required);
    }

    protected <T extends CompletableRenderer<?>> T addPending(T child) {
        pending.addFirst(child);
        return child;
    }

    // stage processing

    protected void requireStage(Stage req) {
        RenderUtils.requireStage(stage, req);
    }

    protected void requireStage(Stage min, Stage max) {
        RenderUtils.requireStage(stage, min, max);
    }

    protected void formatCode(CharSink out, CharSequence s) {
        char c;
        for (int i = 0, n = s.length(); i < n; i++) {
            c = s.charAt(i);
            if (c == '$' && i + 1 < n) {
                c = s.charAt(++i);

                switch (c) {
                    // soft line wrapping
                    case 'W' -> out.lw();
                    // force line wrapping
                    case 'B' -> out.lb();
                    default -> out.append(c);
                }
            } else {
                out.append(c);
            }
        }
    }

    protected void formatCode(CharSink out, CharSequence s, Object... args) {
        int relativeIdx = 0;
        char c, d;
        for (int i = 0, n = s.length(); i < n; i++) {
            c = s.charAt(i);
            if (c == '$' && i + 1 < n) {
                c = s.charAt(++i);
                if (c == '$') { // $$
                    out.append(c);
                    continue;
                }

                if (c == 'W') { // line wrapping (soft)
                    out.lw();
                    continue;
                } else if (c == 'B') { // force
                    out.lb();
                    continue;
                }

                int pos;
                int endPos = i;
                if (c >= '0' && c <= '9') { // indexed arg
                    do {
                        Preconditions.requireArgument(endPos < s.length(), "Unexpected end of format: '" + s + "'");
                        d = s.charAt(endPos++);
                    } while (d >= '0' && d <= '9');

                    endPos--;
                    c = s.charAt(endPos);
                    pos = Integer.parseInt(s, i, endPos, 10) - 1; // 0-based
                } else {
                    pos = relativeIdx++;
                }

                Preconditions.requireArgument(isFormatControl(c), "Unexpected format token: '" + c + "' at position: " + endPos);
                Preconditions.requireArgument(pos < args.length, "Missing format argument #" + pos + ", format specifier: '" + s + "'");

                Object o = args[pos];
                switch (c) {
                    case 'L' -> out.append(o);
                    case 'S' -> appendStringLiteral(out, o);
                    case 'T' -> {
                        Objects.requireNonNull(o);
                        Preconditions.requireArgument(o instanceof Type, () -> "Argument #" + pos + " is not a type: " + o.getClass());
                        appendType(out, TypeRef.of((Type) o));
                    }
                    default -> throw new IllegalArgumentException();
                }

                i = endPos;
            } else {
                out.append(c);
            }
        }
    }

    protected void appendTypeVariable(CharSink out, TypeVariableRef type) {
        out.append(type.name);
        for (int i = 0, n = type.bounds.size(); i < n; i++) {
            TypeRef bound = type.bounds.get(i);

            if (i == 0) {
                out.append(" extends ");
            } else {
                out.append(" & ");
            }
            appendType(out, bound);
        }
    }

    protected void appendModifiers(CharSink out, Iterable<Modifier> modifiers) {
        for (Modifier modifier : modifiers) {
            out.append(modifier).append(' ');
        }
    }

    protected void appendModifiers(CharSink out, Modifier... modifiers) {
        for (Modifier modifier : modifiers) {
            out.append(modifier).append(' ');
        }
    }

    protected void appendAnnotations(CharSink out, Iterable<? extends Type> annotations) {
        for (Type annotation : annotations) {
            out.append('@');
            appendType(out, TypeRef.of(annotation));

            out.ln();
        }
    }

    protected abstract void appendType(CharSink out, TypeRef type, boolean vararg);

    protected abstract void appendType(CharSink out, TypeRef type);

    protected void appendStringLiteral(CharSink out, Object o) {
        if (o == null) {
            out.append("null");
            return;
        }
        if (o instanceof Character) {
            out.append('\'').append(SourceNames.escape((char) o)).append('\'');
            return;
        }

        String s = o.toString();

        out.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                out.append(c);
            } else {
                out.append(SourceNames.escape(c));
            }
        }
        out.append('"');
    }

    private boolean isFormatControl(char c) {
        return switch (c) {
            case 'L', 'S', 'T' -> true;
            default -> false;
        };
    }

    // public api

    public abstract BaseClassRenderer<P> addStaticImports(String... staticImports);

    public abstract BaseClassRenderer<P> addStaticImport(String staticImport);

    public CodeRenderer<CharSequence> createCode() {
        return new CodeRendererImpl(this, new CharSink(out.autoIndent, out.lineWrap));
    }

    public AnnotationRenderer createAnnotation(Class<? extends Annotation> type) {
        return new AnnotationRenderer(ClassRef.of(type), this, new CharSink(out.autoIndent, out.lineWrap));
    }

    // writers

    public BaseClassRenderer<P> addTypeVariables(TypeVariableRef first, TypeVariableRef... rest) {
        if (stage != TYPE_VARIABLES) {
            requireStage(BEGIN, TYPE_VARIABLES);
            completeStage(TYPE_VARIABLES);

            out.append('<');
        } else {
            out.append(", ").lw();
        }

        appendTypeVariable(out, first);
        for (TypeVariableRef t : rest) {
            out.append(", ").lw();
            appendTypeVariable(out, t);
        }
        return this;
    }

    public BaseClassRenderer<P> addTypeVariables(Collection<TypeVariableRef> types) {
        if (types.isEmpty()) {
            return this;
        }

        if (stage != TYPE_VARIABLES) {
            requireStage(BEGIN, TYPE_VARIABLES);
            completeStage(TYPE_VARIABLES);

            out.append('<');
        } else {
            out.append(", ").lw();
        }

        boolean first = true;
        for (TypeVariableRef type : types) {
            if (first) {
                first = false;
            } else {
                out.append(", ").lw();
            }

            appendTypeVariable(out, type);
        }
        return this;
    }

    @Override
    public BaseClassRenderer<P> addAnnotation(AnnotationRenderer renderer) {
        if (stage != ANNOTATIONS) {
            requireStage(BEGIN, ANNOTATIONS);
            completeStage(ANNOTATIONS);
        }
        out.append(renderer.complete());
        return this;
    }

    @Override
    public BaseClassRenderer<P> addAnnotation(Class<? extends Annotation> annotation) {
        return addAnnotations(List.of(annotation));
    }

    @Override
    public BaseClassRenderer<P> addAnnotations(Iterable<Class<? extends Annotation>> annotations) {
        if (stage != ANNOTATIONS) {
            requireStage(BEGIN, ANNOTATIONS);
            completeStage(ANNOTATIONS);
        }
        appendAnnotations(out, annotations);
        return this;
    }

    public BaseClassRenderer<P> addModifiers(Modifier... modifiers) {
        if (stage != MODIFIERS) {
            requireStage(BEGIN, MODIFIERS);
            if (stage == BEGIN) { // TODO
                separate0();
            }
            stage = MODIFIERS;
        }
        appendModifiers(out, modifiers);
        return this;
    }

    public BaseClassRenderer<P> addSuperType(Type type) {
        Preconditions.requireState(kind == ClassRenderer.Kind.CLASS, "Class extending is not allowed in " + kind);
        requireStage(BEGIN, TYPE_VARIABLES);
        completeStage(SUPER_TYPE);

        out.append(" extends ");
        appendType(out, TypeRef.of(type));
        return this;
    }

    public BaseClassRenderer<P> addInterface(Type type) {
        Preconditions.requireState(kind != ClassRenderer.Kind.ANNOTATION, "Interface implementing is not allowed in " + kind);
        if (stage != INTERFACES) {
            requireStage(BEGIN, SUPER_TYPE);
            completeStage(INTERFACES);

            if (kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM) {
                out.append(" implements ");
            } else {
                out.append(" extends ");
            }
        } else {
            out.append(", ").lw();
        }

        appendType(out, TypeRef.of(type));
        return this;
    }

    public BaseClassRenderer<P> addInterfaces(List<? extends Type> types) {
        Preconditions.requireState(kind != ClassRenderer.Kind.ANNOTATION, "Interface implementing is not allowed in " + kind);
        if (types.isEmpty()) {
            return this; // no way to handle
        }

        if (stage != INTERFACES) {
            requireStage(BEGIN, SUPER_TYPE);
            completeStage(INTERFACES);

            if (kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM) {
                out.append(" implements ");
            } else {
                out.append(" extends ");
            }
        } else {
            out.append(", ").lw();
        }

        appendType(out, TypeRef.of(types.get(0)));
        for (int i = 1, n = types.size(); i < n; i++) {
            TypeRef value = TypeRef.of(types.get(i));

            out.append(", ").lw();
            appendType(out, value);
        }
        return this;
    }

    public BaseClassRenderer<P> addInterfaces(Type first, Type... rest) {
        Preconditions.requireState(kind != ClassRenderer.Kind.ANNOTATION, "Interface implementing is not allowed in " + kind);
        if (stage != INTERFACES) {
            requireStage(BEGIN, SUPER_TYPE);
            completeStage(INTERFACES);

            if (kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM) {
                out.append(" implements ");
            } else {
                out.append(" extends ");
            }
        } else {
            out.append(", ").lw();
        }

        appendType(out, TypeRef.of(first));
        for (Type value : rest) {
            TypeRef type = TypeRef.of(value);

            out.append(", ").lw();
            appendType(out, type);
        }
        return this;
    }

    public BaseClassRenderer<P> addPermits(Type first, Type... rest) {
        Preconditions.requireState(kind != ClassRenderer.Kind.ANNOTATION, "Interface implementing is not allowed in " + kind);
        if (stage != PERMITS) {
            requireStage(BEGIN, PERMITS);
            completeStage(PERMITS);

            out.append(" permits ");
        } else {
            out.append(", ").lw();
        }

        appendType(out, TypeRef.of(first));
        for (Type value : rest) {
            TypeRef type = TypeRef.of(value);

            out.append(", ").lw();
            appendType(out, type);
        }
        return this;
    }

    public BaseClassRenderer<P> addAttribute(Type type, String name) {
        Preconditions.requireState(kind == ClassRenderer.Kind.ANNOTATION, "Annotation attributes is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(BEGIN, PROCESSING);
            completeStage(PROCESSING);
        }

        appendType(out, TypeRef.of(type));
        out.append(' ');
        out.append(name);
        out.append("();").ln(2);
        return this;
    }

    public BaseClassRenderer<P> addAttribute(Type type, String name, CharSequence format, Object... args) {
        Preconditions.requireState(kind == ClassRenderer.Kind.ANNOTATION, "Annotation attributes is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(BEGIN, PROCESSING);
            completeStage(PROCESSING);
        }

        appendType(out, TypeRef.of(type));
        out.append(' ');
        out.append(name);
        out.append("() default ");
        formatCode(out, format, args);
        out.append(';').ln(2);

        return this;
    }

    public BaseClassRenderer<P> addConstant(CharSequence name, CharSequence format, Object... args) {
        Preconditions.requireState(kind == ClassRenderer.Kind.ENUM, "Enum constants is not allowed in " + kind);
        if (stage != CONSTANTS) {
            requireStage(BEGIN, PROCESSING);
            completeStage(CONSTANTS);

            out.ln(2);
        } else {
            out.append(',').ln(2);
        }

        out.append(name);
        out.append('(');
        formatCode(out, format, args);
        out.append(')');

        return this;
    }

    public BaseClassRenderer<P> addConstant(CharSequence name) {
        Preconditions.requireState(kind == ClassRenderer.Kind.ENUM, "Enum constants is not allowed in " + kind);
        if (stage != CONSTANTS) {
            requireStage(BEGIN, PROCESSING);
            completeStage(CONSTANTS);

            out.ln(2);
        } else {
            out.append(',').ln(2);
        }

        out.append(name);
        return this;
    }

    public abstract InitializerRenderer<? extends BaseClassRenderer<P>> addStaticInitializer();

    public abstract InitializerRenderer<? extends BaseClassRenderer<P>> addInitializer();

    public FieldRenderer<? extends BaseClassRenderer<P>> addField(Type type, String name) {
        if (stage != PROCESSING) {
            requireStage(BEGIN, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new FieldRenderer<>(this, TypeRef.of(type), name));
    }

    public FieldRenderer<? extends BaseClassRenderer<P>> addField(Type type, String name, Modifier... modifiers) {
        return addField(type, name)
                .addModifiers(modifiers);
    }

    public ExecutableRenderer<? extends BaseClassRenderer<P>> addConstructor() {
        Preconditions.requireState(kind == ClassRenderer.Kind.CLASS || kind == ClassRenderer.Kind.ENUM,
                "Constructors is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(BEGIN, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new ExecutableRenderer<>(this, name.name));
    }

    public ExecutableRenderer<? extends BaseClassRenderer<P>> addConstructor(Modifier... modifiers) {
        return addConstructor()
                .addModifiers(modifiers);
    }

    public MethodRenderer<? extends BaseClassRenderer<P>> addMethod(Type returnType, String name) {
        Preconditions.requireState(kind != ClassRenderer.Kind.ANNOTATION, "Methods is not allowed in " + kind);
        if (stage != PROCESSING) {
            requireStage(BEGIN, PROCESSING);
            completeStage(PROCESSING);
        }
        return addPending(new MethodRenderer<>(this, name, TypeRef.of(returnType)));
    }

    public MethodRenderer<? extends BaseClassRenderer<P>> addMethod(Type returnType, String name, Modifier... modifiers) {
        return addMethod(returnType, name)
                .addModifiers(modifiers);
    }

    protected void complete0() {
        if (stage == BEGIN || stage == ANNOTATIONS || stage == MODIFIERS) { // empty class
            if (stage == BEGIN) {
                separate0();
            }

            out.append(kind.asKeyword());
            out.append(' ');
            out.append(name.name);
            out.append(" {}").ln();
        } else if (stage == PROCESSING) {
            out.decIndent();
            out.lno().append('}').ln();
        } else if (stage == INTERFACES || stage == PERMITS || stage == SUPER_TYPE) {
            out.append(" {}").ln();
        }
    }

    protected void separate0() {} // only for nested class renderers

    @Override
    public Stage stage() {
        return stage;
    }
}

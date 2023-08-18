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

public interface CodeRenderer<P> extends CompletableRenderer<P> {

    CodeRenderer<P> addStatementFormatted(CharSequence code);

    CodeRenderer<P> addStatement(CharSequence code);

    CodeRenderer<P> addStatement(CharSequence format, Object... args);

    CodeRenderer<P> addCode(char c);

    CodeRenderer<P> addCode(CharSequence code);

    CodeRenderer<P> addCodeFormatted(CharSequence code);

    CodeRenderer<P> addCode(CharSequence format, Object... args);

    CodeRenderer<P> beginControlFlow(CharSequence code);

    CodeRenderer<P> beginControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> nextControlFlow(CharSequence code);

    CodeRenderer<P> nextControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> endControlFlow();

    CodeRenderer<P> endControlFlow(CharSequence code);

    CodeRenderer<P> endControlFlow(CharSequence format, Object... args);

    CodeRenderer<P> incIndent();

    CodeRenderer<P> incIndent(int count);

    CodeRenderer<P> decIndent();

    CodeRenderer<P> decIndent(int count);

    CodeRenderer<P> ln();

    CodeRenderer<P> ln(int count);
}

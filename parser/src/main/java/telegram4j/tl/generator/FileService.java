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
package telegram4j.tl.generator;

import telegram4j.tl.generator.renderer.TopLevelRenderer;

import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

import static telegram4j.tl.generator.SchemaGeneratorConsts.TEMPLATE_PACKAGE_INFO;

public class FileService {
    private final String licenseHeader;
    private final Filer filer;

    public FileService(Filer filer) {
        this.filer = filer;

        try {
            licenseHeader = filer
                    .getResource(StandardLocation.ANNOTATION_PROCESSOR_PATH, "", "license-header")
                    .getCharContent(true)
                    .toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeTo(TopLevelRenderer renderer) {
        CharSequence seq = renderer.complete();
        try {
            String filename = renderer.name.qualifiedName().replace('/', '.');
            JavaFileObject fo = filer.createSourceFile("telegram4j.tl/" + filename);
            try (Writer w = fo.openWriter()) {
                w.append(licenseHeader);
                w.append(seq);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

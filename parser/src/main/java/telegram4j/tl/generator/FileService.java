package telegram4j.tl.generator;

import telegram4j.tl.generator.renderer.TopLevelRenderer;

import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

public class FileService {
    private final Filer filer;

    public FileService(Filer filer) {
        this.filer = filer;
    }

    public void writeTo(TopLevelRenderer renderer) {
        CharSequence seq = renderer.complete();
        try {
            String filename = renderer.name.qualifiedName().replace('/', '.');
            JavaFileObject fo = filer.createSourceFile(filename);
            try (Writer w = fo.openWriter()) {
                w.append(seq);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

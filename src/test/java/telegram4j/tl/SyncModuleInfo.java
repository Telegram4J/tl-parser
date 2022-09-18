package telegram4j.tl;

import java.io.File;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;

public class SyncModuleInfo {

    static class PackagesCollector extends SimpleFileVisitor<Path> {
        final Path root;
        final Set<String> packages;

        PackagesCollector(Path root, Set<String> packages) {
            this.root = root;
            this.packages = packages;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
            }

            String pckg = root.relativize(dir).toString()
                    .replace(File.separator, ".");
            packages.add("telegram4j." + pckg);
            return FileVisitResult.CONTINUE;
        }
    }

    public static void main(String[] args) throws Throwable {

        Path procSrc = Path.of("build/generated/sources/annotationProcessor/java/main/telegram4j/");
        Path rootSrc = Path.of("src/main/java/telegram4j/");
        Set<String> exports = new TreeSet<>();

        Files.walkFileTree(procSrc, new PackagesCollector(procSrc, exports));
        Files.walkFileTree(rootSrc, new PackagesCollector(rootSrc, exports));

        Path desc = Path.of("src/main/java/module-info.java");
        try (var w = Files.newBufferedWriter(desc)) {
            w.append("import com.fasterxml.jackson.databind.Module;\n");
            w.append("import telegram4j.tl.json.TlModule;\n\n");
            w.append("module telegram4j.tl {\n");
            w.append("\trequires io.netty.buffer;\n");
            w.append("\trequires reactor.core;\n");
            w.append("\trequires com.fasterxml.jackson.databind;\n\n");
            w.append("\trequires transitive telegram4j.tl.api;\n\n");
            w.append("\trequires static telegram4j.tl.parser;\n\n");
            for (String export : exports) {
                w.append("\texports ").append(export).append(";\n");
            }
            w.append("\tprovides Module with TlModule;\n");
            w.append("}\n");
        }
    }
}

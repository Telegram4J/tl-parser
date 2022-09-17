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

        StringBuilder md = new StringBuilder();

        md.append("module telegram4j.tl {\n");
        md.append("\trequires io.netty.buffer;\n");
        md.append("\trequires reactor.core;\n");
        md.append("\trequires com.fasterxml.jackson.databind;\n\n");
        md.append("\trequires transitive telegram4j.tl.api;\n\n");
        md.append("\trequires static telegram4j.tl.parser;\n\n");
        for (String export : exports) {
            md.append("\texports ").append(export).append(";\n");
        }
        md.append("}\n");

        Path desc = Path.of("src/main/java/module-info.java");
        Files.writeString(desc, md);
    }
}

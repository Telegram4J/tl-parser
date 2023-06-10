import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.versions)
}

val isJitpack = System.getenv("JITPACK") == "true"
val isSnapshot = version.toString().endsWith("-SNAPSHOT")

allprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.github.ben-manes.versions")

    dependencies {
        implementation(platform(rootProject.libs.reactor.bom))
        implementation(platform(rootProject.libs.netty.bom))
        implementation(rootProject.libs.jackson.databind)
        implementation(rootProject.libs.reactor.core)
        compileOnly(rootProject.libs.jsr305)

        testImplementation(rootProject.libs.junit)
    }

    tasks.withType<JavaCompile> {
        options.javaModuleVersion.set(version.toString())
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    tasks.test {
        useJUnitPlatform()
    }
}

dependencies {
    implementation(libs.netty.handler)

    compileOnly(project(":parser"))
    annotationProcessor(project(":parser"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.telegram4j"

            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }

            pom {
                description.set("Telegram API entity domain")
                url.set("https://github.com/Telegram4J/tl-parser")
                inceptionYear.set("2021")

                developers {
                    developer { name.set("The Telegram4J") }
                }

                licenses {
                    license {
                        name.set("GPL-3.0")
                        url.set("https://github.com/Telegram4J/tl-parser/LICENSE")
                        distribution.set("repo")
                    }
                }

                scm {
                    url.set("https://github.com/Telegram4J/tl-parser")
                    connection.set("scm:git:git://github.com/Telegram4J/tl-parser.git")
                    developerConnection.set("scm:git:ssh://git@github.com:Telegram4J/tl-parser.git")
                }
            }

            if (!isJitpack) {
                repositories {
                    maven {
                        if (isSnapshot) {
                            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
                        } else {
                            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2")
                        }

                        val sonatypeUsername: String? by project
                        val sonatypePassword: String? by project
                        if (sonatypeUsername != null && sonatypePassword != null) {
                            credentials {
                                username = sonatypeUsername
                                password = sonatypePassword
                            }
                        }
                    }
                }
            }
        }

        if (!isJitpack && !isSnapshot) {
            signing {
                val signingKey: String? by project
                val signingPassword: String? by project
                if (signingKey != null && signingPassword != null) {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                }
                sign(this@publications["mavenJava"])
            }
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.named<Jar>("sourcesJar") {
    dependsOn("compileJava")
    from("$buildDir/generated/sources/annotationProcessor/java/main")
}

val updateModuleInfo by tasks.registering {
    mustRunAfter("compileJava")

    doLast {

        class PackagesCollector(val root: Path, val packages: MutableSet<String>) : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) {
                    return FileVisitResult.CONTINUE
                }
                val pckg = root.relativize(dir).toString()
                    .replace(File.separator, ".")
                packages.add("telegram4j.$pckg")
                return FileVisitResult.CONTINUE
            }
        }

        val procSrc = Path.of("build/generated/sources/annotationProcessor/java/main/telegram4j/")
        val rootSrc = Path.of("src/main/java/telegram4j/")
        val exports = TreeSet<String>()

        Files.walkFileTree(procSrc, PackagesCollector(procSrc, exports))
        Files.walkFileTree(rootSrc, PackagesCollector(rootSrc, exports))

        val desc = Path.of("src/main/java/module-info.java")
        Files.newBufferedWriter(desc).use { w ->
            w.append("import com.fasterxml.jackson.databind.Module;\n")
            w.append("import telegram4j.tl.json.TlModule;\n\n")
            w.append("module telegram4j.tl {\n")
            w.append("\trequires io.netty.buffer;\n")
            w.append("\trequires reactor.core;\n")
            w.append("\trequires com.fasterxml.jackson.databind;\n\n")
            w.append("\trequires static telegram4j.tl.parser;\n\n")
            for (export in exports) {
                w.append("\texports ").append(export).append(";\n")
            }
            w.append('\n')
            w.append("\tprovides Module with TlModule;\n")
            w.append("}\n")
        }
    }
}

tasks.classes {
    dependsOn(updateModuleInfo)
}

tasks.javadoc {
    source += fileTree("$buildDir/generated/sources/annotationProcessor/java/main/")

    title = "Telegram4J TL API reference ($version)"

    options {
        encoding = "UTF-8"
        locale = "en_US"
        val opt = this as StandardJavadocDocletOptions
        opt.addBooleanOption("html5", true)
        opt.addStringOption("encoding", "UTF-8")

        tags = listOf(
            "apiNote:a:API Note:",
            "implSpec:a:Implementation Requirements:",
            "implNote:a:Implementation Note:"
        )
        links = listOf(
            "https://projectreactor.io/docs/core/release/api/",
            "https://fasterxml.github.io/jackson-databind/javadoc/2.14/",
            "https://www.reactive-streams.org/reactive-streams-1.0.3-javadoc/",
            "https://netty.io/4.1/api/"
        )
    }
}

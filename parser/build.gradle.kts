dependencies {
    compileOnly(libs.immutables.value)
    annotationProcessor(libs.immutables.value)

    implementation(libs.reactor.netty)
}

tasks.register<JavaExec>("updateSchemas") {
    mainClass.set("telegram4j.tl.parser.SchemaUpdater")
    classpath = sourceSets.main.get().runtimeClasspath
}

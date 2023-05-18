dependencies {
    compileOnly(libs.immutables.value)
    annotationProcessor(libs.immutables.value)

    implementation(libs.jackson.databind)
    implementation(libs.reactor.addons.extra)
    implementation(libs.reactor.netty)

    testImplementation(libs.junit)
}

tasks.register<JavaExec>("updateSchemas") {
    mainClass.set("telegram4j.tl.parser.SchemaUpdater")
    classpath = sourceSets.main.get().runtimeClasspath
}

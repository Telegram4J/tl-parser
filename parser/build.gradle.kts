dependencies {
    implementation(libs.netty.buffer)
    compileOnly(libs.immutables.value)
    annotationProcessor(libs.immutables.value)
}

val updateSchemas by tasks.registering(JavaExec::class) {
    mainClass.set("telegram4j.tl.parser.SchemaUpdater")
    classpath = sourceSets.main.get().runtimeClasspath
}

// TODO update api scheme before processing

tasks.compileJava {
    finalizedBy(updateSchemas)
}

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("dev.zcodemobile.verify.MainKt")
}

/** Read path: handshake → bootstrap → bridge → subscribe → snapshot. */
tasks.register<JavaExec>("verifyRead") {
    group = "verification"
    description = "Verify the read path against a live relay."
    mainClass.set("dev.zcodemobile.verify.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** Write path: envelope construction, command dispatch, typed ack handling. */
tasks.register<JavaExec>("verifyCommands") {
    group = "verification"
    description = "Verify the command path against a live relay."
    mainClass.set("dev.zcodemobile.verify.CommandProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

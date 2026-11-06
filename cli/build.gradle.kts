plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.gson)
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "dev.bedrockadder.cli.Main")
    }
}

tasks.shadowJar {
    archiveBaseName.set("BedrockAdder-cli")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "dev.bedrockadder.cli.Main")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

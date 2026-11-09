plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.gson)
    compileOnly(libs.geyser.api)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("extension.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("BedrockBundlerExtension")
    archiveClassifier.set("")
    relocate("com.google.gson", "dev.bedrockbundler.libs.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

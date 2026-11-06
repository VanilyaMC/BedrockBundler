plugins {
    alias(libs.plugins.shadow)
}

repositories {
    maven("https://repo.nexomc.com/releases")
}

// ItemsAdder is a paid plugin and is not on any public repository. Its hook lives in src/itemsadder/java and is
// only compiled when your licensed jar is at libs/ItemsAdder-<version>.jar (git-ignored). Without it the plugin
// still builds and supports Nexo; the ItemsAdder hook is then simply absent.
val itemsAdderJars = fileTree(rootProject.file("libs")) { include("ItemsAdder*.jar") }
val withItemsAdder = !itemsAdderJars.isEmpty

sourceSets {
    main {
        if (withItemsAdder) {
            java.srcDir("src/itemsadder/java")
        }
    }
}

dependencies {
    implementation(project(":common"))
    compileOnly(libs.paper.api)
    compileOnly(libs.floodgate.api)
    compileOnly(libs.geyser.api)
    compileOnly(libs.nexo) { isTransitive = false }
    compileOnly(itemsAdderJars)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }

    // Ship the Geyser extension inside the plugin; it installs itself into Geyser-Spigot/extensions.
    val extensionJar = project(":geyser-extension").tasks.named<Jar>("shadowJar")
    dependsOn(extensionJar)
    from(extensionJar.flatMap { it.archiveFile }) {
        rename { "BedrockAdderExtension.jar" }
    }
}

tasks.shadowJar {
    archiveBaseName.set("BedrockAdder")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

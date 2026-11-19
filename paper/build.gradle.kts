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

// The BetterModel API is compiled for Java 25, so the hook that uses it is its own source set built with release 25.
// It is only loaded (by name) on servers that run BetterModel, which needs Java 25 itself. A build on an older JDK
// leaves the hook out; the plugin then just shows no BetterModel models to Bedrock players.
val withBetterModel = JavaVersion.current().majorVersion.toInt() >= 25
val betterModel: SourceSet = sourceSets.create("bettermodel")

dependencies {
    implementation(project(":common"))
    compileOnly(libs.paper.api)
    compileOnly(libs.floodgate.api)
    compileOnly(libs.geyser.api)
    compileOnly(libs.nexo) { isTransitive = false }
    compileOnly(itemsAdderJars)

    "bettermodelCompileOnly"(sourceSets.main.get().output)
    "bettermodelCompileOnly"(project(":common"))
    "bettermodelCompileOnly"(libs.paper.api)
    "bettermodelCompileOnly"(libs.bettermodel.api)
    "bettermodelCompileOnly"(libs.bettermodel.bukkit.api)

    // compileOnly does not reach the test classpath; the display tests need Bukkit and Adventure classes.
    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<JavaCompile>("compileBettermodelJava") {
    options.release.set(25)
    enabled = withBetterModel
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }

    // Ship the Geyser extension inside the plugin; it installs itself into Geyser-Spigot/extensions.
    val extensionJar = project(":geyser-extension").tasks.named<Jar>("shadowJar")
    dependsOn(extensionJar)
    from(extensionJar.flatMap { it.archiveFile }) {
        rename { "BedrockBundlerExtension.jar" }
    }
}

tasks.shadowJar {
    if (withBetterModel) {
        from(betterModel.output)
    }
    archiveBaseName.set("BedrockBundler")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

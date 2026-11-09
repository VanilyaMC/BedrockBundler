// The root project builds nothing itself; applying `java` here would produce an empty
// build/libs/BedrockAdder-<version>.jar that is easy to mistake for the plugin.
plugins {
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.bedrockadder"
    version = "0.2.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.opencollab.dev/main/")
    }

    // Any JDK 21+ can build; release=21 keeps the bytecode loadable on Java 21 servers.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

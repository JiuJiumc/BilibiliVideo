import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
}
repositories { mavenCentral() }

dependencies {
    compileOnly(files("libs/folia-api-26.2.build.7-beta.jar", "libs/JiuJiuLib.jar"))
    compileOnly("net.kyori:adventure-api:5.2.0")
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.ktorm:ktorm-core:3.6.0")
    implementation("org.ktorm:ktorm-support-mysql:3.6.0")
    implementation("org.ktorm:ktorm-support-sqlite:3.6.0")
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.mysql:mysql-connector-j:8.3.0") { isTransitive = false }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.zxing:core:3.5.2")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("io.netty:netty-transport:4.2.7.Final")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(files("libs/folia-api-26.2.build.7-beta.jar"))
    testImplementation("net.kyori:adventure-api:5.2.0")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("com.google.guava:guava:33.3.1-jre")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("io.netty:netty-transport:4.2.7.Final")
}
configurations.runtimeClasspath { exclude(group="org.slf4j") }
kotlin { compilerOptions { jvmTarget.set(JVM_21) } }
java { sourceCompatibility=JavaVersion.VERSION_21; targetCompatibility=JavaVersion.VERSION_21 }
tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar {
    archiveFileName.set("JiuJiublbl.jar")
    duplicatesStrategy=DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    from(configurations.runtimeClasspath.get().filter { it.isFile }.map { zipTree(it) })
}
tasks.test { useJUnitPlatform() }

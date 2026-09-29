plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

apply(from = "../repositories.gradle.kts")

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Gradle Plugins
    implementation("com.android.tools.build:gradle:8.8.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
}

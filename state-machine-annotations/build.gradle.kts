import edu.wpi.first.toolchain.NativePlatforms

plugins {
    id("java")
    id("edu.wpi.first.wpilib.repositories.WPILibRepositoriesPlugin") version "2025.0"
    id("edu.wpi.first.GradleRIO") version "2025.3.2"
}

group = "bot.den"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
wpilibRepositories.addAllReleaseRepositories(project)

val wpilibVersion = "2025.3.2"

dependencies {
    implementation("com.palantir.javapoet:javapoet:0.9.0")


    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-cpp:${wpilibVersion}")

    wpi.java.deps.wpilibAnnotations().forEach {
        annotationProcessor(it)
    }
    wpi.java.deps.wpilib().forEach {
        implementation(it)
    }
    wpi.java.vendor.java().forEach {
        implementation(it)
    }

    wpi.java.deps.wpilibJniRelease(NativePlatforms.desktop).forEach {
        implementation(it)
    }
    wpi.java.vendor.jniRelease(NativePlatforms.desktop).forEach {
        implementation(it)
    }
}

wpi.java.configureTestTasks(tasks.test.get())
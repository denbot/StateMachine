plugins {
    id("java")
    id("edu.wpi.first.wpilib.repositories.WPILibRepositoriesPlugin") version "2025.0"
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

    implementation(project(":state-machine"))

    implementation("edu.wpi.first.wpilibj:wpilibj-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpiutil:wpiutil-java:${wpilibVersion}")
    implementation("edu.wpi.first.ntcore:ntcore-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-cpp:${wpilibVersion}")
}
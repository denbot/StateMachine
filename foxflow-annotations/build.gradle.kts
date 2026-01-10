plugins {
    id("java")
    id("maven-publish")
    id("edu.wpi.first.wpilib.repositories.WPILibRepositoriesPlugin") version "2025.0"
}

group = "bot.den"
version = project.findProperty("version") ?: "dev"

repositories {
    mavenCentral()
}
wpilibRepositories.addAllReleaseRepositories(project)

val wpilibVersion = "2026.1.1"

dependencies {
    implementation("com.palantir.javapoet:javapoet:0.9.0")

    implementation(project(":foxflow"))

    implementation("edu.wpi.first.wpilibj:wpilibj-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpiutil:wpiutil-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpimath:wpimath-java:${wpilibVersion}")
    implementation("edu.wpi.first.ntcore:ntcore-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-java:${wpilibVersion}")
    implementation("edu.wpi.first.wpilibNewCommands:wpilibNewCommands-cpp:${wpilibVersion}")
}

java {
    if (project.hasProperty("version")) {
        withSourcesJar()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "bot.den"
            artifactId = "foxflow-annotations"
            version = project.version.toString()

            from(components["java"])
        }
    }
}
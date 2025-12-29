plugins {
    id("java")
}

group = "bot.den"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":state-machine"))

    annotationProcessor(project(":state-machine-annotations"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
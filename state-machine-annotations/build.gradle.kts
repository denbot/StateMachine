plugins {
    id("java")
}

group = "bot.den"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.palantir.javapoet:javapoet:0.9.0")
}
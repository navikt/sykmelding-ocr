plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.bytedeco.javacv)
    implementation(libs.pdfbox)
    implementation(libs.google.cloud.storage)
    implementation(project(":lib"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform {
        excludeTags("ocr-smoke")
    }
}

tasks.register<Test>("ocrSmokeTest") {
    description = "Runs OCR on svenskmann.pdf and prints fields for manual inspection."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("ocr-smoke")
    }
    // Full-resolution matching also needs native OpenCV memory outside the Java heap.
    maxHeapSize = "1g"
    systemProperty("org.bytedeco.javacpp.maxPhysicalBytes", "8G")
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
}

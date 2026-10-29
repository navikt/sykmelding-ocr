plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.tsm.opencv)
    // PageRotation's orientation detection calls Tesseract directly.
    implementation(libs.bytedeco.tesseract)
    implementation(libs.pdfbox)
    implementation(libs.google.cloud.storage)
    implementation(libs.papirsykemelding)
    // papirsykemelding only brings JAXB at runtime; JAXBContext/Unmarshaller are needed to compile the parser.
    implementation(libs.jaxb.api)
    implementation(project(":lib"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.named<Test>("test") {
    jvmArgs = listOf("-Xms512m","-Xmx1024m")
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}


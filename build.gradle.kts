import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "caja"
description = "caja-backend: caja de cobranzas sobre wasichai (core, views, forms, pages)"

val wasichaiVersion = "0.2.0"

dependencies {
    implementation(platform("wasichai:wasichai-bom:$wasichaiVersion"))
    implementation("wasichai:wasichai-spring-boot-starter")
    implementation("wasichai:wasichai-spring-boot-starter-views")
    implementation("wasichai:wasichai-spring-boot-starter-forms")
    implementation("wasichai:wasichai-spring-boot-starter-pages")

    // WasichaiIntegrationTest; brings spring-boot-starter-test, webflux-test and testcontainers
    testImplementation("wasichai:wasichai-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// ktlint reads .editorconfig; same tool version as wasichai
ktlint {
    version.set("1.7.1")
}

// unit tests always run; container-backed ones go through integrationTest
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // FronteraDeLaOrdenTest lee model/model.json: un cambio en el modelo vuelve a correr las pruebas
    inputs.file("model/model.json")
}

// WASICHAI_TEST_DB_* (if set) point the suite at an external db instead of testcontainers
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "pruebas de integración contra PostgreSQL 18"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // caja corre en PostgreSQL plano, sin extensiones (PostGIS no hace falta). una base de test externa
    // (WASICHAI_TEST_DB_*) tampoco necesita ninguna
    systemProperty("wasichai.test.db.image", "postgres:18")
    // la app registra en stdout; stderr lleva solo lo que un test reporta
    testLogging {
        events("standard_error")
    }
    // CajaApiTest aplica el modelo y los roles en cada prueba: un cambio en ellos vuelve a correr la suite, nunca un verde
    // de caché
    inputs.file("model/model.json")
    inputs.file("model/roles.json")
    shouldRunAfter(tasks.named("test"))
}

// one runnable jar with a fixed name
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

// an app, not a library: no plain jar next to the boot jar
tasks.named<Jar>("jar") {
    enabled = false
}

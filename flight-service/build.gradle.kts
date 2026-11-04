plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}
kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}
dependencies {
    implementation(project(":flight-contract"))
    implementation(platform("io.grpc:grpc-bom:1.75.0"))
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-services")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-inprocess")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
tasks.test { useJUnitPlatform() }
tasks.bootJar { archiveFileName.set("app.jar") }

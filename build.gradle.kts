plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}
allprojects {
    group = "com.example.flightbooking"
    version = "1.0.0"
    repositories { mavenCentral() }
}

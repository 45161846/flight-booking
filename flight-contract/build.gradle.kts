plugins {
    `java-library`
    id("com.google.protobuf")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    api(platform("io.grpc:grpc-bom:1.75.0"))
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    api("com.google.protobuf:protobuf-java:4.32.1")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.32.1" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.75.0" } }
    generateProtoTasks { all().configureEach { plugins { create("grpc") } } }
}

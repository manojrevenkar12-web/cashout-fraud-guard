import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf")
    application
}

val grpc = "1.68.1"
val grpcKotlin = "1.4.1"
val protobuf = "3.25.5"

dependencies {
    implementation(project(":core"))
    implementation("io.grpc:grpc-netty-shaded:$grpc")
    implementation("io.grpc:grpc-protobuf:$grpc")
    implementation("io.grpc:grpc-stub:$grpc")
    implementation("io.grpc:grpc-services:$grpc")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlin")
    implementation("com.google.protobuf:protobuf-java:$protobuf")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.inject:guice:7.0.0")
    implementation("org.liquibase:liquibase-core:4.29.2")
    implementation("com.zaxxer:HikariCP:6.2.1")
    runtimeOnly("com.mysql:mysql-connector-j:9.1.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("io.grpc:grpc-inprocess:$grpc")
    testImplementation("org.testcontainers:mysql:1.20.4")
    testImplementation("com.mysql:mysql-connector-j:9.1.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobuf" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpc" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlin:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

// The service ships the exact settings and model the evaluation measured.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("config/guard.json")) { into("config") }
    from(rootProject.file("models/risk-model.json")) { into("model") }
}

application {
    mainClass.set("fraudguard.service.MainKt")
    applicationName = "cashout-fraud-guard"
}

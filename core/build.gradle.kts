import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Čistě JVM modul: veškerá navigační logika bez závislosti na Androidu,
// takže ji můžeme rychle testovat na JVM (žádný emulátor).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // api: OkHttpClient je součástí veřejného API (ValhallaRoutingProvider, PhotonGeocoder)
    api(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

// ./gradlew :core:routingBenchmark – porovnání Dijkstra / A* / obousměrný / CH
tasks.register<JavaExec>("routingBenchmark") {
    group = "verification"
    description = "Runs the routing algorithm benchmark on a synthetic road network."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cz.fenixen.navigation.core.graph.RoutingBenchmarkKt")
    jvmArgs("-Xmx2g", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

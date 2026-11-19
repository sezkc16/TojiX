// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch the latest movies and TV series on Cinejoy"
    authors = listOf("Toji")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://cinejoy.pk/pwa-512.png"

    isCrossPlatform = true
}

android {
    namespace = "com.cinejoy"
}

// Chicory is a pure-JVM WASM runtime: the site's crush.wasm is run
// inside the plugin to seal stream requests (see CinejoyExtractor).
// The CloudStream gradle plugin only dexes the module's own classes,
// so the Chicory jars have to be unpacked into the kotlin-classes dir
// before compileDex runs (same mechanism as the reference
// implementation in dhamarar/cloudrepo).
val chicoryConfig by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation("com.dylibso.chicory:runtime:1.0.0")
    implementation("com.dylibso.chicory:wasm:1.0.0")
    chicoryConfig("com.dylibso.chicory:runtime:1.0.0")
    chicoryConfig("com.dylibso.chicory:wasm:1.0.0")
}

val extractDependencies by tasks.registering(Copy::class) {
    dependsOn("compileDebugKotlin")
    from(chicoryConfig.map { zipTree(it) })
    into(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("compileDex") {
    dependsOn(extractDependencies)
}

// Gradle validates that every task consuming extractDependencies'
// output declares a dependency on it — extractDependencies writes
// into kotlin-classes/debug, which compileDex, bundleLibRuntimeToJar
// and the java-res tasks all read. The tasks are created by AGP
// after this script is evaluated, so they are wired up once all
// projects are evaluated, when they are guaranteed to exist.
gradle.projectsEvaluated {
    listOf("bundleLibRuntimeToJarDebug", "processDebugJavaRes", "mergeDebugJavaResource").forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn(extractDependencies)
    }
}

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

// Gradle validates that bundleLibRuntimeToJarDebug declares a dependency
// on any task whose output it consumes — extractDependencies writes
// into kotlin-classes/debug, which that task reads. Without this edge
// the build fails validation ("uses this output ... without declaring
// an explicit or implicit dependency"). The task is created by AGP
// after this script is evaluated, so it is wired up once all projects
// are evaluated, when the task is guaranteed to exist.
gradle.projectsEvaluated {
    tasks.findByName("bundleLibRuntimeToJarDebug")?.dependsOn(extractDependencies)
}

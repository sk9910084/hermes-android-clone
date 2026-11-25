// Top-level build file. Plugin versions are declared once here and applied in :app.
//
// Version pairing (do not bump one without the others):
//   Gradle 8.7  <->  AGP 8.3.2  <->  Kotlin 1.9.23  <->  KSP 1.9.23-1.0.20
//   Compose compiler 1.5.11 (set in app/build.gradle.kts) is the release built for Kotlin 1.9.23.
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}

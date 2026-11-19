buildscript {
    repositories { google() }
    // Same D8/R8 override BitChord uses: AGP 8.10's bundled one does not read Kotlin 2.3 metadata.
    dependencies { classpath("com.android.tools:r8:8.13.23") }
}
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}

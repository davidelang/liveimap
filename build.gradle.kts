plugins {
    id("com.android.application") version "9.0.0" apply false
    // AGP 9 built-in Kotlin is KGP 2.2.10; Compose compiler plugin must match it.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

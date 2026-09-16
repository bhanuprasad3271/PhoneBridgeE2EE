plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.phonebridge.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}
dependencies { implementation("androidx.core:core-ktx:1.17.0") }

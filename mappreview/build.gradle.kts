plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.phonebridge.mappreview"
    compileSdk = 36
    defaultConfig { applicationId = "com.phonebridge.mappreview"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
}

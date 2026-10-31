plugins {
    id("com.android.application")
    id("com.android.legacy-kapt")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.phonebridge.receiver"
    compileSdk = 36
    lint {
        if (providers.gradleProperty("compatibilityLint").isPresent) {
            checkOnly += setOf("NewApi", "InlinedApi")
        }
    }
    defaultConfig {
        applicationId = "com.phonebridge.receiver"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = providers.environmentVariable("PHONEBRIDGE_VERSION_CODE").orElse("1").get().toInt()
        versionName = "0.1.$versionCode"
    }
    if (providers.environmentVariable("PHONEBRIDGE_REQUIRE_SIGNING").orNull == "true") {
        fun signingValue(name: String): String =
            providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: error("Missing $name; refusing temporary signing for a distributed APK")
        signingConfigs.create("persistent") {
            storeFile = file(signingValue("PHONEBRIDGE_KEYSTORE_PATH"))
            storePassword = signingValue("PHONEBRIDGE_STORE_PASSWORD")
            keyAlias = signingValue("PHONEBRIDGE_KEY_ALIAS")
            keyPassword = signingValue("PHONEBRIDGE_KEY_PASSWORD")
        }
        buildTypes.getByName("debug") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(project(":shared"))
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.google.guava:guava:33.7.2-android")
    // Offline-only vector map rendering used by the receiver timeline.
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
}

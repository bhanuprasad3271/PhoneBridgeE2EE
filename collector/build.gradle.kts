plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.phonebridge.collector"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    lint {
        if (providers.gradleProperty("compatibilityLint").isPresent) {
            checkOnly += setOf("NewApi", "InlinedApi")
        }
    }
    defaultConfig {
        applicationId = "com.phonebridge.collector"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = providers.environmentVariable("PHONEBRIDGE_VERSION_CODE").orElse("1").get().toInt()
        versionName = "0.1.$versionCode"
        buildConfigField("String", "GITHUB_BUILD", "\"${providers.environmentVariable("GITHUB_RUN_NUMBER").orElse("0").get()}\"")
    }
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition"; versionNameSuffix = "-full" }
        create("lite") { dimension = "edition"; versionNameSuffix = "-lite" }
    }
    // CI distribution builds must supply the same durable key on every run.
    // Ordinary local/test builds can still use Android's local debug keystore.
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(project(":shared"))
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    "fullImplementation"("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("com.google.android.material:material:1.12.0")
}


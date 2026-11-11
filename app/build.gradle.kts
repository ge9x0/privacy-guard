import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.privacyguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.privacyguard"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 30          // Android 11: Wireless debugging + getConnectionOwnerUid
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release key: with -Ppublish and a keystore.properties file (never committed) it signs the release build;
    // otherwise the debug key is used, which is fine for personal installs only.
    val ksFile = rootProject.file("keystore.properties")
    // Only with -Ppublish: personal builds keep the debug key so they install over the existing app.
    if (ksFile.exists() && project.hasProperty("publish")) {
        val ks = Properties().apply { ksFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // R8: optimized code makes the Compose UI several times faster than the debug build
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ADB inside the app (Wireless debugging to itself), like LADB/Shizuku
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // certificate for the ADB key
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

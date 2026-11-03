plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing: a private key from GitHub Actions secrets if present,
// otherwise the committed public build key (see signing/README.md).
val privateKeystorePath: String? = System.getenv("TARGET_KEYSTORE_PATH")
val hasPrivateKey = !privateKeystorePath.isNullOrBlank() && file(privateKeystorePath).exists()

android {
    namespace = "com.rachitratnammudaliar.target"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rachitratnammudaliar.target"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 34
        versionCode = (System.getenv("TARGET_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("TARGET_VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasPrivateKey) {
                storeFile = file(privateKeystorePath!!)
                storePassword = System.getenv("TARGET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TARGET_KEY_ALIAS")
                keyPassword = System.getenv("TARGET_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/public-release.jks")
                storePassword = "target-public"
                keyAlias = "target"
                keyPassword = "target-public"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Release builds shouldn't fail on style warnings; errors are still reported in CI logs.
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

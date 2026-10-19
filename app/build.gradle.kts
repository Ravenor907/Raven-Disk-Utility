import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: a release key from CI secrets when configured, otherwise the repository's shared test
// key, so every build installs over the previous one.
val keystoreFile = System.getenv("RDU_KEYSTORE_FILE")?.let { file(it) } ?: file("signing/test-key.p12")
val keystorePass = System.getenv("RDU_KEYSTORE_PASSWORD") ?: "raven-disk-test"
val keyAliasName = System.getenv("RDU_KEY_ALIAS") ?: "raven-disk"

android {
    namespace = "io.github.ravenor907.ravendisk"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ravenor907.ravendisk"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"
    }

    signingConfigs {
        create("raven") {
            storeFile = keystoreFile
            storePassword = keystorePass
            keyAlias = keyAliasName
            keyPassword = keystorePass
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("raven")
        }
        debug {
            signingConfig = signingConfigs.getByName("raven")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
}

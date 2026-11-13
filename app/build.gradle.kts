plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.paperview.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paperview.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-proguard-rules.pro")

        ndk {
            // PDFium ships a native .so per ABI. Shipping only the two ABIs
            // real phones use keeps the APK ~3x smaller than the default four.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    splits {
        // One APK per ABI so an arm64 phone doesn't carry a 32-bit copy of
        // PDFium it will never load. The universal APK stays available for
        // when you just want a single file that installs anywhere.
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // Instrumented tests run against the debug build. Pointing them at the
    // minified release build needs androidx.test keep rules in the *app's*
    // proguard config — test-framework rules would then ship in the real APK,
    // so the release path is verified by driving the UI instead.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-key signing so a sideloaded release installs over the debug
            // build. Release mode is also what makes Compose scrolling smooth —
            // debuggable builds skip AOT and keep runtime checks on.
            signingConfig = signingConfigs.getByName("debug")
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
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // PDFBox pulls in BouncyCastle, whose post-quantum parameter tables
            // (SIKE, Picnic) are ~4 MB of the APK on their own. PDF encryption
            // only ever uses RC4 and AES, so none of this is reachable.
            "org/bouncycastle/pqc/crypto/picnic/*.properties",
            "org/bouncycastle/pqc/crypto/sike/*.properties",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.pdfiumandroid)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.document.scanner)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

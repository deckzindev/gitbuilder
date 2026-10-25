plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.gitbuilder"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.gitbuilder"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Recursos de build que o app não usa: menos tarefas na compilação.
    buildFeatures {
        buildConfig = false
        resValues = false
    }

    buildTypes {
        // O GitBuilder compila sempre em release (assinado com chave de teste no workflow).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }

    // Metadados que só aumentam o APK.
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/*.version", "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin", "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1") // FileProvider (instalação do APK)
}

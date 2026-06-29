plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.PMouse"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.PMouse"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    aaptOptions {
        noCompress("tflite")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "lib/arm64-v8a/libLiteRtDispatch_MediaTek.so",
                "lib/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so",
                "lib/arm64-v8a/libLiteRt.so",
            )
        }
        resources {
            pickFirsts += setOf(
                "org/tensorflow/lite/**",
            )
        }

    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
}

afterEvaluate {
    tasks.matching { it.name == "stripDebugDebugSymbols" }.configureEach {
        doLast {
            val strippedDir = file("${buildDir}/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a")
            val ourLib = file("src/main/jniLibs/arm64-v8a/libLiteRtDispatch_MediaTek.so")
            if (strippedDir.exists() && ourLib.exists()) {
                val target = File(strippedDir, "libLiteRtDispatch_MediaTek.so")
                ourLib.copyTo(target, overwrite = true)
                logger.lifecycle("Patched libLiteRtDispatch_MediaTek.so after strip (${ourLib.length()} bytes)")
            }
        }
    }
}


dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation("com.google.ai.edge.litert:litert:2.1.5")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
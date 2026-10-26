plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cl.zkage.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cl.zkage.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The mock card, for the instrumented tests. Never real card data.
    sourceSets["androidTest"].assets.directories += "../../prover/fixtures/out"

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
            // The three Bouncy Castle jars ship the same license file.
            pickFirsts += setOf("META-INF/LICENSE.md")
        }
    }
}

dependencies {
    implementation(project(":verifier"))
    implementation(libs.jmrtd)
    implementation(libs.scuba.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

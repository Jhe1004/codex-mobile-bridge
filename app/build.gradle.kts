plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val xiaomiSuperIslandAppId = providers.gradleProperty("xiaomiSuperIslandAppId").getOrElse("")
val escapedXiaomiSuperIslandAppId = xiaomiSuperIslandAppId.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "org.codexmobile.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.codexmobile.bridge"
        minSdk = 28
        targetSdk = 36
        versionCode = 38
        versionName = "0.21.0"

        manifestPlaceholders["xiaomiSuperIslandAppId"] = xiaomiSuperIslandAppId
        manifestPlaceholders["xiaomiSuperIslandDebug"] = "true"
        buildConfigField("String", "XIAOMI_SUPER_ISLAND_APP_ID", "\"$escapedXiaomiSuperIslandAppId\"")

        val relayUrl = providers.gradleProperty("relayUrl")
            .orElse(providers.environmentVariable("CODEX_MOBILE_RELAY_URL"))
            .getOrElse("http://10.0.2.2:18880/v1/ws")
        buildConfigField("String", "RELAY_URL", "\"${relayUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        getByName("release") {
            manifestPlaceholders["xiaomiSuperIslandDebug"] = "false"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")

    testImplementation("junit:junit:4.13.2")
}

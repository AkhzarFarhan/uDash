import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.utility.dashcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.utility.dashcam"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            val localProps = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                localProps.load(localPropsFile.inputStream())
            }
            buildConfigField(
                "String",
                "YOUTUBE_CLIENT_ID",
                "\"${localProps.getProperty("YT_CLIENT_ID", "")}\""
            )
            buildConfigField(
                "String",
                "YOUTUBE_CLIENT_SECRET",
                "\"${localProps.getProperty("YT_CLIENT_SECRET", "")}\""
            )
            buildConfigField(
                "String",
                "YOUTUBE_REFRESH_TOKEN",
                "\"${localProps.getProperty("YT_REFRESH_TOKEN", "")}\""
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val localProps = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                localProps.load(localPropsFile.inputStream())
            }
            buildConfigField(
                "String",
                "YOUTUBE_CLIENT_ID",
                "\"${localProps.getProperty("YT_CLIENT_ID", "")}\""
            )
            buildConfigField(
                "String",
                "YOUTUBE_CLIENT_SECRET",
                "\"${localProps.getProperty("YT_CLIENT_SECRET", "")}\""
            )
            buildConfigField(
                "String",
                "YOUTUBE_REFRESH_TOKEN",
                "\"${localProps.getProperty("YT_REFRESH_TOKEN", "")}\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Persistence (Room)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Concurrency & Background Processing
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Video Processing
    implementation("io.github.trongnhan136:ffmpeg-kit-min-gpl:7.1.5")
    implementation("com.arthenica:smart-exception-common:0.2.1")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Network & Authentication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.apis:google-api-services-youtube:v3-rev20231011-2.0.0")

    // Google Sign-In (for in-app YouTube OAuth flow)
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

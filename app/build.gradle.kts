plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseBannerId = providers.gradleProperty("yandexBannerId").orElse(providers.environmentVariable("YANDEX_BANNER_ID")).orElse("")
val keyStorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
val signingValues = listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "com.jonkryl.sumpath"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jonkryl.sumpath"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.0.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://jonkryl.github.io/sum-path/privacy/\"")
    }
    signingConfigs {
        if (keyStorePath != null && signingValues.values.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(keyStorePath)
                storePassword = signingValues["KEYSTORE_PASSWORD"]
                keyAlias = signingValues["KEY_ALIAS"]
                keyPassword = signingValues["KEY_PASSWORD"]
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "YANDEX_BANNER_ID", "\"demo-banner-yandex\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "YANDEX_BANNER_ID", "\"${releaseBannerId.get()}\"")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; checkReleaseBuilds = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        check(Regex("R-M-\\d+-\\d+").matches(releaseBannerId.get())) {
            "Release requires a real Yandex banner ID in YANDEX_BANNER_ID / yandexBannerId. Demo IDs are debug-only."
        }
        check(keyStorePath != null && file(keyStorePath).isFile && signingValues.values.all { !it.isNullOrBlank() }) {
            "Release requires KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD from protected GitHub secrets."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseConfiguration)
}

dependencies {
    implementation("com.yandex.android:mobileads:8.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

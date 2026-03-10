import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "me.rerere.rikkahub.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":ai"))
    implementation(project(":common"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.paging.runtime)

    // Room
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Serialization
    api(libs.kotlinx.serialization.json)

    // Utils
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.quickjs)

    // Chaquopy Runtime for compilation
    // 我们使用 compileOnly 因为实际的 python 环境由 app 模块提供
    compileOnly("com.chaquo.python:gradle:${libs.versions.chaquopy.get()}")
}

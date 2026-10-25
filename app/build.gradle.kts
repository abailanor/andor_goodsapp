plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    // MigrationTestHelper 要从 assets 里读 `app/schemas/` 下的历史 schema JSON，
    // 所以把那个目录挂成 androidTest 的 assets 源。少了这一步
    // `createDatabase(name, 3)` 会抛 IOException 找不到 schema。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.all {
            // 把命令行的 `-Ds1.base=...` / `-Ds1.data=...` 透传给测试 JVM。
            // ByteIdentityGateTest（方案 §7.2 硬验收门）靠它们指向真服务端，
            // 而 Gradle 默认**不**把 -D 传给 test JVM —— 少了这段，那道门只能在
            // IDE 的 Run Configuration 里填参，命令行与 CI 都驱动不了。
            System.getProperties()
                .filterKeys { it.toString().startsWith("s1.") }
                .forEach { (k, v) -> it.systemProperty(k.toString(), v) }
        }
    }
}

// Room 把每个版本的 schema 导出成 JSON 到 app/schemas/，**这个目录要进 git**。
// 有了它 MigrationTestHelper 才能造出"真实的 v3 库"来验证 3→4 的迁移，
// 否则迁移测试只能对着自己写的假 DDL 自说自话。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    // Room数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    ksp(libs.androidx.room.compiler)

    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Gson
    implementation(libs.gson)

    // 协程（StripedMutex / 同步引擎）
    implementation(libs.kotlinx.coroutines.android)

    // 网络：普通 API 与 SSE 用**不同**的 OkHttpClient（SSE 必须 readTimeout=0）
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // MigrationTestHelper 是 instrumented 的，迁移测试跑在 androidTest
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
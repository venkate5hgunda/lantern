import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lantern.peer"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.lantern.peer"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("release") {
            System.getenv("LANTERN_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("LANTERN_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LANTERN_KEY_ALIAS")
            keyPassword = System.getenv("LANTERN_KEY_PASSWORD")
        }
    }
    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = false
        isDebuggable = false
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    androidResources { noCompress += "tflite" }
    packaging.resources.excludes.add("META-INF/versions/**")
    sourceSets.getByName("main").assets.srcDir("../../web/dist")
}

val checkViewerAssets = tasks.register("checkViewerAssets") {
    doLast {
        val model = file("src/main/assets/models/efficientdet-lite0.tflite")
        check(model.isFile && MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) } == "2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b") { "Person model missing or hash mismatch; see assets/models/README.md" }
        check(file("../../web/dist/index.html").isFile && file("../../web/dist/review.html").isFile && file("../../web/dist/sw.js").isFile) {
            "Browser assets missing: build lantern/web first (npm run build)."
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkViewerAssets) }
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    doLast {
        for (name in listOf("LANTERN_KEYSTORE_PATH", "LANTERN_KEYSTORE_PASSWORD", "LANTERN_KEY_ALIAS", "LANTERN_KEY_PASSWORD")) {
            check(!System.getenv(name).isNullOrBlank()) { "Release signing requires $name; see docs/releases.md" }
        }
        check(file(System.getenv("LANTERN_KEYSTORE_PATH")).isFile) { "Release keystore not found" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
tasks.withType<Test>().configureEach {
    val scratch = layout.buildDirectory.dir("test-runtime").get().asFile
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
}

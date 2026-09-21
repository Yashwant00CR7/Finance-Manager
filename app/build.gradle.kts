import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yk.finance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yk.finance"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "2.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        // The drawer shows the running version, so BuildConfig has to be generated.
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * Fails the build if the merged manifest asks for INTERNET.
 *
 * The manifest declares tools:node="remove", which strips the permission however it
 * got there - but a directive nobody checks is a comment. This reads the manifest the
 * APK is actually built from, which is the only artifact that can answer the question.
 */
abstract class VerifyNoInternetPermission : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        if (manifest.readText().contains("android.permission.INTERNET")) {
            throw GradleException(
                "INTERNET permission present in ${manifest.path}.\n" +
                    "This app's privacy claim is that bank data cannot leave the device, and " +
                    "that claim is only worth anything while it is verifiable. If a dependency " +
                    "needs the network, that is a decision to take deliberately - not one to " +
                    "let through a manifest merge.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val verify = tasks.register<VerifyNoInternetPermission>(
            "verifyNoInternetPermission${variant.name.replaceFirstChar { it.uppercase() }}",
        ) {
            group = "verification"
            description = "Fails if the merged manifest grants INTERNET."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}

// Room writes the schema JSON here on every build. v1 shipped with exportSchema off,
// so no v1 file exists and its migration had to be verified by hand; from v2 onward
// every version is on disk and future migrations can be tested properly.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

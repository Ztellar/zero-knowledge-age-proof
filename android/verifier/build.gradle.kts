plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    testImplementation(libs.junit)
}

// The same mock card the prover tests use.
sourceSets.test {
    resources.srcDir("../../prover/fixtures/out")
}

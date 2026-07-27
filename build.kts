@KotlinBuildScript("https://tools.kotlin.build/")
@file:WithArtifact("kompile:build-kotlin-jvm:0.0.1")
package w3wallet.browser.testharness

import build.kotlin.withartifact.WithArtifact
import java.io.File
import build.kotlin.jvm.*
import build.kotlin.annotations.MavenArtifactCoordinates

val dependencies = resolveDependencies(
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22"),
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22"),
    MavenPrebuilt("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22"),
    MavenPrebuilt("org.apache.commons:commons-compress:1.26.1"),
    MavenPrebuilt("org.tukaani:xz:1.9"),
    MavenPrebuilt("commons-io:commons-io:2.16.1"),
)

@MavenArtifactCoordinates("w3wallet.browser.testharness:W3WalletBrowserTestHarness:")
fun buildMaven(): File {
    return buildSimpleKotlinMavenArtifact(
        coordinates = "w3wallet.browser.testharness:W3WalletBrowserTestHarness:0.0.16",
        src = File("src"),
        compileDependencies = dependencies,
    )
}

fun buildSkinnyJar(): File = buildMaven().jar

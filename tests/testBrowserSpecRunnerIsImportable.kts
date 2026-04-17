@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.9.22")
@file:WithArtifact("w3wallet.browser.testharness.buildMaven()")

package w3wallet.browser.testharness.tests

import build.kotlin.withartifact.WithArtifact
import kotlin.test.assertEquals
import w3wallet.browser.testharness.BrowserSpecRunner

/**
 * Smoke test: the harness's public entry point (`BrowserSpecRunner.runSpec`)
 * must be importable by consumers.
 *
 * A real integration test of `runSpec` would need a fully-checked-out
 * consumer repository with a demo fat JAR, W3WalletDaemon clone, and a
 * W3WalletExtension dist — end-to-end behaviour is verified from inside
 * each consuming project's own `tests/*.kts`. This test only guards the
 * artifact itself: if the Maven artifact fails to publish, or if
 * `BrowserSpecRunner` moves package or accidentally becomes private,
 * kompile fails this test.
 */
fun testBrowserSpecRunnerIsImportable() {
    // Access the singleton so the classloader resolves it.
    val runner = BrowserSpecRunner
    assertEquals("BrowserSpecRunner", runner::class.simpleName)
    // Public entry point must exist. We call it with a path that does
    // not exist (no playwright.config.ts in the test's working dir) and
    // expect it to throw, confirming the signature without actually
    // orchestrating anything.
    var threw = false
    try {
        runner.runSpec("testThatDoesNotExist.spec.ts")
    } catch (e: IllegalStateException) {
        threw = true
    } catch (e: Throwable) {
        // Any throwable from runSpec is acceptable for this smoke test —
        // we only care that the method exists and is reachable. Re-check
        // with a stricter expectation only once we have a real fixture.
        threw = true
    }
    require(threw) { "runSpec should fail when no consumer repo is in scope" }
}

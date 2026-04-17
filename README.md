# W3WalletBrowserTestHarness

Kotlin test harness for the W3Wallet browser-extension Playwright suite.

## What it does

Consumers of this harness have Playwright `*.spec.ts` files under `tests/` and a corresponding `tests/*.kts` wrapper per spec so that kompile-cli discovers them. Each wrapper delegates to this library:

```kotlin
@file:WithArtifact("w3wallet.browser.testharness:W3WalletBrowserTestHarness:0.0.1")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.9.22")

package your.project.tests

import build.kotlin.withartifact.WithArtifact
import w3wallet.browser.testharness.BrowserSpecRunner

fun testMintViaServerFlow() {
    BrowserSpecRunner.runSpec("testMintViaServerFlow.spec.ts")
}
```

A single call to `BrowserSpecRunner.runSpec` handles:

1. Repo-root lookup (`W3WALLET_DEMO_ROOT` env → walk up → `find` under common dirs).
2. Demo fat JAR build (`./gradlew fatJar`, cached).
3. W3WalletDaemon fat JAR build (`git clone`, `./gradlew fatJar`, cached under `.deps/`).
4. W3WalletExtension `dist/` build (`git clone`, `npm ci`, `npm run build`, cached under `.deps/`).
5. `npx playwright install chromium`.
6. Daemon start on a per-spec port (hashed from the spec name).
7. Demo-server start on a per-spec port.
8. `npx playwright test tests/<spec>` with `DEMO_URL`, `DAEMON_URL`, `DAEMON_WS_URL`, `DAEMON_SQLITE_PATH`, `EXTENSION_DIST_DIR`, `DAEMON_JAR` exported.
9. Deterministic tear-down of daemon + demo.

Runs identically on the `kotlin.build (remote)` droplet and on a GitHub Actions runner — the only prereqs are Node 20, `git`, and a JDK.

## Why a separate repo

`test.kts` files cannot share a helper via a sibling `.kts` in the same `tests/` directory ([TESTING.md: Every Test Must Be Fully Isolated](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md#every-test-must-be-fully-isolated)). Substantial test-infrastructure code must live in its own repository and be imported via `@file:WithArtifact`. This project is that repository for the W3Wallet browser suite.

## Building

```bash
scripts/build.bash
```

## Tests

```bash
scripts/test.bash --test .
```

Only the smoke-level "is the artifact importable" test lives here. End-to-end behaviour is exercised by each consuming project's own `tests/*.kts`.

## Publishing

Uses the standard kompile pipeline: `kotlin.build (remote)` builds and verifies; the artifact is published to `kotlin.directory` at coordinates `w3wallet.browser.testharness:W3WalletBrowserTestHarness:<version>`.

## See also

- [W3JvmServerSideWalletDemo](https://github.com/CodexCoder21Organization/W3JvmServerSideWalletDemo) — the first consumer.
- [W3WalletTestHarness](https://github.com/CodexCoder21Organization/W3WalletTestHarness) — the TypeScript-side harness the Playwright specs themselves import.
- [W3WalletDaemon](https://github.com/CodexCoder21Organization/W3WalletDaemon) — the daemon started by this harness.
- [W3WalletExtension](https://github.com/CodexCoder21Organization/W3WalletExtension) — the Chromium extension the Playwright specs load.

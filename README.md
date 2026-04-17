# W3WalletBrowserTestHarness
Kotlin test harness for browser-extension e2e specs — builds daemon/demo JARs, clones and builds W3WalletExtension, starts daemon+demo, installs Playwright+Chromium, and runs a single spec. Published to kotlin.directory so each consumer's test.kts can pull it in via @file:WithArtifact.

package w3wallet.browser.testharness

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.concurrent.thread

/**
 * Runs a single Playwright browser-e2e spec from a kompile test.kts.
 *
 * The consumer's `tests/testXxx.kts` file adds
 *
 * ```
 * @file:WithArtifact("w3wallet.browser.testharness:W3WalletBrowserTestHarness:<ver>")
 * ...
 * fun testXxx() {
 *     BrowserSpecRunner.runSpec("testXxx.spec.ts")
 * }
 * ```
 *
 * This single call handles the entire lifecycle:
 *
 *   1. Resolve the consumer's repo root (env var → walk up from cwd → `find`).
 *   2. Ensure a demo fat JAR exists (gradle fatJar, cached on disk).
 *   3. Fetch W3WalletDaemon's classpath from kotlin.directory via coursier.
 *   4. Fetch W3WalletExtension dist/ from kotlin.directory (as a JAR) and
 *      extract it into a local directory for Chromium --load-extension=.
 *   5. Ensure Playwright + Chromium are installed (`npx playwright install
 *      chromium`, skipped when the cache is already populated).
 *   6. Start a fresh daemon on a per-spec port via
 *      `java -cp <daemon-classpath> w3wallet.daemon.MainKt`.
 *   7. Start a fresh demo on a per-spec port.
 *   8. Invoke `npx playwright test tests/<spec>` with the env vars the
 *      w3wallet-test-harness npm package reads.
 *   9. Tear down the daemon and demo processes.
 *
 * Deliberately avoids `git clone` so the harness works on any kompile
 * environment — GitHub Actions runners AND the shared kotlin.build
 * droplet — without needing credentials for the private source repos.
 *
 * kompile spawns each test in a separate JVM, so daemon+demo are always
 * fresh; the coursier cache amortizes the dependency downloads across
 * tests in the same run.
 */
object BrowserSpecRunner {

    /** kotlin.directory Maven coordinates for the daemon the tests exercise. */
    private const val DAEMON_COORDINATES = "com.w3wallet:W3WalletDaemon:1.0.3"

    /** kotlin.directory Maven coordinates for the prebuilt extension dist/. */
    private const val EXTENSION_DIST_COORDINATES = "com.w3wallet:W3WalletExtensionDist:0.0.1"

    private const val KOTLIN_DIRECTORY_REPO = "https://kotlin.directory"

    /** Fully-qualified main class name for [DAEMON_COORDINATES]. */
    private const val DAEMON_MAIN_CLASS = "w3wallet.daemon.MainKt"

    /**
     * Run the Playwright spec whose file name is [specFileBasename]
     * (e.g. `"testMintViaServerFlow.spec.ts"`). Throws [IllegalStateException]
     * on any orchestration failure or a non-zero Playwright exit.
     */
    fun runSpec(specFileBasename: String) {
        val repoRoot = findRepoRoot()
        require(File(repoRoot, "tests/$specFileBasename").isFile) {
            "Spec file not found: tests/$specFileBasename under $repoRoot. " +
                "Present specs: ${File(repoRoot, "tests")
                    .listFiles()?.map { it.name }?.filter { it.endsWith(".spec.ts") }?.sorted() ?: "none"}"
        }

        val deps = resolveDependencies(repoRoot)
        ensurePlaywrightInstalled(repoRoot)

        val port = derivePort(specFileBasename, prefix = "daemon")
        val demoPort = derivePort(specFileBasename, prefix = "demo")
        val runDir = File(repoRoot, ".run-$specFileBasename").apply { mkdirs() }

        val daemon = startDaemon(deps, runDir, port)
        try {
            val demo = startDemo(deps, runDir, demoPort)
            try {
                runPlaywright(
                    repoRoot = repoRoot,
                    spec = specFileBasename,
                    demoUrl = "http://localhost:$demoPort",
                    daemonUrl = daemon.daemonUrl,
                    daemonWsUrl = "ws://localhost:$port/ws",
                    daemonDbPath = daemon.dbPath,
                    extensionDistDir = deps.extensionDistDir,
                    daemonClasspath = deps.daemonClasspath,
                )
            } finally {
                demo.stop()
            }
        } finally {
            daemon.stop()
        }
    }

    // ---- Repo root lookup ---------------------------------------------------

    private fun findRepoRoot(): File {
        val envRoot = System.getenv("W3WALLET_DEMO_ROOT")?.let { File(it) }
        if (envRoot != null && File(envRoot, "playwright.config.ts").isFile) return envRoot

        var dir: File? = File(".").absoluteFile.canonicalFile
        while (dir != null) {
            if (File(dir, "playwright.config.ts").isFile) return dir
            dir = dir.parentFile
        }

        for (root in listOf("/github/workspace", "/__w", "/root", "/home", "/workspace", "/tmp")) {
            val rootFile = File(root)
            if (!rootFile.isDirectory) continue
            val result = runCommand(
                listOf(
                    "find", rootFile.absolutePath,
                    "-maxdepth", "6",
                    "-name", "playwright.config.ts",
                    "-not", "-path", "*/node_modules/*",
                ),
                cwd = rootFile,
                captureOutput = true,
                failOnNonZero = false,
            )
            val hit = result.stdout.lineSequence().firstOrNull { it.isNotBlank() && File(it).isFile }
            if (hit != null) return File(hit).parentFile
        }

        error(
            "Could not locate playwright.config.ts. Set W3WALLET_DEMO_ROOT to the " +
                "consumer repo root, or run from inside the checkout. " +
                "cwd=${File(".").absoluteFile.canonicalFile}",
        )
    }

    // ---- Dependencies: demo jar (local gradle), daemon + extension (Maven) --

    private data class ResolvedDeps(
        val repoRoot: File,
        val demoJar: File,
        val daemonClasspath: String,
        val extensionDistDir: File,
    )

    private fun resolveDependencies(repoRoot: File): ResolvedDeps {
        val demoJar = System.getenv("DEMO_JAR")?.let { File(it) }
            ?.takeIf { it.isFile }
            ?: buildDemoJar(repoRoot)
        val daemonClasspath = fetchDaemonClasspath()
        val extensionDistDir = fetchExtensionDist(repoRoot)
        return ResolvedDeps(repoRoot, demoJar, daemonClasspath, extensionDistDir)
    }

    private fun buildDemoJar(repoRoot: File): File {
        val libs = File(repoRoot, "build/libs")
        val cached = libs.takeIf { it.isDirectory }
            ?.listFiles { f -> f.name.endsWith("-all.jar") }
            ?.firstOrNull()
        if (cached != null) {
            println("[harness] Reusing cached demo fat JAR: ${cached.name}")
            return cached
        }
        println("[harness] Building demo fat JAR under $repoRoot")
        runCommand(
            listOf("./gradlew", "fatJar", "-x", "test", "--no-daemon"),
            cwd = repoRoot,
        )
        return libs.listFiles { f -> f.name.endsWith("-all.jar") }?.firstOrNull()
            ?: error("demo fat JAR not found under $libs after gradle fatJar")
    }

    private fun fetchDaemonClasspath(): String {
        // Cached between tests in the same run by coursier itself.
        println("[harness] Resolving $DAEMON_COORDINATES classpath via coursier")
        val result = runCommand(
            listOf(
                "coursier", "fetch",
                "-r", KOTLIN_DIRECTORY_REPO,
                DAEMON_COORDINATES,
                "--classpath",
            ),
            cwd = File("."),
            captureOutput = true,
        )
        return result.stdout.trim().lineSequence().lastOrNull { it.contains(':') && it.contains(".jar") }
            ?: error(
                "coursier fetch --classpath returned no classpath line for $DAEMON_COORDINATES. " +
                    "stdout:\n${result.stdout.takeLast(2048)}",
            )
    }

    private fun fetchExtensionDist(repoRoot: File): File {
        val cacheDir = File(repoRoot, ".harness-cache/extension-dist")
        val marker = File(cacheDir, ".ok")
        if (marker.isFile) {
            println("[harness] Reusing cached extension dist at $cacheDir")
            return cacheDir
        }
        println("[harness] Fetching $EXTENSION_DIST_COORDINATES")
        val result = runCommand(
            listOf(
                "coursier", "fetch",
                "-r", KOTLIN_DIRECTORY_REPO,
                EXTENSION_DIST_COORDINATES,
            ),
            cwd = File("."),
            captureOutput = true,
        )
        // `coursier fetch` (without --classpath) prints one JAR path per line.
        val jarPath = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(".jar") && it.contains("W3WalletExtensionDist") }
            ?: error(
                "coursier fetch could not locate the W3WalletExtensionDist JAR. " +
                    "stdout:\n${result.stdout.takeLast(2048)}",
            )

        if (cacheDir.exists()) cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        JarFile(File(jarPath)).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("META-INF/")) continue
                val out = File(cacheDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        require(File(cacheDir, "manifest.json").isFile) {
            "Extracted extension dist is missing manifest.json at $cacheDir — wrong artifact?"
        }
        marker.writeText("ok\n")
        return cacheDir
    }

    private fun ensurePlaywrightInstalled(repoRoot: File) {
        if (!File(repoRoot, "node_modules/@playwright/test").isDirectory) {
            println("[harness] Installing browser-e2e npm deps")
            File(repoRoot, "package-lock.json").delete()
            runCommand(listOf("npm", "install", "--no-audit", "--no-fund"), cwd = repoRoot)
        } else {
            println("[harness] Reusing cached node_modules")
        }
        val playwrightCache = File(System.getProperty("user.home"), ".cache/ms-playwright")
        val chromiumInstalled = playwrightCache.isDirectory &&
            (playwrightCache.listFiles()?.any { it.name.startsWith("chromium-") } == true)
        if (chromiumInstalled) {
            println("[harness] Reusing cached Playwright Chromium")
        } else {
            println("[harness] Installing Playwright Chromium")
            runCommand(
                listOf("npx", "playwright", "install", "chromium"),
                cwd = repoRoot,
                timeoutSeconds = 600,
            )
        }
    }

    // ---- Daemon / demo lifecycle -------------------------------------------

    private class ProcessHandle(
        private val process: Process,
        val label: String,
    ) {
        fun stop() {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private class DaemonHandle(
        private val handle: ProcessHandle,
        val daemonUrl: String,
        val dbPath: File,
    ) {
        fun stop() = handle.stop()
    }

    private fun startDaemon(deps: ResolvedDeps, runDir: File, port: Int): DaemonHandle {
        val dbPath = File(runDir, "wallet.db")
        dbPath.delete()
        File(runDir, "wallet.db-journal").delete()
        val peersDir = File(runDir, "daemon-peers").apply { mkdirs() }
        val logFile = File(runDir, "daemon.log")

        println("[harness] Starting W3WalletDaemon on port $port (cp via coursier)")
        val process = ProcessBuilder(
            "java", "-cp", deps.daemonClasspath, DAEMON_MAIN_CLASS,
            "--port", port.toString(),
            "--db", dbPath.absolutePath,
            "--peers-dir", peersDir.absolutePath,
        )
            .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            .redirectErrorStream(true)
            .start()

        val handle = ProcessHandle(process, "daemon")
        val daemonUrl = try {
            waitForLogMatch(
                logFile = logFile,
                process = process,
                label = "daemon",
                regex = Regex("""url://w3wallet\.daemon\.[A-Za-z0-9]+/?"""),
                timeoutSeconds = 60,
            )
        } catch (t: Throwable) {
            handle.stop()
            throw t
        }

        return DaemonHandle(handle, daemonUrl, dbPath)
    }

    private fun startDemo(deps: ResolvedDeps, runDir: File, port: Int): ProcessHandle {
        val logFile = File(runDir, "demo.log")
        println("[harness] Starting WalletDemoServer on port $port")
        val process = ProcessBuilder(
            "java", "-jar", deps.demoJar.absolutePath,
            "--port", port.toString(),
        )
            .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            .redirectErrorStream(true)
            .start()

        val handle = ProcessHandle(process, "demo")
        try {
            waitForLogMatch(
                logFile = logFile,
                process = process,
                label = "demo",
                regex = Regex("""WalletDemoServer started on port $port"""),
                timeoutSeconds = 30,
            )
        } catch (t: Throwable) {
            handle.stop()
            throw t
        }
        return handle
    }

    private fun waitForLogMatch(
        logFile: File,
        process: Process,
        label: String,
        regex: Regex,
        timeoutSeconds: Int,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val log = if (logFile.isFile) logFile.readText().takeLast(4096) else "(no log)"
                error("$label exited before emitting expected log. Last 4 KiB:\n$log")
            }
            if (logFile.isFile) {
                val match = regex.find(logFile.readText())
                if (match != null) return match.value
            }
            Thread.sleep(200)
        }
        val log = if (logFile.isFile) logFile.readText().takeLast(4096) else "(no log)"
        error(
            "$label did not emit log matching ${regex.pattern} within ${timeoutSeconds}s. " +
                "Last 4 KiB of log:\n$log",
        )
    }

    // ---- Playwright invocation ---------------------------------------------

    private fun runPlaywright(
        repoRoot: File,
        spec: String,
        demoUrl: String,
        daemonUrl: String,
        daemonWsUrl: String,
        daemonDbPath: File,
        extensionDistDir: File,
        daemonClasspath: String,
    ) {
        val env = mapOf(
            "DEMO_URL" to demoUrl,
            "DAEMON_URL" to daemonUrl,
            "DAEMON_WS_URL" to daemonWsUrl,
            "DAEMON_SQLITE_PATH" to daemonDbPath.absolutePath,
            "EXTENSION_DIST_DIR" to extensionDistDir.absolutePath,
            // DAEMON_JAR is a legacy pointer some specs inspect; now it's a
            // classpath string, not a single JAR, so they must not pass it
            // directly to `java -jar`. Kept for backward compatibility with
            // specs that only read it to decide whether to self-launch.
            "DAEMON_CLASSPATH" to daemonClasspath,
        )

        val useXvfb = System.getenv("CI") == "true" &&
            System.getenv("DISPLAY").isNullOrBlank() &&
            hasCommand("xvfb-run")
        val cmd = if (useXvfb) {
            listOf("xvfb-run", "-a", "npx", "playwright", "test", "tests/$spec")
        } else {
            listOf("npx", "playwright", "test", "tests/$spec")
        }
        println("[harness] Running Playwright: ${cmd.joinToString(" ")}")
        val exit = runCommand(cmd, cwd = repoRoot, extraEnv = env, failOnNonZero = false).exitCode
        if (exit != 0) {
            error("Playwright spec '$spec' failed with exit code $exit")
        }
    }

    // ---- Low-level helpers -------------------------------------------------

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommand(
        command: List<String>,
        cwd: File,
        extraEnv: Map<String, String> = emptyMap(),
        captureOutput: Boolean = false,
        failOnNonZero: Boolean = true,
        timeoutSeconds: Int = 1200,
    ): CommandResult {
        val pb = ProcessBuilder(command).directory(cwd)
        pb.environment().putAll(extraEnv)
        if (!captureOutput) {
            pb.inheritIO()
        } else {
            pb.redirectErrorStream(false)
        }
        val process = pb.start()
        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()
        val stdoutThread = if (captureOutput) thread(start = true) {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { stdoutSb.appendLine(it) } }
        } else null
        val stderrThread = if (captureOutput) thread(start = true) {
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderrSb.appendLine(it) } }
        } else null

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Command ${command.joinToString(" ")} did not finish within ${timeoutSeconds}s")
        }
        stdoutThread?.join()
        stderrThread?.join()

        val exit = process.exitValue()
        if (exit != 0 && failOnNonZero) {
            error(
                "Command ${command.joinToString(" ")} (cwd=$cwd) failed with exit $exit. " +
                    "stderr:\n${stderrSb.toString().takeLast(4096)}",
            )
        }
        return CommandResult(exit, stdoutSb.toString(), stderrSb.toString())
    }

    private fun hasCommand(name: String): Boolean =
        try {
            runCommand(
                listOf("sh", "-c", "command -v $name"),
                cwd = File("."),
                captureOutput = true,
                failOnNonZero = false,
            ).exitCode == 0
        } catch (_: IOException) {
            false
        }

    private fun derivePort(spec: String, prefix: String): Int {
        var hash = 0
        for (c in "$prefix-$spec") {
            hash = (hash * 31 + c.code) and 0x7fffffff
        }
        return 20000 + (hash % 10000)
    }
}

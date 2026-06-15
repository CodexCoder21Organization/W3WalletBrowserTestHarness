package w3wallet.browser.testharness

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.concurrent.thread
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

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
    private const val DAEMON_COORDINATES = "com.w3wallet:W3WalletDaemon:1.0.4"

    /** kotlin.directory Maven coordinates for the prebuilt extension dist/. */
    private const val EXTENSION_DIST_COORDINATES = "com.w3wallet:W3WalletExtensionDist:0.0.3"

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

        // Serialize first-run bootstrap across parallel kompile test JVMs.
        // kotlin.build invokes kompile-cli directly (no `scripts/test.bash`
        // runs beforehand) and spawns multiple test JVMs in parallel; without
        // a lock each JVM would independently download Node, run npm
        // install, and fetch Playwright's Chromium, thrashing the network
        // and blowing through every @Timeout.
        val (deps, nodeBin) = withBootstrapLock(repoRoot) {
            val d = resolveDependencies(repoRoot)
            val n = ensureNodeOnPath(repoRoot)
            ensurePlaywrightInstalled(repoRoot, n)
            d to n
        }

        val port = derivePort(specFileBasename, prefix = "daemon")
        val demoPort = derivePort(specFileBasename, prefix = "demo")
        val runDir = File(repoRoot, ".run-$specFileBasename").apply { mkdirs() }

        // Each test gets its own copy of the extension dist/ so we can
        // stamp a per-test daemon URL into the manifest without racing
        // with sibling tests. IPv4-pinned for the same reason
        // DEMO_URL / DAEMON_WS_URL below are.
        val perTestExtensionDir = preparePerTestExtensionDir(
            deps.extensionDistDir,
            runDir,
            daemonWsUrl = "ws://127.0.0.1:$port/ws",
        )

        val daemon = startDaemon(deps, runDir, port)
        try {
            val demo = startDemo(deps, runDir, demoPort, daemon.directMultiaddr)
            try {
                // Pin IPv4 loopback — on the kotlin.build droplet `localhost`
                // resolves to ::1 first, and W3WalletDaemon only listens on
                // 127.0.0.1 by default. Playwright's extension then hits
                // `ECONNREFUSED ::1:<port>` because nothing is bound to v6.
                runPlaywright(
                    repoRoot = repoRoot,
                    spec = specFileBasename,
                    demoUrl = "http://127.0.0.1:$demoPort",
                    daemonUrl = daemon.daemonUrl,
                    daemonWsUrl = "ws://127.0.0.1:$port/ws",
                    daemonDbPath = daemon.dbPath,
                    extensionDistDir = perTestExtensionDir,
                    daemonClasspath = deps.daemonClasspath,
                    nodeBin = nodeBin,
                )
            } finally {
                demo.stop()
            }
        } finally {
            daemon.stop()
        }
    }

    /**
     * Copy the cached extension dist into a per-test directory and stamp the
     * per-spec daemon WebSocket URL into the manifest's `w3wallet_daemon_url`
     * field. W3WalletExtension 0.0.2+ reads that field at startup
     * (`chrome.runtime.getManifest().w3wallet_daemon_url`), so each test's
     * Chromium talks to its own daemon instance without parallel tests
     * colliding on a shared port.
     */
    private fun preparePerTestExtensionDir(
        cachedDistDir: File,
        runDir: File,
        daemonWsUrl: String,
    ): File {
        val perTestDir = File(runDir, "extension")
        if (perTestDir.exists()) perTestDir.deleteRecursively()
        perTestDir.mkdirs()
        cachedDistDir.copyRecursively(perTestDir, overwrite = true)

        val manifest = File(perTestDir, "manifest.json")
        require(manifest.isFile) {
            "Extension dist at $perTestDir is missing manifest.json — did the cached dist extract correctly?"
        }
        val original = manifest.readText()
        // Use a literal string match against the manifest's declared default
        // so we never accidentally alter any unrelated URL in the manifest.
        val marker = "\"w3wallet_daemon_url\""
        require(original.contains(marker)) {
            "Extension manifest at $manifest does not declare $marker — the harness " +
                "requires W3WalletExtensionDist 0.0.2 or later, which exposes the " +
                "daemon URL as a manifest key."
        }
        // Preserve formatting — swap only the value string.
        val patched = Regex(""""w3wallet_daemon_url"\s*:\s*"[^"]*"""")
            .replace(original, "\"w3wallet_daemon_url\": \"$daemonWsUrl\"")
        manifest.writeText(patched)
        println("[harness] Stamped $daemonWsUrl into ${manifest.absolutePath}")
        return perTestDir
    }

    /**
     * Run [block] while holding an exclusive file lock at
     * `.harness-cache/bootstrap.lock`. Parallel test JVMs serialize here so
     * only one ever performs the Node + npm + Playwright setup; the others
     * block until the holder releases and then observe a warm cache.
     */
    private fun <T> withBootstrapLock(repoRoot: File, block: () -> T): T {
        val cacheDir = File(repoRoot, ".harness-cache").apply { mkdirs() }
        val lockFile = File(cacheDir, "bootstrap.lock")
        val raf = java.io.RandomAccessFile(lockFile, "rw")
        val channel = raf.channel
        println("[harness] Acquiring bootstrap lock at $lockFile")
        val lock = channel.lock()
        try {
            println("[harness] Bootstrap lock acquired")
            return block()
        } finally {
            try { lock.release() } catch (_: Throwable) {}
            try { channel.close() } catch (_: Throwable) {}
            try { raf.close() } catch (_: Throwable) {}
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
        val coursier = ensureCoursier(repoRoot)
        val daemonClasspath = fetchDaemonClasspath(repoRoot, coursier)
        val extensionDistDir = fetchExtensionDist(repoRoot, coursier)
        return ResolvedDeps(repoRoot, demoJar, daemonClasspath, extensionDistDir)
    }

    /**
     * Return the absolute path of a usable `coursier` binary, downloading one
     * into the harness cache when neither `$PATH` nor the consumer's
     * `jars/coursier` has it. kompile test JVMs on the kotlin.build droplet
     * don't inherit the outer shell's PATH, so we cannot rely on the system
     * install that the droplet image provides; making the harness
     * self-sufficient removes an entire class of "works on one env, not the
     * other" flakes.
     */
    private fun ensureCoursier(repoRoot: File): String {
        val onPath = runCommand(
            listOf("sh", "-c", "command -v coursier"),
            cwd = File("."),
            captureOutput = true,
            failOnNonZero = false,
        )
        if (onPath.exitCode == 0) {
            val path = onPath.stdout.trim()
            if (path.isNotEmpty() && File(path).canExecute()) {
                println("[harness] Using coursier from PATH: $path")
                return path
            }
        }
        val consumerLocal = File(repoRoot, "jars/coursier")
        if (consumerLocal.canExecute()) {
            println("[harness] Using coursier from consumer jars/: $consumerLocal")
            return consumerLocal.absolutePath
        }
        val cached = File(repoRoot, ".harness-cache/coursier")
        if (!cached.canExecute()) {
            println("[harness] Downloading coursier into $cached")
            cached.parentFile.mkdirs()
            runCommand(
                listOf(
                    "curl", "-fLo", cached.absolutePath,
                    "https://github.com/coursier/launchers/raw/master/coursier",
                ),
                cwd = File("."),
            )
            cached.setExecutable(true)
        }
        return cached.absolutePath
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

    private fun fetchDaemonClasspath(repoRoot: File, coursier: String): String {
        // Persist the resolved classpath string across per-test JVMs so
        // only the first test pays for the cold-cache `coursier fetch`
        // (which can take 5+ minutes the first time it downloads jvm-libp2p,
        // netty, bouncycastle, etc. on a fresh droplet).
        val cacheFile = File(repoRoot, ".harness-cache/daemon-classpath.txt")
        if (cacheFile.isFile) {
            val cached = cacheFile.readText().trim()
            if (cached.isNotEmpty() && cached.split(':').all { File(it).isFile }) {
                println("[harness] Reusing cached daemon classpath ($cacheFile)")
                return cached
            }
            println("[harness] Stale daemon-classpath cache; refetching")
        }
        println("[harness] Resolving $DAEMON_COORDINATES classpath via coursier")
        val result = runCommand(
            listOf(
                coursier, "fetch",
                "-r", KOTLIN_DIRECTORY_REPO,
                DAEMON_COORDINATES,
                "--classpath",
            ),
            cwd = File("."),
            captureOutput = true,
        )
        val cp = result.stdout.trim().lineSequence()
            .lastOrNull { it.contains(':') && it.contains(".jar") }
            ?: error(
                "coursier fetch --classpath returned no classpath line for $DAEMON_COORDINATES. " +
                    "stdout:\n${result.stdout.takeLast(2048)}",
            )
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(cp)
        return cp
    }

    private fun fetchExtensionDist(repoRoot: File, coursier: String): File {
        val cacheDir = File(repoRoot, ".harness-cache/extension-dist")
        val marker = File(cacheDir, ".ok")
        if (marker.isFile) {
            println("[harness] Reusing cached extension dist at $cacheDir")
            return cacheDir
        }
        println("[harness] Fetching $EXTENSION_DIST_COORDINATES")
        val result = runCommand(
            listOf(
                coursier, "fetch",
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

    /**
     * Return a PATH entry that has `node` and `npm` on it. Prefers the
     * system install. If neither is found (as on the kotlin.build droplet),
     * downloads an official Node.js 20 tarball into `.harness-cache/node`
     * and returns its `bin/` directory.
     */
    private fun ensureNodeOnPath(repoRoot: File): String {
        val onPath = runCommand(
            listOf("sh", "-c", "command -v npx && command -v npm && command -v node"),
            cwd = File("."),
            captureOutput = true,
            failOnNonZero = false,
        )
        if (onPath.exitCode == 0) {
            val nodeBin = File(onPath.stdout.trim().lines().first()).parentFile
            // Playwright 1.48 requires Node 18+. Ubuntu 22.04 ships Node 12 at
            // /usr/bin/node; running `npx playwright install chromium` with
            // Node 12 exits 127 (the script can't parse modern syntax). Only
            // accept system Node if the major version is ≥ 18.
            val versionResult = runCommand(
                listOf(File(nodeBin, "node").absolutePath, "--version"),
                cwd = File("."),
                captureOutput = true,
                failOnNonZero = false,
            )
            val major = Regex("""v(\d+)""").find(versionResult.stdout.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (major != null && major >= 18) {
                println("[harness] Using system Node ${versionResult.stdout.trim()} from ${nodeBin.absolutePath}")
                return nodeBin.absolutePath
            }
            println(
                "[harness] System Node ${versionResult.stdout.trim().ifEmpty { "(unknown version)" }} " +
                    "at ${nodeBin.absolutePath} is too old; downloading Node 20 into the cache",
            )
        }
        val nodeHome = File(repoRoot, ".harness-cache/node")
        val nodeBin = File(nodeHome, "bin")
        if (File(nodeBin, "node").canExecute() && File(nodeBin, "npm").isFile) {
            println("[harness] Reusing cached Node at $nodeBin")
            return nodeBin.absolutePath
        }
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val (os, ext) = when {
            "linux" in osName -> "linux" to "tar.xz"
            "mac" in osName || "darwin" in osName -> "darwin" to "tar.xz"
            else -> error("[harness] Automatic Node install not supported on $osName; install Node 20 manually.")
        }
        val arch = when {
            "aarch64" in osArch || "arm64" in osArch -> "arm64"
            "amd64" in osArch || "x86_64" in osArch -> "x64"
            else -> error("[harness] Automatic Node install not supported for arch $osArch.")
        }
        val version = "v20.18.0"
        val tarName = "node-$version-$os-$arch.$ext"
        val url = "https://nodejs.org/dist/$version/$tarName"
        val cacheRoot = File(repoRoot, ".harness-cache")
        cacheRoot.mkdirs()
        val tarPath = File(cacheRoot, tarName)
        println("[harness] Downloading Node $version for $os/$arch from $url")
        runCommand(listOf("curl", "-fLo", tarPath.absolutePath, url), cwd = cacheRoot)
        if (nodeHome.exists()) nodeHome.deleteRecursively()
        nodeHome.mkdirs()
        // Strip the outer `node-v20-*` directory from the tarball so bin/ lands
        // directly under .harness-cache/node/. Extracted entirely in-process via
        // Apache Commons Compress so the harness does not depend on the `tar`/`xz`
        // binaries being installed on the runner (the bld-all-tests containers ship
        // neither, which previously made `tar -xJf` fail with "xz: Cannot exec").
        extractTarXz(tarPath, nodeHome, stripComponents = 1)
        require(File(nodeBin, "node").canExecute() && File(nodeBin, "npm").isFile) {
            "Extracted Node install is missing bin/node or bin/npm at $nodeBin"
        }
        return nodeBin.absolutePath
    }

    private fun ensurePlaywrightInstalled(repoRoot: File, nodeBin: String) {
        val npxPath = "$nodeBin:${System.getenv("PATH") ?: ""}"
        val pathEnv = mapOf("PATH" to npxPath)
        if (!File(repoRoot, "node_modules/@playwright/test").isDirectory) {
            println("[harness] Installing browser-e2e npm deps")
            File(repoRoot, "package-lock.json").delete()
            runCommand(
                listOf("$nodeBin/npm", "install", "--no-audit", "--no-fund"),
                cwd = repoRoot,
                extraEnv = pathEnv,
            )
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
                listOf("$nodeBin/npx", "playwright", "install", "chromium"),
                cwd = repoRoot,
                extraEnv = pathEnv,
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
        /**
         * The daemon's DIRECT localhost libp2p multiaddr
         * (/ip4/127.0.0.1/tcp/<port>/p2p/<peerId>). The demo is started with
         * this as a `--bootstrap-peer` so it dials the daemon directly, never
         * the public relay. See [startDaemon].
         */
        val directMultiaddr: String,
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
        // --no-default-bootstrap: the daemon must NOT fall back to the public
        // relay (198.199.106.165). Tests are hermetic — the daemon joins no
        // public network and is reached only via its direct localhost listener.
        val process = ProcessBuilder(
            "java", "-cp", deps.daemonClasspath, DAEMON_MAIN_CLASS,
            "--port", port.toString(),
            "--db", dbPath.absolutePath,
            "--peers-dir", peersDir.absolutePath,
            "--no-default-bootstrap",
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

        // Parse the daemon's DIRECT listen multiaddr so the demo can dial it
        // directly. With --no-default-bootstrap the daemon advertises only its
        // direct listener (no p2p-circuit), e.g.
        //   Multiaddresses: [/ip4/127.0.0.1/tcp/35555/p2p/12D3KooW...]
        // We force loopback + the daemon's own peerId (only the tcp port is
        // load-bearing) — the direct addr is the one whose /p2p/ segment is
        // immediately the daemon's own peerId (a relayed addr would have the
        // relay's peerId there first).
        val directMultiaddr = try {
            val peerId = daemonUrl.substringAfter("w3wallet.daemon.").trimEnd('/')
            val directAddr = waitForLogMatch(
                logFile = logFile,
                process = process,
                label = "daemon direct multiaddr",
                regex = Regex("""/ip4/[0-9.]+/tcp/\d+/p2p/""" + Regex.escape(peerId)),
                timeoutSeconds = 30,
            )
            val tcpPort = Regex("""/tcp/(\d+)/""").find(directAddr)!!.groupValues[1]
            "/ip4/127.0.0.1/tcp/$tcpPort/p2p/$peerId"
        } catch (t: Throwable) {
            handle.stop()
            throw t
        }

        return DaemonHandle(handle, daemonUrl, dbPath, directMultiaddr)
    }

    private fun startDemo(
        deps: ResolvedDeps,
        runDir: File,
        port: Int,
        daemonBootstrap: String,
    ): ProcessHandle {
        val logFile = File(runDir, "demo.log")
        println("[harness] Starting WalletDemoServer on port $port (daemon bootstrap: $daemonBootstrap)")
        // --bootstrap-peer = the daemon's direct localhost addr: the demo's
        // UrlResolver dials the daemon directly, never the public relay.
        val process = ProcessBuilder(
            "java", "-jar", deps.demoJar.absolutePath,
            "--port", port.toString(),
            "--bootstrap-peer", daemonBootstrap,
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
        nodeBin: String,
    ) {
        val env = mapOf(
            "DEMO_URL" to demoUrl,
            "DAEMON_URL" to daemonUrl,
            "DAEMON_WS_URL" to daemonWsUrl,
            "DAEMON_SQLITE_PATH" to daemonDbPath.absolutePath,
            "EXTENSION_DIST_DIR" to extensionDistDir.absolutePath,
            "DAEMON_CLASSPATH" to daemonClasspath,
            // Put our Node install first on PATH so playwright shells launched
            // by the spec (e.g. testP2pReconnectAfterDaemonRestart's
            // `java -cp $DAEMON_CLASSPATH ...`) see a consistent env.
            "PATH" to "$nodeBin:${System.getenv("PATH") ?: ""}",
        )

        // Playwright's `chromium.launchPersistentContext({ headless: false })`
        // needs a real display for the extension to load; the npm harness's
        // `launchChromiumWithExtension` uses that mode. Wrap in xvfb-run
        // whenever there is no DISPLAY, regardless of CI env, and install
        // xvfb on the fly if it's missing (as on the kotlin.build droplet).
        val needDisplay = System.getenv("DISPLAY").isNullOrBlank()
        val useXvfb = if (needDisplay) ensureXvfb() else false
        // Give each spec its OWN Playwright output dir. The harness runs every
        // spec as a separate `npx playwright test` invocation, and Playwright
        // cleans its outputDir at the start of each run — so a shared
        // `test-results/` means each spec WIPES the previous spec's traces and
        // error-context.md, leaving only the last spec's artifacts (a failing
        // spec's diagnosis vanishes the moment the next spec runs). A per-spec
        // dir preserves all of them for the CI `test-results/**` upload.
        val specSlug = spec.removeSuffix(".spec.ts").removeSuffix(".ts")
        val outputDir = "test-results/$specSlug"
        val playwrightCmd =
            listOf("$nodeBin/npx", "playwright", "test", "tests/$spec", "--output", outputDir)
        val cmd = if (useXvfb) listOf("xvfb-run", "-a") + playwrightCmd else playwrightCmd
        println("[harness] Running Playwright: ${cmd.joinToString(" ")}")
        // Capture the output so a FAILURE can surface the REAL Playwright error
        // (assertion / timeout / connection refused) in the thrown exception —
        // which is what the kompile test runner reports back to CI. Without this
        // the runner shows only "exit code 1" and the actual error is invisible
        // unless someone digs the per-spec artifact out of the upload, which
        // defeats diagnosis.
        val result = runCommand(
            cmd,
            cwd = repoRoot,
            extraEnv = env,
            captureOutput = true,
            failOnNonZero = false,
            timeoutSeconds = 1800,
        )
        if (result.exitCode != 0) {
            val errorContext = readPlaywrightErrorContext(File(repoRoot, outputDir))
            val detail = buildString {
                append("Playwright spec '$spec' failed with exit code ${result.exitCode}.\n")
                append("--- playwright output (tail) ---\n")
                append(result.stdout.takeLast(6000).ifBlank { "(no stdout captured)" })
                if (result.stderr.isNotBlank()) {
                    append("\n--- stderr (tail) ---\n")
                    append(result.stderr.takeLast(2000))
                }
                if (errorContext.isNotBlank()) {
                    append("\n--- error-context.md ---\n")
                    append(errorContext)
                }
            }
            // Echo to stdout for the live log, then fail with the same detail in
            // the exception (the channel the kompile runner reliably surfaces).
            println(detail)
            error(detail)
        }
    }

    /**
     * Read the Playwright-generated `error-context.md` file(s) under [outputDir]
     * (Playwright writes one per failing test) so the real failure can be folded
     * into the thrown exception. Returns "" when none exist.
     */
    private fun readPlaywrightErrorContext(outputDir: File): String {
        if (!outputDir.isDirectory) return ""
        return outputDir.walkTopDown()
            .filter { it.isFile && it.name == "error-context.md" }
            .joinToString("\n\n") { f -> "# ${f.relativeTo(outputDir).path}\n${f.readText().take(4000)}" }
    }

    /**
     * Make sure `xvfb-run` is on PATH so the Playwright invocation can run
     * headed Chromium (required by the extension). Returns true when xvfb-run
     * is usable, false when we couldn't install it (caller falls back to
     * invoking Playwright without a display — which will fail loudly rather
     * than hang). Tried in order:
     *  1. Already on PATH → return true.
     *  2. `apt-get install -y xvfb` if we're root on a Debian-family image
     *     (covers the kotlin.build droplet).
     *  3. Give up and return false.
     */
    private fun ensureXvfb(): Boolean {
        if (hasCommand("xvfb-run")) return true
        // Only attempt apt install when running as root (droplet image) and
        // apt-get exists; otherwise let Playwright fail with its own hint.
        if (!hasCommand("apt-get")) return false
        val whoami = runCommand(
            listOf("id", "-u"),
            cwd = File("."),
            captureOutput = true,
            failOnNonZero = false,
        )
        if (whoami.stdout.trim() != "0") return false
        println("[harness] Installing xvfb via apt-get")
        val install = runCommand(
            listOf(
                "sh", "-c",
                "DEBIAN_FRONTEND=noninteractive apt-get update -qq && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xvfb",
            ),
            cwd = File("."),
            failOnNonZero = false,
        )
        return install.exitCode == 0 && hasCommand("xvfb-run")
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

/**
 * Extracts a `.tar.xz` archive entirely in-process using Apache Commons Compress,
 * so the harness does not depend on the `tar` or `xz` binaries being present on
 * the runner. `stripComponents` drops that many leading path segments from every
 * entry (mirrors `tar --strip-components=N`). Executable permission bits and
 * symbolic links are preserved, so the extracted `bin/node` stays runnable and
 * `bin/npm` (a symlink into `lib/`) resolves.
 */
fun extractTarXz(tarFile: File, destDir: File, stripComponents: Int = 0) {
    destDir.mkdirs()
    BufferedInputStream(FileInputStream(tarFile)).use { fileIn ->
        XZCompressorInputStream(fileIn).use { xzIn ->
            TarArchiveInputStream(xzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val segments = entry.name.split('/')
                    if (segments.size > stripComponents) {
                        val relative = segments.drop(stripComponents).joinToString("/")
                        if (relative.isNotEmpty()) {
                            val outFile = File(destDir, relative)
                            when {
                                entry.isDirectory -> outFile.mkdirs()
                                entry.isSymbolicLink -> {
                                    outFile.parentFile?.mkdirs()
                                    Files.deleteIfExists(outFile.toPath())
                                    Files.createSymbolicLink(outFile.toPath(), Paths.get(entry.linkName))
                                }
                                else -> {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out: OutputStream -> tarIn.copyTo(out) }
                                    // Preserve any execute bit (owner/group/other) from the tar mode.
                                    if ((entry.mode and 73) != 0) outFile.setExecutable(true, false)
                                }
                            }
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
    }
}

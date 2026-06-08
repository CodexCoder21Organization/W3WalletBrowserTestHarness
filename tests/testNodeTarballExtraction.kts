@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-test:1.9.22")
@file:WithArtifact("org.apache.commons:commons-compress:1.26.1")
@file:WithArtifact("org.tukaani:xz:1.9")
@file:WithArtifact("commons-io:commons-io:2.16.1")
@file:WithArtifact("w3wallet.browser.testharness.buildMaven()")

package w3wallet.browser.testharness.tests

import build.kotlin.withartifact.WithArtifact
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import w3wallet.browser.testharness.extractTarXz
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream

/**
 * Regression test for the in-process `.tar.xz` extraction the harness uses to
 * install Node. Builds a small `.tar.xz` (an outer directory to strip, a normal
 * file, an executable file, and a symlink) entirely in memory, then extracts it
 * with `extractTarXz` and asserts the strip, executable bit, and symlink are all
 * preserved. The whole thing runs with NO `tar`/`xz` binary on the runner — which
 * is exactly the condition (bld-all-tests containers ship neither) that made the
 * old `tar -xJf` shell-out fail with "xz: Cannot exec".
 */
fun testExtractTarXzStripsComponentsAndPreservesModesAndSymlinks() {
    val tmp = Files.createTempDirectory("harness-extract-test").toFile()
    val tarXz = File(tmp, "fixture.tar.xz")
    FileOutputStream(tarXz).use { fos ->
        XZCompressorOutputStream(fos).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                val payload = "hello-harness".toByteArray()
                val normal = TarArchiveEntry("outer/data/hello.txt")
                normal.size = payload.size.toLong()
                tar.putArchiveEntry(normal)
                tar.write(payload)
                tar.closeArchiveEntry()

                val script = "#!/bin/sh\necho hi\n".toByteArray()
                val executable = TarArchiveEntry("outer/bin/run")
                executable.size = script.size.toLong()
                executable.mode = 493 // 0755
                tar.putArchiveEntry(executable)
                tar.write(script)
                tar.closeArchiveEntry()

                val symlink = TarArchiveEntry("outer/bin/run-link", TarArchiveEntry.LF_SYMLINK)
                symlink.linkName = "run"
                tar.putArchiveEntry(symlink)
                tar.closeArchiveEntry()
            }
        }
    }

    val dest = File(tmp, "out")
    extractTarXz(tarXz, dest, 1)

    val hello = File(dest, "data/hello.txt")
    assertTrue(hello.isFile, "data/hello.txt should be extracted with the leading 'outer/' component stripped")
    assertEquals("hello-harness", hello.readText())

    val run = File(dest, "bin/run")
    assertTrue(run.isFile, "bin/run should be extracted")
    assertTrue(run.canExecute(), "bin/run should preserve its executable bit from tar mode 0755")

    val link = File(dest, "bin/run-link")
    assertTrue(Files.isSymbolicLink(link.toPath()), "bin/run-link should be recreated as a symbolic link")
    assertEquals("run", Files.readSymbolicLink(link.toPath()).toString())

    tmp.deleteRecursively()
}

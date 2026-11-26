package io.github.warleysr.dechainer.data

import java.io.File
import java.util.zip.ZipFile

/**
 * Inspects and extracts either a standalone `.apk` or a split-APK bundle (`.apks`, `.xapk`, `.apkm`)
 * inside [destDir], guarding against path traversal (Zip Slip) and oversized archives.
 */
object ApkArchiveExtractor {
    private const val MAX_ENTRY_COUNT = 64
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_SIZE = 8192

    data class ExtractedArchive(
        val rootDir: File,
        val apkFiles: List<File>,
        val isSplitBundle: Boolean
    )

    fun extract(sourceFile: File, destDir: File): ExtractedArchive? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        return runCatching {
            ZipFile(sourceFile).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") != null) {
                    return@use ExtractedArchive(
                        rootDir = destDir,
                        apkFiles = listOf(sourceFile),
                        isSplitBundle = false
                    )
                }

                val canonicalDest = destDir.canonicalPath + File.separator
                val extracted = mutableListOf<File>()
                var totalBytes = 0L
                var entryCount = 0

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith("__MACOSX/") || !name.endsWith(".apk", ignoreCase = true)) {
                        continue
                    }
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) return@use null

                    val rawBase = File(name).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val safeName = if (rawBase.isBlank()) "split_$entryCount.apk" else rawBase
                    var target = File(destDir, safeName)
                    if (target.exists() || target == sourceFile) {
                        target = File(destDir, "split_${entryCount}_$safeName")
                    }
                    if (!target.canonicalPath.startsWith(canonicalDest)) {
                        return@use null
                    }

                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > MAX_TOTAL_BYTES) return@use null
                                output.write(buf, 0, read)
                            }
                        }
                    }
                    extracted += target
                }

                if (extracted.isEmpty()) null
                else ExtractedArchive(rootDir = destDir, apkFiles = extracted, isSplitBundle = true)
            }
        }.getOrNull()
    }
}

package io.github.warleysr.dechainer.data

import android.content.pm.PackageInstaller
import android.os.UserManager
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ApkUpdateInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `requestDowngrade sets INSTALL_REQUEST_DOWNGRADE and INSTALL_ALLOW_DOWNGRADE in installFlags`() {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        ApkUpdateInstaller.requestDowngrade(params)

        val flags = PackageInstaller.SessionParams::class.java.getField("installFlags").getInt(params)
        (flags and 0x00000080) shouldBe 0x00000080
        (flags and 0x00100000) shouldBe 0x00100000
    }

    @Test
    fun `installRestrictions includes DISALLOW_INSTALL_APPS and unknown sources`() {
        assertTrue(UserManager.DISALLOW_INSTALL_APPS in ApkUpdateInstaller.installRestrictions)
        assertTrue(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES in ApkUpdateInstaller.installRestrictions)
        assertTrue(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY in ApkUpdateInstaller.installRestrictions)
    }

    @Test
    fun `ApkArchiveExtractor handles standalone APK without extracting`() {
        val dest = tempFolder.newFolder("single")
        val apk = File(dest, "sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val result = ApkArchiveExtractor.extract(apk, dest)
        assertNotNull(result)
        assertFalse(result!!.isSplitBundle)
        assertEquals(listOf(apk), result.apkFiles)
    }

    @Test
    fun `ApkArchiveExtractor extracts split APK bundle, skips metadata, and blocks Zip Slip`() {
        val dest = tempFolder.newFolder("bundle")
        val bundle = File(dest, "app.apkm")
        ZipOutputStream(bundle.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("base.apk"))
            zip.write(byteArrayOf(10, 20))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("../../evil_split.apk"))
            zip.write(byteArrayOf(30, 40))
            zip.closeEntry()
        }

        val result = ApkArchiveExtractor.extract(bundle, dest)
        assertNotNull(result)
        assertTrue(result!!.isSplitBundle)
        assertEquals(2, result.apkFiles.size)
        val canonicalDest = dest.canonicalPath + File.separator
        result.apkFiles.forEach { extracted ->
            assertTrue(extracted.canonicalPath.startsWith(canonicalDest))
            assertTrue(extracted.name.endsWith(".apk"))
        }
    }

    @Test
    fun `ApkArchiveExtractor returns null for archive without APKs`() {
        val dest = tempFolder.newFolder("empty")
        val zipFile = File(dest, "not_an_apk.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        assertNull(ApkArchiveExtractor.extract(zipFile, dest))
    }
}

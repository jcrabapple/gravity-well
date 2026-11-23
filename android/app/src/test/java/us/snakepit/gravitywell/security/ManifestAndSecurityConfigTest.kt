package us.snakepit.gravitywell.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestAndSecurityConfigTest {

    private fun findAppDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.isFile) return dir
            val appSub = File(dir, "app/src/main/AndroidManifest.xml")
            if (appSub.isFile) return File(dir, "app")
            dir = dir.parentFile
        }
        error("Could not locate android/app module root from user.dir=${System.getProperty("user.dir")}")
    }

    @Test
    fun `main manifest enforces usesCleartextTraffic=false allowBackup=false and singleTask`() {
        val appDir = findAppDir()
        val manifestFile = File(appDir, "src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)

        val appEl = doc.getElementsByTagName("application").item(0)
        val attrs = appEl.attributes
        assertEquals("false", attrs.getNamedItem("android:usesCleartextTraffic")?.nodeValue)
        assertEquals("false", attrs.getNamedItem("android:allowBackup")?.nodeValue)
        assertEquals(null, attrs.getNamedItem("android:networkSecurityConfig"))

        val activityEl = doc.getElementsByTagName("activity").item(0)
        assertEquals("singleTask", activityEl.attributes.getNamedItem("android:launchMode")?.nodeValue)

        val permissions = (0 until doc.getElementsByTagName("uses-permission").length).map { i ->
            doc.getElementsByTagName("uses-permission").item(i).attributes
                .getNamedItem("android:name")?.nodeValue
        }
        assertTrue(permissions.contains("android.permission.INTERNET"))
    }

    @Test
    fun `debug network_security_config permits cleartext only on local loopback addresses`() {
        val appDir = findAppDir()
        val mainNetSec = File(appDir, "src/main/res/xml/network_security_config.xml")
        assertFalse("Main/release res must not include cleartext network_security_config.xml", mainNetSec.exists())

        val debugNetSec = File(appDir, "src/debug/res/xml/network_security_config.xml")
        assertTrue(debugNetSec.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(debugNetSec)
        val domains = (0 until doc.getElementsByTagName("domain").length).map { i ->
            doc.getElementsByTagName("domain").item(i).textContent.trim()
        }.toSet()
        assertEquals(setOf("10.0.2.2", "127.0.0.1", "localhost"), domains)
    }
}

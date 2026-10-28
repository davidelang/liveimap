package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesTest {
    @Test
    fun unsentMailStaysOutOfBackup() {
        val rules = text("src/main/res/xml/backup_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for (section in listOf(cloud, transfer)) {
            assertTrue(section.contains("domain=\"file\" path=\"datastore/account_settings.preferences_pb\""))
            assertTrue(section.contains("domain=\"file\" path=\"datastore/global_settings.preferences_pb\""))
        }
        assertTrue(text("src/main/AndroidManifest.xml").contains("allowBackup=\"true\""))
        val older = text("src/main/res/xml/full_backup_content.xml")
        assertTrue(older.contains("<full-backup-content>"))
        assertTrue(older.contains("domain=\"file\" path=\"datastore/account_settings.preferences_pb\""))
        assertTrue(older.contains("domain=\"file\" path=\"datastore/global_settings.preferences_pb\""))
        val manifest = text("src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/full_backup_content\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("allowBackup=\"true\""))
    }

    private fun text(path: String) =
        listOf(path, "app/$path").map(::File).first { it.exists() }.readText()
}

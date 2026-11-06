package com.example.phoneins.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.phoneins.data.TrialLogSummary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class TrialLogCatalog(private val context: Context) {
    private val importsDirectory = File(context.filesDir, "replay_imports").apply { mkdirs() }
    private val trialDirectory get() = CsvTrialLogger.trialLogDirectory(context)

    fun recent(activeFile: File? = null): List<TrialLogSummary> {
        val activePath = activeFile?.canonicalPath
        return (trialDirectory.listFiles()?.toList().orEmpty() + importsDirectory.listFiles()?.toList().orEmpty())
            .asSequence()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) && it.canonicalPath != activePath }
            .sortedByDescending(File::lastModified)
            .mapNotNull { runCatching { CsvReplayIndex.quickSummary(it) }.getOrNull() }
            .take(10)
            .toList()
    }

    fun import(uri: Uri): File {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "imported_trial.csv"
        val safeStem = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifEmpty { "imported_trial" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var output = File(importsDirectory, "${safeStem}_$stamp.csv")
        var suffix = 1
        while (output.exists()) output = File(importsDirectory, "${safeStem}_${stamp}_${suffix++}.csv")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected CSV" }
            output.outputStream().buffered().use(input::copyTo)
        }
        CsvReplayIndex.open(output)
        return output
    }

    fun resolve(id: String): File {
        val file = File(id).canonicalFile
        val allowed = listOf(trialDirectory.canonicalFile, importsDirectory.canonicalFile)
            .any { directory -> file.path.startsWith(directory.path + File.separator) }
        require(allowed && file.isFile) { "Selected replay log is unavailable" }
        return file
    }
}

package com.localmotion.data

import android.content.Context
import com.localmotion.model.ImageArtifact
import java.io.File
import java.util.UUID

class ArtifactRepository(private val context: Context) {
    private val maxArtifactCount = 30
    private val maxTotalBytes = 2L * 1024L * 1024L * 1024L

    fun artifactsDir(): File = File(context.filesDir, "generated_images").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    fun createArtifactDir(): File = File(artifactsDir(), UUID.randomUUID().toString()).apply {
        mkdirs()
    }

    fun saveArtifact(artifact: ImageArtifact) {
        val directory = File(artifactsDir(), artifact.id).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        File(directory, "artifact.json").writeText(artifact.toJson().toString(2))
        enforceStoragePolicy()
    }

    fun loadArtifacts(): List<ImageArtifact> =
        artifactsDir()
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory ->
                val artifactFile = File(directory, "artifact.json")
                if (!artifactFile.exists()) {
                    null
                } else {
                    runCatching { ImageArtifact.fromJson(artifactFile.readText()) }.getOrNull()
                }
            }
            .sortedByDescending { it.createdAtEpochMs }

    fun loadArtifact(id: String): ImageArtifact? {
        val artifactFile = File(File(artifactsDir(), id), "artifact.json")
        if (!artifactFile.exists()) {
            return null
        }
        return runCatching { ImageArtifact.fromJson(artifactFile.readText()) }.getOrNull()
    }

    private fun enforceStoragePolicy() {
        val directories = artifactsDir()
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var totalBytes = directories.sumOf { it.directorySize() }

        while (directories.size > maxArtifactCount || totalBytes > maxTotalBytes) {
            val oldest = directories.removeFirstOrNull() ?: break
            totalBytes -= oldest.directorySize()
            oldest.deleteRecursively()
        }
    }

    private fun File.directorySize(): Long {
        if (!exists()) {
            return 0L
        }
        if (isFile) {
            return length()
        }
        return walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

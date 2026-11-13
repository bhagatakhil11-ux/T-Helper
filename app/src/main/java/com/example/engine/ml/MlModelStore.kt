package com.example.engine.ml

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * File-backed Moshi JSON persistence for trained Random Forest models.
 * Accepts any java.io.File (e.g. context.filesDir in Android or temp file in JVM unit tests).
 */
class MlModelStore(
    private val modelFile: File
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(PersistedRandomForestModel::class.java)

    fun save(model: PersistedRandomForestModel): Boolean {
        return try {
            val parent = modelFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val json = adapter.toJson(model)
            val tmpFile = File(parent, "${modelFile.name}.tmp")
            tmpFile.writeText(json, Charsets.UTF_8)
            if (modelFile.exists()) {
                modelFile.delete()
            }
            if (!tmpFile.renameTo(modelFile)) {
                modelFile.writeText(json, Charsets.UTF_8)
                tmpFile.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(): PersistedRandomForestModel? {
        return try {
            if (!modelFile.exists() || !modelFile.canRead()) return null
            val json = modelFile.readText(Charsets.UTF_8)
            if (json.isBlank()) return null
            val loaded = adapter.fromJson(json) ?: return null
            if (loaded.trees.isEmpty()) return null
            loaded
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        try {
            if (modelFile.exists()) modelFile.delete()
        } catch (_: Exception) {
        }
    }
}

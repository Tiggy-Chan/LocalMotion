package com.localmotion

import com.localmotion.data.RuntimeBundleManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBundleManifestTest {
    @Test
    fun `manifest parser resolves expected fields`() {
        val manifest = RuntimeBundleManifest.fromJson(
            """
            {
              "bundleId": "localmotion-video-v1-sm8650",
              "displayName": "LocalMotion Video V1 SM8650",
              "version": "0.1.0",
              "backend": "qnn",
              "supportedSocs": ["SM8650"],
              "files": [
                {
                  "relativePath": "models/sd15_img2img.bin",
                  "url": "models/sd15_img2img.bin",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "sizeBytes": 1024,
                  "role": "stylizer",
                  "required": true
                },
                {
                  "relativePath": "models/depth_anything_v2_small.bin",
                  "url": "models/depth_anything_v2_small.bin",
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "sizeBytes": 2048,
                  "role": "depth",
                  "required": true
                },
                {
                  "relativePath": "models/rife46_lite.bin",
                  "url": "models/rife46_lite.bin",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "sizeBytes": 4096,
                  "role": "interpolator",
                  "required": true
                }
              ]
            }
            """.trimIndent(),
            "https://example.com/runtime/runtime-manifest.json",
        )

        assertEquals("0.1.0", manifest.version)
        assertEquals(3, manifest.files.size)
        assertEquals(
            "https://example.com/runtime/models/sd15_img2img.bin",
            manifest.files.first().resolvedUrl(manifest.sourceUrl),
        )
    }

    @Test
    fun `manifest validation rejects missing required roles`() {
        val manifest = RuntimeBundleManifest.fromJson(
            """
            {
              "backend": "qnn",
              "supportedSocs": ["SM8650"],
              "files": []
            }
            """.trimIndent(),
        )

        val errors = manifest.validate("SM8650")
        assertTrue(errors.any { it.contains("缺少角色 stylizer") })
        assertTrue(errors.any { it.contains("没有任何文件条目") })
    }
}

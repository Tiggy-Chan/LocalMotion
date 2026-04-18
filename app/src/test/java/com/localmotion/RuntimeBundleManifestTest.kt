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
              "bundleId": "localmotion-sd15-v1-sm8650",
              "displayName": "LocalMotion SD15 V1 SM8650",
              "version": "0.1.0",
              "workload": "sd15",
              "backend": "qnn",
              "supportedSocs": ["SM8650"],
              "files": [
                {
                  "relativePath": "models/text_encoder.bin",
                  "url": "models/text_encoder.bin",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "sizeBytes": 1024,
                  "role": "text_encoder_bin",
                  "required": true
                },
                {
                  "relativePath": "models/text_encoder.so",
                  "url": "models/text_encoder.so",
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "sizeBytes": 2048,
                  "role": "text_encoder_lib",
                  "required": true
                },
                {
                  "relativePath": "models/unet.bin",
                  "url": "models/unet.bin",
                  "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "sizeBytes": 4096,
                  "role": "unet_bin",
                  "required": true
                },
                {
                  "relativePath": "models/unet.so",
                  "url": "models/unet.so",
                  "sha256": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "sizeBytes": 8192,
                  "role": "unet_lib",
                  "required": true
                },
                {
                  "relativePath": "models/vae_encoder.bin",
                  "url": "models/vae_encoder.bin",
                  "sha256": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "sizeBytes": 4096,
                  "role": "vae_encoder_bin",
                  "required": true
                },
                {
                  "relativePath": "models/vae_encoder.so",
                  "url": "models/vae_encoder.so",
                  "sha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                  "sizeBytes": 8192,
                  "role": "vae_encoder_lib",
                  "required": true
                },
                {
                  "relativePath": "models/vae_decoder.bin",
                  "url": "models/vae_decoder.bin",
                  "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                  "sizeBytes": 4096,
                  "role": "vae_decoder_bin",
                  "required": true
                },
                {
                  "relativePath": "models/vae_decoder.so",
                  "url": "models/vae_decoder.so",
                  "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                  "sizeBytes": 8192,
                  "role": "vae_decoder_lib",
                  "required": true
                },
                {
                  "relativePath": "tokenizer/tokenizer.json",
                  "url": "tokenizer/tokenizer.json",
                  "sha256": "3333333333333333333333333333333333333333333333333333333333333333",
                  "sizeBytes": 1024,
                  "role": "tokenizer_json",
                  "required": true
                },
                {
                  "relativePath": "tokenizer/tokenizer_config.json",
                  "url": "tokenizer/tokenizer_config.json",
                  "sha256": "4444444444444444444444444444444444444444444444444444444444444444",
                  "sizeBytes": 1024,
                  "role": "tokenizer_config",
                  "required": true
                },
                {
                  "relativePath": "qnn/lib/libQnnSystem.so",
                  "url": "qnn/lib/libQnnSystem.so",
                  "sha256": "5555555555555555555555555555555555555555555555555555555555555555",
                  "sizeBytes": 1024,
                  "role": "qnn_system",
                  "required": true
                },
                {
                  "relativePath": "qnn/lib/libQnnHtp.so",
                  "url": "qnn/lib/libQnnHtp.so",
                  "sha256": "6666666666666666666666666666666666666666666666666666666666666666",
                  "sizeBytes": 1024,
                  "role": "qnn_htp",
                  "required": true
                }
              ]
            }
            """.trimIndent(),
            "https://example.com/runtime/runtime-manifest.json",
        )

        assertEquals("0.1.0", manifest.version)
        assertEquals("sd15", manifest.workload)
        assertEquals(12, manifest.files.size)
        assertEquals(
            "https://example.com/runtime/models/text_encoder.bin",
            manifest.files.first().resolvedUrl(manifest.sourceUrl),
        )
    }

    @Test
    fun `manifest validation rejects missing required roles`() {
        val manifest = RuntimeBundleManifest.fromJson(
            """
            {
              "workload": "sd15",
              "backend": "qnn",
              "supportedSocs": ["SM8650"],
              "files": []
            }
            """.trimIndent(),
        )

        val errors = manifest.validate("SM8650")
        assertTrue(errors.any { it.contains("缺少角色 text_encoder_bin") })
        assertTrue(errors.any { it.contains("没有任何文件条目") })
    }

    @Test
    fun `manifest parser still infers legacy video workload`() {
        val manifest = RuntimeBundleManifest.fromJson(
            """
            {
              "bundleId": "localmotion-video-v1-sm8650",
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
        )

        assertEquals("video-demo", manifest.workload)
        assertTrue(manifest.validate("SM8650").isEmpty())
    }
}

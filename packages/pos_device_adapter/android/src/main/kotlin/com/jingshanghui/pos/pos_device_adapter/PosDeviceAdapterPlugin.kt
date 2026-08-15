package com.jingshanghui.pos.pos_device_adapter

import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** Vendor-neutral Android boundary for certified POS hardware adapters. */
class PosDeviceAdapterPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getSnapshot" -> result.success(buildSnapshot())
            else -> result.notImplemented()
        }
    }

    internal fun buildSnapshot(): Map<String, Any> =
        mapOf(
            "contractVersion" to CONTRACT_VERSION,
            "metadata" to
                mapOf(
                    "manufacturer" to normalized(Build.MANUFACTURER),
                    "model" to normalized(Build.MODEL),
                    "androidRelease" to normalized(Build.VERSION.RELEASE),
                    "androidSdk" to Build.VERSION.SDK_INT,
                    "adapterVersion" to ADAPTER_VERSION,
                ),
            // Capabilities are empty until a model-specific implementation is certified.
            "capabilities" to emptyList<String>(),
        )

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    private fun normalized(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"

    private companion object {
        const val CHANNEL_NAME = "com.jingshanghui.pos/device_adapter/v1"
        const val CONTRACT_VERSION = "1.0"
        const val ADAPTER_VERSION = "0.1.0"
    }
}

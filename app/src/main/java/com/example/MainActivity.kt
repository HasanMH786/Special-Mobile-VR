package com.example

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

  private var webViewRef: WebView? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(12, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()

  private val videoPickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      val callback = filePathCallback
      filePathCallback = null
      if (uri != null) {
        callback?.onReceiveValue(arrayOf(uri))
      } else {
        callback?.onReceiveValue(null)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    configureVrWindow()

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val wv = webViewRef
          if (wv != null) {
            wv.evaluateJavascript("window.__onAndroidBack && window.__onAndroidBack()") { result ->
              if (result != "true") {
                finish()
              }
            }
          } else {
            finish()
          }
        }
      },
    )

    setContent {
      Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black)) {
        VrWebViewContainer(
          onWebViewReady = { webView ->
            webViewRef = webView
            webView.requestFocus()
          }
        )
      }
    }
  }

  private fun configureVrWindow() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes =
        window.attributes.apply {
          layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    applyImmersiveFullscreen(true)
  }

  private fun applyImmersiveFullscreen(enable: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, !enable)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (enable) {
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      applyImmersiveFullscreen(true)
      webViewRef?.requestFocus()
    }
  }

  override fun onResume() {
    super.onResume()
    webViewRef?.onResume()
  }

  override fun onPause() {
    webViewRef?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    ioScope.cancel()
    webViewRef?.apply {
      stopLoading()
      destroy()
    }
    webViewRef = null
    super.onDestroy()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val code = event.keyCode
    val isVrNavKey =
      code == KeyEvent.KEYCODE_DPAD_UP ||
        code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT ||
        code == KeyEvent.KEYCODE_DPAD_RIGHT ||
        code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_BUTTON_A ||
        code == KeyEvent.KEYCODE_BUTTON_B ||
        code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_SPACE

    if (isVrNavKey && webViewRef != null) {
      if (event.action == KeyEvent.ACTION_DOWN) {
        val mapped =
          when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_SPACE -> " "
            KeyEvent.KEYCODE_BUTTON_B -> "Escape"
            else -> ""
          }
        if (mapped.isNotEmpty()) {
          val escaped = JSONObject.quote(mapped)
          webViewRef?.evaluateJavascript(
            "window.__onNativeKey && window.__onNativeKey($escaped);",
            null,
          )
          return true
        }
      } else if (event.action == KeyEvent.ACTION_UP) {
        return true
      }
    }
    return super.dispatchKeyEvent(event)
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Composable
  private fun VrWebViewContainer(onWebViewReady: (WebView) -> Unit) {
    AndroidView(
      modifier = Modifier.fillMaxSize().background(ComposeColor.Black),
      factory = { context ->
        WebView(context).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(Color.BLACK)
          setLayerType(View.LAYER_TYPE_HARDWARE, null)
          isFocusable = true
          isFocusableInTouchMode = true
          overScrollMode = View.OVER_SCROLL_NEVER

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
              "Mozilla/5.0 (Linux; Android 14; Mobile VR) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
          }

          addJavascriptInterface(AndroidVRBridge(), "AndroidBridge")

          webViewClient =
            object : WebViewClient() {
              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
              ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (
                  url.host == "appassets.androidplatform.net" &&
                    url.path?.startsWith("/assets/") == true
                ) {
                  val assetPath = url.path!!.removePrefix("/assets/")
                  return try {
                    val inputStream = context.assets.open(assetPath)
                    val mime =
                      when {
                        assetPath.endsWith(".html") -> "text/html"
                        assetPath.endsWith(".js") -> "application/javascript"
                        assetPath.endsWith(".css") -> "text/css"
                        else -> "application/octet-stream"
                      }
                    WebResourceResponse(
                      mime,
                      "UTF-8",
                      200,
                      "OK",
                      mapOf("Access-Control-Allow-Origin" to "*"),
                      inputStream,
                    )
                  } catch (e: Exception) {
                    WebResourceResponse(
                      "text/plain",
                      "UTF-8",
                      404,
                      "Not Found",
                      emptyMap(),
                      ByteArrayInputStream("Asset not found".toByteArray()),
                    )
                  }
                }
                return super.shouldInterceptRequest(view, request)
              }

              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
              ): Boolean {
                val target = request?.url?.toString() ?: return false
                // Keep top-level navigation confined to our VR Cinema app; external links stay in browser hub
                return request.isForMainFrame &&
                  !target.startsWith("https://appassets.androidplatform.net/")
              }
            }

          webChromeClient =
            object : WebChromeClient() {
              override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
              ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                  videoPickerLauncher.launch(arrayOf("video/*"))
                  true
                } catch (e: Exception) {
                  filePathCallback = null
                  false
                }
              }
            }

          loadUrl("https://appassets.androidplatform.net/assets/index.html")
          onWebViewReady(this)
        }
      },
    )
  }

  inner class AndroidVRBridge {
    @JavascriptInterface fun isAndroidNative(): Boolean = true

    @JavascriptInterface
    fun enterVrMode() {
      runOnUiThread {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersiveFullscreen(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }

    @JavascriptInterface
    fun exitVrMode() {
      runOnUiThread {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersiveFullscreen(true)
      }
    }

    @JavascriptInterface
    fun fetchPage(urlString: String, callbackId: String) {
      ioScope.launch {
        val payload = JSONObject()
        payload.put("callbackId", callbackId)
        try {
          val normalized =
            if (urlString.startsWith("http://") || urlString.startsWith("https://")) urlString
            else "https://$urlString"
          val req =
            Request.Builder()
              .url(normalized)
              .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
              )
              .build()
          httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val titleMatch =
              Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)
            val title =
              titleMatch?.groupValues?.getOrNull(1)?.replace(Regex("\\s+"), " ")?.trim()
                ?: normalized
            val cleaned =
              body
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("<(br|p|div|h1|h2|h3|h4|li|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
                .take(16000)

            payload.put("ok", true)
            payload.put("url", resp.request.url.toString())
            payload.put("title", title)
            payload.put("text", cleaned)
          }
        } catch (e: Exception) {
          payload.put("ok", false)
          payload.put("error", e.message ?: "Failed to load page")
        }
        val jsArg = payload.toString()
        runOnUiThread {
          webViewRef?.evaluateJavascript("window.__onAndroidPageFetched($jsArg);", null)
        }
      }
    }

    @JavascriptInterface
    fun searchYouTube(query: String, callbackId: String) {
      ioScope.launch {
        val payload = JSONObject()
        payload.put("callbackId", callbackId)
        try {
          val encoded = URLEncoder.encode(query, "UTF-8")
          val req =
            Request.Builder()
              .url("https://www.youtube.com/results?search_query=$encoded")
              .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
              )
              .header("Accept-Language", "en-US,en;q=0.9")
              .build()
          httpClient.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            val results = JSONArray()
            val idPattern =
              Regex(
                "\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]+)\"",
                RegexOption.DOT_MATCHES_ALL,
              )
            val seen = HashSet<String>()
            for (match in idPattern.findAll(html)) {
              val id = match.groupValues[1]
              if (seen.add(id)) {
                val rawTitle = match.groupValues[2]
                  .replace("\\u0026", "&")
                  .replace("\\\"", "\"")
                val item = JSONObject()
                item.put("id", id)
                item.put("title", rawTitle)
                item.put("channel", "YouTube")
                item.put("is360", rawTitle.contains("360", ignoreCase = true) || rawTitle.contains("VR", ignoreCase = true))
                results.put(item)
                if (results.length() >= 12) break
              }
            }
            payload.put("ok", true)
            payload.put("results", results)
          }
        } catch (e: Exception) {
          payload.put("ok", false)
          payload.put("error", e.message ?: "Search failed")
        }
        val jsArg = payload.toString()
        runOnUiThread {
          webViewRef?.evaluateJavascript("window.__onAndroidYouTubeSearch($jsArg);", null)
        }
      }
    }
  }
}

package net.antibs.client.vk

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import net.antibs.client.engine.QwdttEngine
import net.antibs.client.engine.VpnState

/**
 * Manual VK "not robot" captcha for the qWDTT engine. Loads the redirect URI from the Go client's
 * CAPTCHA_SOLVE request, intercepts the captchaNotRobot.check response and returns success_token via
 * CAPTCHA_RESULT|<token> on the client's stdin. The interceptor logic is adapted from qWDTT's
 * CaptchaWebViewManager (SpaceNeuroX/proxy-turn-vk-android, GPL-3.0).
 */
class CaptchaActivity : ComponentActivity() {
    private var done = false

    private val interceptor = """
        (function() {
            if (window.__antibs_interceptor) return; window.__antibs_interceptor = true;
            function handle(data) {
                try {
                    if (data && data.response && data.response.success_token) { AntiBSCaptcha.onSuccess(data.response.success_token); }
                    else if (data && data.error) { AntiBSCaptcha.onError(JSON.stringify(data.error)); }
                } catch (e) {}
            }
            const of = window.fetch;
            window.fetch = async function() {
                const url = arguments[0] || '';
                const r = await of.apply(this, arguments);
                if (typeof url === 'string' && url.includes('captchaNotRobot.check')) { try { handle(await r.clone().json()); } catch (e) {} }
                return r;
            };
            const oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) { this.__u = u; return oo.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function() {
                const x = this;
                if (x.__u && String(x.__u).includes('captchaNotRobot.check')) {
                    x.addEventListener('load', function() { try { handle(JSON.parse(x.responseText)); } catch (e) {} });
                }
                return os.apply(this, arguments);
            };
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface fun onSuccess(token: String) {
            runOnUiThread { finishWith(token) }
        }
        @JavascriptInterface fun onError(err: String) {
            runOnUiThread { Toast.makeText(this@CaptchaActivity, "Капча: $err", Toast.LENGTH_SHORT).show() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = VpnState.captcha.value ?: intent.getStringExtra("uri")
        if (uri.isNullOrEmpty()) { finish(); return }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        wv.addJavascriptInterface(Bridge(), "AntiBSCaptcha")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(interceptor, null)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(interceptor, null)
            }
        }
        setContentView(wv)
        title = "VK: подтвердите, что вы не робот"
        wv.loadUrl(uri)
    }

    private fun finishWith(token: String) {
        if (done) return
        done = true
        QwdttEngine.current?.submitCaptcha(token)
        VpnState.log("[qWDTT] капча решена, токен передан клиенту")
        finish()
    }

    override fun onDestroy() {
        if (!done && VpnState.captcha.value != null) {
            // user closed the page without solving: keep the request pending so it can be reopened
        }
        super.onDestroy()
    }
}

package com.xycz.simple_live_tv.ui.login

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Store
import com.xycz.simple_live_tv.databinding.ActivityQrBinding
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.util.Qr
import com.xycz.simple_live_tv.util.errorMessage
import com.xycz.simple_live_tv.util.toast
import com.xycz.simplelive.core.common.Http
import com.xycz.simplelive.core.common.get
import com.xycz.simplelive.core.common.int
import com.xycz.simplelive.core.common.parseJson
import com.xycz.simplelive.core.common.str
import com.xycz.simplelive.core.site.BiliBiliSite
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 哔哩哔哩扫码登录 */
class BiliLoginActivity : BaseActivity() {
    private lateinit var b: ActivityQrBinding
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQrBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.back.setIcon(R.drawable.ic_arrow_back)
        b.back.setOnClickListener { finish() }
        b.title.text = "哔哩哔哩扫码登录"
        b.refresh.setIcon(R.drawable.ic_refresh)
        b.refresh.setOnClickListener { loadQr() }
        loadQr()
    }

    private val headers = mapOf("user-agent" to BiliBiliSite.USER_AGENT, "referer" to "https://www.bilibili.com/")

    private fun loadQr() {
        pollJob?.cancel()
        b.refresh.visibility = View.GONE
        b.status.text = "正在加载二维码..."
        pollJob = lifecycleScope.launch {
            try {
                val result = Http.getJson(
                    "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
                    headers = headers,
                )
                if (result["code"].int != 0) throw IllegalStateException(result["message"].str)
                val key = result["data"]["qrcode_key"].str
                b.qr.setImageBitmap(Qr.bitmap(result["data"]["url"].str, 400))
                b.status.text = "请使用哔哩哔哩手机客户端扫码登录"
                poll(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                b.status.text = "加载二维码失败：${errorMessage(e)}"
                b.refresh.visibility = View.VISIBLE
                b.refresh.requestFocus()
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.poll(key: String) {
        while (isActive) {
            delay(3000)
            val resp = Http.request(
                "https://passport.bilibili.com/x/passport-login/web/qrcode/poll",
                query = mapOf("qrcode_key" to key),
                headers = headers,
            )
            val data = parseJson(resp.text)["data"]
            when (data["code"].int) {
                0 -> {
                    // 登录成功：Set-Cookie 里的 name=value 拼起来就是 cookie
                    val cookie = resp.headers.values("set-cookie").joinToString(";") { it.substringBefore(";") }
                    Store.setAccountCookie("bilibili", cookie)
                    toast("登录成功")
                    finish()
                    return
                }
                86090 -> b.status.text = "已扫描，请在手机上确认登录"
                86038 -> {
                    b.status.text = "二维码已失效"
                    b.refresh.visibility = View.VISIBLE
                    b.refresh.requestFocus()
                    return
                }
            }
        }
    }
}

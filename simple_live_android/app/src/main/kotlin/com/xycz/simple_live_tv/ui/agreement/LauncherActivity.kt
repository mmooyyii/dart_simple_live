package com.xycz.simple_live_tv.ui.agreement

import android.content.Intent
import android.os.Bundle
import com.xycz.simple_live_tv.data.Settings
import com.xycz.simple_live_tv.databinding.ActivityAgreementBinding
import com.xycz.simple_live_tv.ui.BaseActivity
import com.xycz.simple_live_tv.ui.home.HomeActivity

/** 启动入口：首次运行显示使用须知，之后直接进首页 */
class LauncherActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.firstRun) {
            goHome()
            return
        }
        val b = ActivityAgreementBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.agree.setOnClickListener {
            Settings.firstRun = false
            goHome()
        }
        b.exit.setOnClickListener { finishAffinity() }
        b.agree.requestFocus()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

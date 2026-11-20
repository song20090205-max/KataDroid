package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.LocaleList
import android.app.LocaleManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.zhzy0077.katadroid.ui.KataDroidApp
import io.github.zhzy0077.katadroid.ui.theme.KataDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep this build in Traditional Chinese (Taiwan), regardless of the device's default language.
        val localeManager = getSystemService(LocaleManager::class.java)
        val traditionalChinese = LocaleList.forLanguageTags("zh-TW")
        if (localeManager.applicationLocales != traditionalChinese) {
            localeManager.applicationLocales = traditionalChinese
        }
        io.github.zhzy0077.katadroid.engine.KataGoModel.refresh(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { KataDroidTheme { KataDroidApp() } }
    }
}

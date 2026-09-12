package com.qianbi.writer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qianbi.writer.ui.AppTheme
import com.qianbi.writer.ui.NovelApp

/**
 * 唯一 Activity。
 *
 * 书架仓库默认放在应用外部私有目录（`Android/data/<包名>/files/NovelStudio/books`）：
 * 不需要任何存储权限。用户也可以在书架界面里改用**自己选的外部文件夹**
 * （Storage Access Framework，持久授权），位置由 `StoragePrefs` 记录。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                NovelApp(applicationContext)
            }
        }
    }
}

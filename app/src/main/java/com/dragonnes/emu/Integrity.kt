package com.dragonnes.emu

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonnes.emu.ui.Neon
import java.security.MessageDigest

/**
 * Anti-tamper: the official app is signed with the developer's private release key.
 * A copy that was modified and re-signed (or rebuilt from a fork) carries a different
 * certificate, so it refuses to run and points to the official release instead.
 */
object Integrity {
    private const val OFFICIAL_PACKAGE = "com.dragonnes.emu"

    @Volatile private var cached: Boolean? = null

    fun isOfficial(context: Context): Boolean = cached ?: check(context).also { cached = it }

    private fun check(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true              // local development builds
        if (context.packageName != OFFICIAL_PACKAGE) return false
        val allowed = BuildConfig.RELEASE_CERTS.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val actual = runCatching { signingCertHashes(context) }.getOrDefault(emptyList())
        return actual.isNotEmpty() && actual.all { it in allowed }
    }

    @Suppress("DEPRECATION")
    private fun signingCertHashes(context: Context): List<String> {
        val pm = context.packageManager
        val sigs: List<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners?.toList().orEmpty()
            else listOfNotNull(info.signingCertificateHistory?.lastOrNull()) // current cert (after rotation)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
        }
    }

    /** Call at the top of an activity's onCreate. Returns false (and shows the blocking screen) for copies. */
    fun guard(activity: Activity): Boolean {
        if (isOfficial(activity)) return true
        CrashReporter.crumb("integrity: unofficial build")
        if (activity is ComponentActivity) {
            activity.setContent { UnofficialScreen { activity.finishAffinity() } }
        } else activity.finish()
        return false
    }
}

@Composable
private fun UnofficialScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Neon.Bg).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(120.dp).clip(CircleShape))
        Spacer(Modifier.height(20.dp))
        Text("نسخة غير أصلية", color = Neon.Magenta, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "هاي النسخة من DragonNES معدّلة أو ما موقّعة من المطوّر، فما تشتغل.\nنزّل النسخة الرسمية من صفحة المطوّر.",
            color = Neon.Text, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text("تطوير: مهند سات لنك", color = Neon.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClose, colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
            Text("إغلاق", color = Neon.Bg, fontWeight = FontWeight.Bold)
        }
    }
}

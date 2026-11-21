package com.dragonnes.emu.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonnes.emu.netplay.LanGame
import com.dragonnes.emu.netplay.NetplaySession
import com.dragonnes.emu.ui.Neon
import com.dragonnes.emu.ui.NeonPanel

/** What the lobby is doing right now. */
sealed class Lobby {
    object Menu : Lobby()
    data class HostingLan(val addresses: List<String>) : Lobby()
    data class FindingLan(val scanning: Boolean, val games: List<LanGame>) : Lobby()
    data class HostingOnline(val code: String, val connected: Boolean) : Lobby()
    object JoinOnline : Lobby()
    data class Connecting(val text: String) : Lobby()
    data class Failed(val text: String) : Lobby()
}

interface LobbyActions {
    fun hostLan()
    fun findLan()
    fun joinLan(address: String)
    fun hostOnline()
    fun openJoinOnline()
    fun joinOnline(code: String)
    fun shareCode(code: String)
    fun cancelLobby()
    fun closePanel()
    fun endSession()
}

/** Netplay lobby shown above the game (RTL, Arabic). */
@Composable
fun NetplayPanel(lobby: Lobby, status: NetplaySession.Status?, onlineReady: Boolean, actions: LobbyActions) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
                // Swallow touches so the game buttons underneath don't react.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final).changes.forEach { it.consume() } } },
            contentAlignment = Alignment.Center,
        ) {
            NeonPanel(Modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f).fillMaxHeight(0.92f), glow = true) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐 لعب لاعبين ويه صديقك", style = Neon.glowText.copy(fontSize = 19.sp), modifier = Modifier.weight(1f))
                        IconButton(actions::closePanel) { Icon(Icons.Default.Close, "إغلاق", tint = Neon.TextDim) }
                    }
                    if (status != null && status.phase != NetplaySession.Phase.ENDED) {
                        SessionInfo(status, actions)
                    } else when (lobby) {
                        Lobby.Menu -> MenuPage(onlineReady, actions)
                        is Lobby.HostingLan -> Waiting(
                            "بانتظار صديقك على نفس الشبكة…",
                            "خلّي صديقك يفتح نفس اللعبة ويضغط \"انضمام بالبيت\". إنت لاعب 1." +
                                if (lobby.addresses.isNotEmpty()) "\nعنوانك: ${lobby.addresses.joinToString(" • ")}" else "",
                            actions,
                        )
                        is Lobby.FindingLan -> FindLan(lobby, actions)
                        is Lobby.HostingOnline -> HostOnline(lobby, actions)
                        Lobby.JoinOnline -> JoinOnline(actions)
                        is Lobby.Connecting -> Waiting(lobby.text, null, actions)
                        is Lobby.Failed -> {
                            Text(lobby.text, color = Neon.Magenta, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(actions::cancelLobby) { Text("رجوع", color = Neon.Cyan) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuPage(onlineReady: Boolean, a: LobbyActions) {
    Text("الشخصين لازم يفتحون نفس اللعبة. المضيف يصير لاعب 1 والمنضم لاعب 2.", color = Neon.TextDim, fontSize = 13.sp)
    Section("🏠 نفس الواي فاي أو الهوتسبوت", "أسرع اتصال، ما يحتاج إنترنت")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("استضافة", Icons.Default.Home, Modifier.weight(1f), a::hostLan)
        BigButton("انضمام", Icons.Default.Home, Modifier.weight(1f), a::findLan)
    }
    Section("🌐 بالإنترنت (كل واحد ببيته)", "المضيف ياخذ كود ويدزه لصديقه")
    if (!onlineReady) {
        Text("سيرفر اللعب أونلاين بعده ما متجهز. راح يشتغل أول ما يتفعّل السيرفر.", color = Neon.Magenta, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("استضافة", Icons.Default.Public, Modifier.weight(1f), a::hostOnline, enabled = onlineReady)
        BigButton("انضمام بالكود", Icons.Default.Public, Modifier.weight(1f), a::openJoinOnline, enabled = onlineReady)
    }
}

@Composable
private fun Section(title: String, sub: String) {
    Column {
        Text(title, color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(sub, color = Neon.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun BigButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick, modifier.height(52.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan, disabledContainerColor = Neon.Surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(icon, null, tint = Neon.Bg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Neon.Bg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Waiting(title: String, body: String?, a: LobbyActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Neon.Cyan, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = Neon.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    body?.let { Text(it, color = Neon.TextDim, fontSize = 13.sp) }
    OutlinedButton(a::cancelLobby) { Text("إلغاء", color = Neon.Magenta) }
}

@Composable
private fun FindLan(l: Lobby.FindingLan, a: LobbyActions) {
    var ip by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("الألعاب المستضافة على شبكتك", color = Neon.Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (l.scanning) CircularProgressIndicator(color = Neon.Cyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton(a::findLan) { Text("بحث مرة ثانية", color = Neon.Cyan) }
    }
    if (!l.scanning && l.games.isEmpty()) {
        Text("ما لكيت أحد. تأكدوا إنكم على نفس الواي فاي، وإن صديقك ضغط \"استضافة\".", color = Neon.TextDim, fontSize = 13.sp)
    }
    l.games.forEach { g ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Neon.Surface)
                .clickable { a.joinLan(g.address) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.hostName, color = Neon.Text, fontWeight = FontWeight.Bold)
                Text("${g.game} • ${g.address}", color = Neon.TextDim, fontSize = 12.sp)
            }
            Text("انضمام", color = Neon.Cyan, fontWeight = FontWeight.Bold)
        }
    }
    Text("أو اكتب عنوان المضيف", color = Neon.TextDim, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(ip, { ip = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("192.168.1.5") })
        Button({ if (ip.isNotBlank()) a.joinLan(ip) }, colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
            Text("اتصال", color = Neon.Bg)
        }
    }
    OutlinedButton(a::cancelLobby) { Text("رجوع", color = Neon.Magenta) }
}

@Composable
private fun HostOnline(l: Lobby.HostingOnline, a: LobbyActions) {
    Text("كود الغرفة", color = Neon.TextDim, fontSize = 13.sp)
    Text(
        l.code, color = Neon.Cyan, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
        letterSpacing = 6.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    Button({ a.shareCode(l.code) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Neon.Magenta)) {
        Icon(Icons.Default.Share, null, tint = Neon.Bg); Spacer(Modifier.width(6.dp))
        Text("دز الكود لصديقك", color = Neon.Bg, fontWeight = FontWeight.Bold)
    }
    Waiting(
        if (l.connected) "صديقك دخل، جاري المزامنة…" else "بانتظار صديقك يدخل الكود…",
        "صديقك يفتح نفس اللعبة ← القائمة ← لعب أونلاين ← انضمام بالكود. إنت لاعب 1.", a,
    )
}

@Composable
private fun JoinOnline(a: LobbyActions) {
    var code by remember { mutableStateOf("") }
    Text("اكتب الكود اللي دزّه صديقك", color = Neon.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, Modifier.fillMaxWidth(),
        singleLine = true, placeholder = { Text("مثلاً K7QM2X") },
        textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
    Button({ if (code.length == 6) a.joinOnline(code) }, Modifier.fillMaxWidth(), enabled = code.length == 6,
        colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
        Text("انضمام", color = Neon.Bg, fontWeight = FontWeight.Bold)
    }
    OutlinedButton(a::cancelLobby) { Text("رجوع", color = Neon.Magenta) }
}

@Composable
private fun SessionInfo(s: NetplaySession.Status, a: LobbyActions) {
    Text("متصل ${s.label}", color = Neon.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    s.peerName?.let { Text("ويه: $it", color = Neon.Text) }
    Text(
        listOfNotNull(
            s.pingMs?.let { "سرعة الاتصال: $it ms" },
            if (s.delayFrames > 0) "تأخير الإدخال: ${s.delayFrames} إطار" else null,
        ).joinToString("  •  "),
        color = Neon.TextDim, fontSize = 13.sp,
    )
    Text("الإعادة بالزمن والتسريع وتحميل الحفظ والجيتات تتعطل وقت اللعب أونلاين حتى تبقى اللعبة نفسها عند الاثنين.",
        color = Neon.TextDim, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(a::closePanel, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
            Text("رجوع للعب", color = Neon.Bg, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(a::endSession, Modifier.weight(1f)) { Text("إنهاء اللعب أونلاين", color = Neon.Magenta) }
    }
}

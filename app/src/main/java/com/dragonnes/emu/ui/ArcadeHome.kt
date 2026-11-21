package com.dragonnes.emu.ui

import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.dragonnes.emu.R
import com.dragonnes.emu.data.Game
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Console-style home: a cover carousel (swipe, arrows, d-pad or controller left/right) with a
 * glowing selected cartridge, launched by tapping it or pressing A / START, over a pixel night
 * scene. A big NES-style pad at the bottom works by touch too.
 */
object HomeKeys {
    /** Set by the home screen; MainActivity forwards controller keys here. */
    var handler: ((Int) -> Boolean)? = null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArcadeHome(vm: LibraryViewModel, onOpenSettings: () -> Unit, onGridView: () -> Unit, onPlay: (Game) -> Unit) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    // Most recently played first, then the rest alphabetically.
    val games = remember(vm.games) {
        vm.games.sortedWith(compareByDescending<Game> { it.lastPlayed }.thenBy { it.title.lowercase() })
    }
    val pager = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()
    fun go(delta: Int) {
        if (games.isEmpty()) return
        scope.launch { pager.animateScrollToPage((pager.currentPage + delta).coerceIn(0, games.size - 1)) }
    }
    fun playCurrent() { games.getOrNull(pager.currentPage)?.let(onPlay) }

    DisposableEffect(games) {
        HomeKeys.handler = { key ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> { go(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> { go(1); true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER -> { playCurrent(); true }
                KeyEvent.KEYCODE_BUTTON_SELECT -> { onOpenSettings(); true }
                else -> false
            }
        }
        onDispose { HomeKeys.handler = null }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Neon.Bg)) {
            val landscape = maxWidth > maxHeight
            val screenW = maxWidth; val screenH = maxHeight
            NightScene(Modifier.fillMaxSize())

            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // ---- Top bar: cartridge logo, title, actions ----
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.app_logo), null,
                        Modifier.size(if (landscape) 52.dp else 46.dp).neonGlow(26.dp, strength = 0.5f).clip(CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("DragonNES", style = Neon.glowText.copy(fontSize = 22.sp))
                        Text("${games.size} لعبة", color = Neon.TextDim, fontSize = 12.sp)
                    }
                    RoundIcon(Icons.Default.CreateNewFolder, "إضافة مجلد") { pickFolder.launch(null) }
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Default.GridView, "عرض الشبكة", onGridView)
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Default.Settings, "الإعدادات", onOpenSettings)
                }

                // ---- Carousel ----
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (games.isEmpty()) {
                        EmptyHint { pickFolder.launch(null) }
                    } else {
                        val cardW = if (landscape) (screenH * 0.34f).coerceIn(120.dp, 220.dp) else (screenW * 0.42f).coerceIn(130.dp, 220.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Arrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { go(-1) }
                            BoxWithConstraints(Modifier.weight(1f)) {
                                val side = ((maxWidth - cardW) / 2).coerceAtLeast(0.dp)
                                HorizontalPager(
                                    state = pager,
                                    pageSize = PageSize.Fixed(cardW),
                                    contentPadding = PaddingValues(horizontal = side),
                                    pageSpacing = 14.dp,
                                    beyondViewportPageCount = 3,
                                    key = { games[it].uri },
                                ) { page ->
                                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                                    val dist = abs(offset).coerceAtMost(2f)
                                    Cartridge(
                                        games[page], selected = page == pager.currentPage,
                                        modifier = Modifier.width(cardW).graphicsLayer {
                                            val s = 1f - 0.18f * dist
                                            scaleX = s; scaleY = s
                                            alpha = 1f - 0.3f * dist
                                        },
                                    ) {
                                        if (page == pager.currentPage) onPlay(games[page])
                                        else scope.launch { pager.animateScrollToPage(page) }
                                    }
                                }
                            }
                            Arrow(Icons.AutoMirrored.Filled.KeyboardArrowRight) { go(1) }
                        }
                    }
                }

                // ---- Selected game title ----
                games.getOrNull(pager.currentPage)?.let { g ->
                    Text(
                        g.title, color = Neon.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                // ---- NES-style pad: ◀ ▶ to browse, A / START to play, SELECT = settings ----
                HomePad(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(if (landscape) 118.dp else 132.dp),
                    onLeft = { go(-1) }, onRight = { go(1) }, onPlay = ::playCurrent, onSelect = onOpenSettings,
                )
            }

            vm.scanning?.let {
                Text("جاري فحص الألعاب… $it", color = Neon.Text, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                        .clip(RoundedCornerShape(50)).background(Neon.Surface.copy(alpha = 0.9f)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).neonGlow(22.dp, Neon.Magenta, 0.35f).clip(CircleShape)
            .background(Neon.Surface.copy(alpha = 0.85f)).border(2.dp, Neon.edge, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Neon.Cyan, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(56.dp)) {
        Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun Cartridge(game: Game, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.aspectRatio(0.74f)
            .then(if (selected) Modifier.neonGlow(14.dp, Neon.Magenta, 0.6f) else Modifier)
            .clip(shape)
            .background(Neon.Surface)
            .border(if (selected) 4.dp else 2.dp, if (selected) Neon.edge else Brush.linearGradient(listOf(Neon.SurfaceHi, Neon.SurfaceHi)), shape)
            .clickable(onClick = onClick)
            .padding(if (selected) 6.dp else 4.dp),
    ) {
        SubcomposeAsyncImage(
            model = game.coverUrl, contentDescription = game.title, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            error = { CoverText(game) }, loading = { CoverText(game) },
        )
    }
}

@Composable
private fun CoverText(game: Game) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Neon.Purple.copy(alpha = 0.6f), Neon.Bg, Neon.Cyan.copy(alpha = 0.4f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(game.title, color = Neon.Text, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun EmptyHint(onAdd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text("ضيف مجلد ألعابك 🎮", color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text("الألعاب الجديدة اللي تحطها بالمجلد تطلع هنا تلقائياً", color = Neon.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Neon.Cyan).clickable(onClick = onAdd).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text("اختيار مجلد الألعاب", color = Neon.Bg, fontWeight = FontWeight.Bold)
        }
    }
}

/** Big NES pad: d-pad left/right browse, START / A play, SELECT opens settings. */
@Composable
private fun HomePad(modifier: Modifier, onLeft: () -> Unit, onRight: () -> Unit, onPlay: () -> Unit, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.neonGlow(28.dp, Neon.Cyan, 0.3f).clip(shape)
            .background(Brush.verticalGradient(listOf(Neon.SurfaceHi, Neon.Surface)))
            .border(2.dp, Neon.edge, shape).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // D-pad
        Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.36f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262B)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.36f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262B)))
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onLeft), contentAlignment = Alignment.CenterStart) {
                    Text("◀", color = Neon.Cyan, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onRight), contentAlignment = Alignment.CenterEnd) {
                    Text("▶", color = Neon.Cyan, fontSize = 18.sp, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Select / Start
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("SELECT", onSelect)
            Pill("START", onPlay)
        }
        Spacer(Modifier.weight(1f))
        // B / A
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RedButton("B", onLeft)
            RedButton("A", onPlay)
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp, 20.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2A30))
            .border(2.dp, Neon.edge, RoundedCornerShape(50)).clickable(onClick = onClick))
        Text(label, color = Neon.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RedButton(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).neonGlow(27.dp, Color(0xFFFF2D2D), 0.4f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFF6B6B), Color(0xFFD10F0F), Color(0xFF7A0000))))
                .border(2.dp, Neon.edge, CircleShape).clickable(onClick = onClick),
        )
        Text(label, color = Neon.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Pixel-art style night sky: gradient, stars, moon, a castle silhouette and blocky hills, tinted by the theme. */
@Composable
private fun NightScene(modifier: Modifier) {
    val top = Neon.Bg
    val mid = androidx.compose.ui.graphics.lerp(Color(0xFF14104A), Neon.Purple, 0.25f)
    val low = androidx.compose.ui.graphics.lerp(Color(0xFF3A1A6E), Neon.Magenta, 0.25f)
    val glow = Neon.Cyan
    val stars = remember { val r = Random(7); List(70) { Offset(r.nextFloat(), r.nextFloat() * 0.6f) to (1 + r.nextInt(3)) } }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(top, mid, low)))
        stars.forEach { (p, s) ->
            drawRect(Color.White.copy(alpha = 0.55f), Offset(p.x * size.width, p.y * size.height), Size(s * 2f, s * 2f))
        }
        // Moon
        val moon = Offset(size.width * 0.84f, size.height * 0.18f)
        val mr = size.minDimension * 0.07f
        for (i in 4 downTo 1) drawCircle(glow.copy(alpha = 0.06f * i), mr * (1f + i * 0.35f), moon)
        drawCircle(Color(0xFFDDF7FF), mr, moon)
        // Castle silhouette (blocks)
        val px = size.minDimension / 90f
        val base = size.height * 0.72f
        val cx = size.width * 0.88f
        val castle = Color(0xFF120B2E)
        drawRect(castle, Offset(cx - 14 * px, base - 22 * px), Size(28 * px, 22 * px))
        listOf(-14f, -2f, 10f).forEachIndexed { i, dx ->
            val h = (30 + i % 2 * 10) * px
            drawRect(castle, Offset(cx + dx * px - 2 * px, base - h), Size(8 * px, h))
            drawRect(Color(0xFFFFC857).copy(alpha = 0.8f), Offset(cx + dx * px + 1 * px, base - h + 6 * px), Size(2 * px, 3 * px))
        }
        // Blocky hills + ground
        val ground = androidx.compose.ui.graphics.lerp(Color(0xFF0B3D1E), Neon.Cyan, 0.08f)
        var x = 0f
        val rnd = Random(3)
        while (x < size.width) {
            val h = (6 + rnd.nextInt(10)) * px
            drawRoundRect(ground, Offset(x, base - h), Size(8 * px, h + size.height), CornerRadius(px, px))
            x += 8 * px
        }
        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, base), Size(size.width, size.height - base))
    }
}

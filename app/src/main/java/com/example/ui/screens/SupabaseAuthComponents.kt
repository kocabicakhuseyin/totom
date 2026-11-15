package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.repository.OAuthProvider
import com.example.repository.PoolRepository
import com.example.ui.theme.CasinoGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibrantRed
import kotlinx.coroutines.launch

enum class AuthFormTab(val title: String) {
    SIGN_IN("Giriş Yap"),
    REGISTER("Üye Ol"),
    RECOVER("Şifre Sıfırla")
}

@Composable
fun SupabaseAuthDialog(
    repository: PoolRepository,
    onDismiss: () -> Unit,
    onAuthenticated: (String) -> Unit = {}
) {
    val session by repository.sessionState.collectAsStateWithLifecycle()

    LaunchedEffect(session.isSignedIn) {
        if (session.isSignedIn) {
            onAuthenticated(session.displayName)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CasinoGold.copy(alpha = 0.16f))
                            .border(1.dp, CasinoGold.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Üyelik",
                            tint = CasinoGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Spor Toto Üyelik Merkezi",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Google, Apple ID veya E-posta (Supabase)",
                            color = ElectricCyan,
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = TextMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SupabaseAuthCard(
                    repository = repository,
                    embedded = true,
                    onAuthCompleted = { name ->
                        onAuthenticated(name)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Daha Sonra", color = TextMuted)
            }
        }
    )
}

@Composable
fun SupabaseAuthCard(
    repository: PoolRepository,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    title: String = "Üyelik Girişi / Kayıt",
    subtitle: String = "Google hesabınız, Apple ID'niz veya e-posta adresinizle Supabase üzerinden güvenli giriş yapın.",
    onAuthCompleted: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val oauthStatus by repository.oauthStatus.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(AuthFormTab.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(oauthStatus) {
        oauthStatus.message?.let { msg ->
            statusMessage = msg
            isError = oauthStatus.isError
        }
    }

    val effectiveLoading = isLoading || oauthStatus.inProgress

    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (embedded) Modifier else Modifier.padding(14.dp)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!embedded) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            // OAuth Provider Buttons: Google & Apple ID
            Button(
                onClick = {
                    isError = false
                    statusMessage = "Google giriş sayfası açılıyor..."
                    repository.launchOAuthSignIn(context, OAuthProvider.GOOGLE)
                        .onFailure { err ->
                            isError = true
                            statusMessage = err.message ?: "Google girişi açılamadı."
                        }
                },
                enabled = !effectiveLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1F1F1F)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_auth_google")
            ) {
                GoogleGMark(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Google ile Devam Et",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            OutlinedButton(
                onClick = {
                    isError = false
                    statusMessage = "Apple ID giriş sayfası açılıyor..."
                    repository.launchOAuthSignIn(context, OAuthProvider.APPLE)
                        .onFailure { err ->
                            isError = true
                            statusMessage = err.message ?: "Apple ID girişi açılamadı."
                        }
                },
                enabled = !effectiveLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF0A0A0C),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color(0xFF3A4458)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_auth_apple")
            ) {
                AppleLogoMark(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Apple ID ile Devam Et",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = DarkCardBorder)
                Text(
                    text = "  veya e-posta ile  ",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = DarkCardBorder)
            }

            // Tab switcher: Giriş Yap / Üye Ol / Şifre Sıfırla
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkBackground)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AuthFormTab.values().forEach { tab ->
                    val selected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) CasinoGold else Color.Transparent)
                            .clickable(enabled = !isLoading) {
                                activeTab = tab
                                statusMessage = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (selected) Color.Black else TextSecondary,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            AnimatedVisibility(visible = activeTab == AuthFormTab.REGISTER) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(30) },
                    label = { Text("Kullanıcı / Takma Ad (İsteğe bağlı)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedIndicatorColor = CasinoGold,
                        unfocusedIndicatorColor = DarkCardBorder
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_auth_username")
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(160) },
                label = { Text("E-posta adresi", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = CasinoGold,
                        modifier = Modifier.size(18.dp)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedIndicatorColor = CasinoGold,
                    unfocusedIndicatorColor = DarkCardBorder
                ),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_auth_email")
            )

            AnimatedVisibility(visible = activeTab != AuthFormTab.RECOVER) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Şifre (en az 6 karakter)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CasinoGold,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Şifre görünürlüğü",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedIndicatorColor = CasinoGold,
                        unfocusedIndicatorColor = DarkCardBorder
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_auth_password")
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        isError = false
                        statusMessage = null
                        when (activeTab) {
                            AuthFormTab.SIGN_IN -> {
                                repository.signIn(email, password)
                                    .onSuccess {
                                        password = ""
                                        statusMessage = "✅ Üye girişi başarılı!"
                                        onAuthCompleted(repository.sessionState.value.displayName)
                                    }
                                    .onFailure {
                                        isError = true
                                        statusMessage = it.message ?: "Giriş yapılamadı."
                                    }
                            }
                            AuthFormTab.REGISTER -> {
                                repository.register(email, password, displayName)
                                    .onSuccess { signedInNow ->
                                        password = ""
                                        if (signedInNow) {
                                            statusMessage = "✅ Üyelik oluşturuldu ve giriş yapıldı!"
                                            onAuthCompleted(repository.sessionState.value.displayName)
                                        } else {
                                            statusMessage = "📩 Kayıt tamamlandı! E-postanıza gelen doğrulama bağlantısını onaylayıp giriş yapın."
                                        }
                                    }
                                    .onFailure {
                                        isError = true
                                        statusMessage = it.message ?: "Kayıt yapılamadı."
                                    }
                            }
                            AuthFormTab.RECOVER -> {
                                repository.sendPasswordReset(email)
                                    .onSuccess {
                                        statusMessage = "📩 Şifre sıfırlama bağlantısı e-posta adresinize gönderildi."
                                    }
                                    .onFailure {
                                        isError = true
                                        statusMessage = it.message ?: "Şifre sıfırlama bağlantısı gönderilemedi."
                                    }
                            }
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CasinoGold,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_auth_submit")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("İşleniyor...", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = when (activeTab) {
                            AuthFormTab.SIGN_IN -> "E-posta ile Giriş Yap"
                            AuthFormTab.REGISTER -> "Üye Ol"
                            AuthFormTab.RECOVER -> "Sıfırlama E-postası Gönder"
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
            }

            statusMessage?.let { msg ->
                val bgColor = if (isError) VibrantRed.copy(alpha = 0.14f) else ElectricGreen.copy(alpha = 0.14f)
                val borderColor = if (isError) VibrantRed.copy(alpha = 0.55f) else ElectricGreen.copy(alpha = 0.55f)
                val textColor = if (isError) Color(0xFFFCA5A5) else ElectricGreen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = msg,
                        color = textColor,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Text(
                text = "OAuth dönüş adresi: ${PoolRepository.AUTH_REDIRECT_URI}",
                color = TextMuted,
                fontSize = 10.sp
            )
        }
    }

    if (embedded) {
        Box(modifier = modifier.fillMaxWidth()) {
            cardContent()
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DarkCardBorder)
        ) {
            cardContent()
        }
    }
}

@Composable
private fun GoogleGMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        // Blue right/bottom
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 75f,
            useCenter = false,
            style = stroke
        )
        // Green bottom
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 60f,
            sweepAngle = 95f,
            useCenter = false,
            style = stroke
        )
        // Yellow left
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 155f,
            sweepAngle = 65f,
            useCenter = false,
            style = stroke
        )
        // Red top
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 220f,
            sweepAngle = 95f,
            useCenter = false,
            style = stroke
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = androidx.compose.ui.geometry.Offset(size.width * 0.52f, size.height * 0.5f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.5f),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
private fun AppleLogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val leaf = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.52f, h * 0.25f)
            cubicTo(w * 0.50f, h * 0.12f, w * 0.62f, h * 0.04f, w * 0.71f, h * 0.04f)
            cubicTo(w * 0.72f, h * 0.16f, w * 0.61f, h * 0.25f, w * 0.52f, h * 0.25f)
            close()
        }
        drawPath(path = leaf, color = Color.White)

        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.51f, h * 0.31f)
            cubicTo(w * 0.39f, h * 0.27f, w * 0.24f, h * 0.30f, w * 0.17f, h * 0.43f)
            cubicTo(w * 0.10f, h * 0.58f, w * 0.17f, h * 0.81f, w * 0.28f, h * 0.92f)
            cubicTo(w * 0.34f, h * 0.97f, w * 0.40f, h * 0.96f, w * 0.46f, h * 0.94f)
            cubicTo(w * 0.49f, h * 0.93f, w * 0.52f, h * 0.93f, w * 0.56f, h * 0.94f)
            cubicTo(w * 0.62f, h * 0.96f, w * 0.68f, h * 0.97f, w * 0.74f, h * 0.91f)
            cubicTo(w * 0.81f, h * 0.84f, w * 0.85f, h * 0.75f, w * 0.87f, h * 0.71f)
            cubicTo(w * 0.74f, h * 0.65f, w * 0.72f, h * 0.48f, w * 0.84f, h * 0.40f)
            cubicTo(w * 0.78f, h * 0.31f, w * 0.67f, h * 0.28f, w * 0.58f, h * 0.31f)
            cubicTo(w * 0.55f, h * 0.32f, w * 0.53f, h * 0.32f, w * 0.51f, h * 0.31f)
            close()
        }
        drawPath(path = body, color = Color.White)
    }
}

package com.nchat.app.feature.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nchat.app.data.ServiceLocator
import com.nchat.app.data.repository.AuthResult
import kotlinx.coroutines.delay

private val AuthBackground = listOf(
    Color(0xFFA855F7),
    Color(0xFF8B5CF6),
    Color(0xFF7C3AED)
)

private val TitleColor = Color(0xFF1F2937)
private val SubtitleColor = Color(0xFF6B7280)
private val LabelColor = Color(0xFF374151)
private val FieldBorder = Color(0xFFE5E7EB)

private val EmailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private enum class EmailStatus { Idle, Checking, Available, Taken }

/**
 * `Auth.tsx`: purple gradient backdrop with floating circles, a white card holding the
 * logo, the Welcome Back / Create Account copy, the email + password form with live email
 * availability while signing up, and the switch-mode footer.
 */
@Composable
fun AuthScreen(modifier: Modifier = Modifier) {
    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var emailStatus by remember { mutableStateOf(EmailStatus.Idle) }

    // Sign-up only: tell the user straight away when an email is already taken.
    LaunchedEffect(email, isLogin) {
        if (isLogin) {
            emailStatus = EmailStatus.Idle
            return@LaunchedEffect
        }
        val trimmed = email.trim()
        if (!EmailRegex.matches(trimmed)) {
            emailStatus = EmailStatus.Idle
            return@LaunchedEffect
        }
        emailStatus = EmailStatus.Checking
        // Debounce while typing before asking the backend (`GET /auth/check-email`).
        delay(350)
        emailStatus = if (ServiceLocator.authRepository.isEmailRegistered(trimmed)) {
            EmailStatus.Taken
        } else {
            EmailStatus.Available
        }
    }

    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        val repository = ServiceLocator.authRepository
        val result = if (isLogin) {
            repository.signIn(email, password)
        } else {
            repository.signUp(email, password)
        }
        when (result) {
            is AuthResult.Success -> Unit
            is AuthResult.Failure -> error = result.message
        }
        loading = false
    }

    fun submit() {
        if (loading) return
        error = ""
        success = ""
        val trimmed = email.trim()
        when {
            !EmailRegex.matches(trimmed) -> error = "Please enter a valid email address"
            !isLogin && password != confirmPassword -> error = "Passwords do not match"
            password.length < 6 -> error = "Password must be at least 6 characters"
            !isLogin && emailStatus == EmailStatus.Taken ->
                error = "This email is already registered — log in instead"
            else -> loading = true
        }
    }

    fun switchMode() {
        isLogin = !isLogin
        error = ""
        success = ""
        emailStatus = EmailStatus.Idle
        password = ""
        confirmPassword = ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.linearGradient(AuthBackground))
    ) {
        AuthDecor()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(horizontal = 26.dp, vertical = 34.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFFA855F7), Color(0xFF7C3AED)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubble,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = if (isLogin) "Welcome Back" else "Create Account",
                    color = TitleColor,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp)
                )
                Text(
                    text = if (isLogin) {
                        "Sign in to continue to NChat"
                    } else {
                        "Sign up to get started with NChat"
                    },
                    color = SubtitleColor,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 22.dp)
                )

                if (error.isNotEmpty()) {
                    Banner(
                        message = error,
                        icon = Icons.Rounded.Info,
                        background = Color(0xFFFEF2F2),
                        border = Color(0xFFFECACA),
                        foreground = Color(0xFFDC2626),
                        iconTint = Color(0xFFEF4444)
                    )
                }
                if (success.isNotEmpty()) {
                    Banner(
                        message = success,
                        icon = Icons.Rounded.Check,
                        background = Color(0xFFF0FDF4),
                        border = Color(0xFFBBF7D0),
                        foreground = Color(0xFF16A34A),
                        iconTint = Color(0xFF22C55E)
                    )
                }

                AuthField(
                    label = "Email",
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "Enter your email",
                    leadingIcon = Icons.Rounded.Email,
                    keyboardType = KeyboardType.Email,
                    enabled = !loading,
                    trailing = {
                        if (!isLogin && emailStatus != EmailStatus.Idle) {
                            when (emailStatus) {
                                EmailStatus.Checking -> CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFC4B5FD)
                                )

                                EmailStatus.Available -> Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Email available",
                                    tint = Color(0xFF22C55E),
                                    modifier = Modifier.size(17.dp)
                                )

                                EmailStatus.Taken -> Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Email taken",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(17.dp)
                                )

                                EmailStatus.Idle -> Unit
                            }
                        }
                    }
                )

                if (!isLogin) {
                    when (emailStatus) {
                        EmailStatus.Taken -> Row(
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "This email is already registered. ",
                                color = Color(0xFFEF4444),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Log in instead",
                                color = Color(0xFF7C3AED),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { switchMode() }
                            )
                        }

                        EmailStatus.Available -> Text(
                            text = "Email available",
                            color = Color(0xFF16A34A),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                        )

                        else -> Unit
                    }
                }

                AuthField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Enter your password",
                    leadingIcon = Icons.Rounded.Lock,
                    keyboardType = KeyboardType.Password,
                    isPassword = !showPassword,
                    enabled = !loading,
                    modifier = Modifier.padding(top = 16.dp),
                    trailing = {
                        Icon(
                            imageVector = if (showPassword) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { showPassword = !showPassword }
                        )
                    }
                )

                if (!isLogin) {
                    AuthField(
                        label = "Confirm Password",
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        placeholder = "Confirm your password",
                        leadingIcon = Icons.Rounded.Lock,
                        keyboardType = KeyboardType.Password,
                        isPassword = !showConfirm,
                        enabled = !loading,
                        modifier = Modifier.padding(top = 16.dp),
                        trailing = {
                            Icon(
                                imageVector = if (showConfirm) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showConfirm) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { showConfirm = !showConfirm }
                            )
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFA855F7),
                                    Color(0xFF8B5CF6),
                                    Color(0xFF7C3AED)
                                )
                            )
                        )
                        .clickable(enabled = !loading, onClick = { submit() }),
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isLogin) "Sign In" else "Sign Up",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isLogin) {
                            "Don't have an account?"
                        } else {
                            "Already have an account?"
                        },
                        color = SubtitleColor,
                        fontSize = 13.sp
                    )
                    Box(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isLogin) "Sign Up" else "Sign In",
                        color = Color(0xFF7C3AED),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { switchMode() }
                    )
                }
            }
        }
    }
}

/** Gradient backdrop decorations: soft white waves top and bottom plus four floating circles. */
@Composable
private fun AuthDecor() {
    val transition = rememberInfiniteTransition(label = "authFloat")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "authFloatOffset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Top waves
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h * 0.2f)
                quadraticBezierTo(w * 0.8f, h * 0.36f, w * 0.5f, h * 0.3f)
                quadraticBezierTo(w * 0.2f, h * 0.24f, 0f, h * 0.36f)
                close()
            },
            color = Color(0x26FFFFFF)
        )
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h * 0.12f)
                quadraticBezierTo(w * 0.76f, h * 0.28f, w * 0.4f, h * 0.2f)
                quadraticBezierTo(w * 0.1f, h * 0.14f, 0f, h * 0.24f)
                close()
            },
            color = Color(0x1AFFFFFF)
        )

        // Bottom waves
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h)
                lineTo(w, h)
                lineTo(w, h * 0.8f)
                quadraticBezierTo(w * 0.8f, h * 0.64f, w * 0.5f, h * 0.7f)
                quadraticBezierTo(w * 0.2f, h * 0.76f, 0f, h * 0.64f)
                close()
            },
            color = Color(0x26FFFFFF)
        )
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h)
                lineTo(w, h)
                lineTo(w, h * 0.9f)
                quadraticBezierTo(w * 0.7f, h * 0.74f, w * 0.36f, h * 0.82f)
                quadraticBezierTo(w * 0.1f, h * 0.88f, 0f, h * 0.8f)
                close()
            },
            color = Color(0x1AFFFFFF)
        )

        // Floating circles (translateY -20 at the peak, as in the reference keyframes).
        val drop = float * 20.dp.toPx()
        drawCircle(Color(0x1AFFFFFF), 40.dp.toPx(), Offset(w * 0.14f, h * 0.16f - drop))
        drawCircle(Color(0x1AFFFFFF), 60.dp.toPx(), Offset(w * 0.88f, h * 0.6f + drop))
        drawCircle(Color(0x1AFFFFFF), 30.dp.toPx(), Offset(w * 0.18f, h * 0.78f - drop))
        drawCircle(Color(0x1AFFFFFF), 20.dp.toPx(), Offset(w * 0.78f, h * 0.26f + drop))
    }
}

@Composable
private fun Banner(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    border: Color,
    foreground: Color,
    iconTint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = message,
            color = foreground,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = LabelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF9FAFB))
                .border(1.dp, FieldBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(18.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = TitleColor),
                    cursorBrush = SolidColor(Color(0xFF7C3AED)),
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF9CA3AF),
                        fontSize = 14.sp
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 6.dp)) { trailing() }
            }
        }
    }
}

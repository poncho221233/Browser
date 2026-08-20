package com.antidetect.browser.ui.profiles

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antidetect.browser.AntiDetectApp
import com.antidetect.browser.auth.DeviceAuth
import com.antidetect.browser.data.ProfileEntity
import com.antidetect.browser.ui.browser.BrowserActivity
import com.antidetect.browser.ui.newprofile.NewProfileActivity
import com.antidetect.browser.ui.theme.*
import com.antidetect.browser.utils.ProxyUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ProfilesViewModel : ViewModel() {
    private val repository = AntiDetectApp.instance.repository

    val profiles = repository.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            repository.delete(profile)
        }
    }

    fun touch(profileId: Long) {
        viewModelScope.launch {
            repository.touchLastUsed(profileId)
        }
    }
}

class ProfilesActivity : ComponentActivity() {

    private val viewModel: ProfilesViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ProfilesViewModel() as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectId = getString(com.antidetect.browser.R.string.firebase_project_id)
        val apiKey = getString(com.antidetect.browser.R.string.firebase_api_key)
        val authUrl = getString(com.antidetect.browser.R.string.auth_devices_url)
        setContent {
            AntiDetectTheme {
                var authState by remember {
                    mutableStateOf<DeviceAuth.AuthResult?>(null)
                }
                var checking by remember { mutableStateOf(true) }
                val scope = rememberCoroutineScope()

                fun recheck() {
                    checking = true
                    scope.launch {
                        DeviceAuth.clearCache(this@ProfilesActivity)
                        authState = DeviceAuth.isAuthorized(
                            this@ProfilesActivity, projectId, apiKey, authUrl
                        )
                        checking = false
                    }
                }

                LaunchedEffect(Unit) {
                    authState = DeviceAuth.isAuthorized(
                        this@ProfilesActivity, projectId, apiKey, authUrl
                    )
                    checking = false
                }

                when {
                    checking -> {
                        Box(
                            Modifier.fillMaxSize().background(BackgroundDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentPurple)
                                Spacer(Modifier.height(16.dp))
                                Text("Verificando dispositivo…", color = TextSecondary)
                            }
                        }
                    }
                    authState?.authorized != true -> {
                        DeviceLockedScreen(
                            result = authState,
                            authUrl = authUrl,
                            onRetry = { recheck() }
                        )
                    }
                    else -> {
                        ProfilesScreen(
                            viewModel = viewModel,
                            onCreateNew = {
                                startActivity(Intent(this, NewProfileActivity::class.java))
                            },
                            onEdit = { profile ->
                                val intent = Intent(this, NewProfileActivity::class.java)
                                intent.putExtra("profile_id", profile.id)
                                startActivity(intent)
                            },
                            onLaunch = { profile ->
                                viewModel.touch(profile.id)
                                val intent = Intent(this, BrowserActivity::class.java)
                                intent.putExtra("profile_id", profile.id)
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceLockedScreen(
    result: DeviceAuth.AuthResult?,
    authUrl: String,
    onRetry: () -> Unit
) {
    val id = result?.deviceId ?: "…"
    Box(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E2E))
                .padding(24.dp)
        ) {
            Text(
                "Dispositivo no autorizado",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                result?.message ?: "Este teléfono no está en la lista de dispositivos permitidos.",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))
            Text("ID de este dispositivo", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                id,
                color = AccentPurple,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "El dispositivo se registra solo en Firestore.\n" +
                    "En Console → authorized_devices:\n" +
                    "busca este ID y pon enabled = true.\n\n" +
                    "Reglas necesarias:\n" +
                    "allow read: if true;\n" +
                    "allow create: if request.resource.data.enabled == false;\n" +
                    "allow update: if false;",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
            ) {
                Text("Reintentar")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    onCreateNew: () -> Unit,
    onEdit: (ProfileEntity) -> Unit,
    onLaunch: (ProfileEntity) -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "GestorTDD",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateNew,
                containerColor = AccentPurple,
                contentColor = TextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Profile")
            }
        },
        containerColor = BackgroundDark
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No profiles yet",
                        color = TextSecondary,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Toca + para crear tu primer perfil",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onEdit = { onEdit(profile) },
                        onDelete = { viewModel.deleteProfile(profile) },
                        onLaunch = { onLaunch(profile) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileCard(
    profile: ProfileEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLaunch: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }
    val lastUsedText = if (profile.lastUsed > 0) {
        dateFormat.format(Date(profile.lastUsed))
    } else {
        "Never used"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(profile.avatarColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name.ifBlank { "Unnamed Profile" },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.os} · ${profile.fingerprintTemplate}",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextSecondary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = ProxyUtils.formatProxyDisplay(profile),
                        color = if (profile.proxyType == "None") TextSecondary else SuccessGreen,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Last used: $lastUsedText",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Launch")
                }
            }
        }
    }
}

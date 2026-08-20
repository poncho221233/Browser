@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.antidetect.browser.ui.newprofile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.antidetect.browser.AntiDetectApp
import com.antidetect.browser.data.ProfileEntity
import com.antidetect.browser.fingerprint.FingerprintRepository
import com.antidetect.browser.fingerprint.FingerprintTemplate
import com.antidetect.browser.ui.theme.*
import com.antidetect.browser.utils.FingerprintTemplates
import kotlinx.coroutines.launch

class NewProfileViewModel : ViewModel() {
    private val repository = AntiDetectApp.instance.repository
    private val fingerprintRepo = FingerprintRepository(AntiDetectApp.instance)

    var profile by mutableStateOf(ProfileEntity(avatarColor = FingerprintTemplates.randomAvatarColor()))
        private set

    var catalog by mutableStateOf<List<FingerprintTemplate>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** Currently selected template (for extras sent to Gecko) */
    var selectedTemplate by mutableStateOf<FingerprintTemplate?>(null)
        private set

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            catalog = fingerprintRepo.getCatalog()
        }
    }

    fun load(id: Long) {
        viewModelScope.launch {
            isLoading = true
            repository.getProfile(id)?.let { profile = it }
            isLoading = false
        }
    }

    fun update(transform: (ProfileEntity) -> ProfileEntity) {
        profile = transform(profile)
    }

    fun applyTemplateByName(name: String) {
        val tpl = catalog.find { it.name == name }
        if (tpl != null) {
            selectedTemplate = tpl
            profile = fingerprintRepo.applyToProfile(profile, tpl)
            statusMessage = "Applied: ${tpl.name}"
        } else {
            // Fallback to legacy static templates
            profile = FingerprintTemplates.applyTemplate(profile, name)
            selectedTemplate = null
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            isLoading = true
            val tpl = fingerprintRepo.importFromUri(uri)
            if (tpl != null) {
                refreshCatalog()
                selectedTemplate = tpl
                profile = fingerprintRepo.applyToProfile(profile, tpl)
                statusMessage = "Imported: ${tpl.name}"
            } else {
                statusMessage = "Import failed"
            }
            isLoading = false
        }
    }

    fun syncRemote(url: String) {
        viewModelScope.launch {
            isLoading = true
            val list = fingerprintRepo.syncRemote(url)
            refreshCatalog()
            statusMessage = if (list.isNotEmpty()) "Synced ${list.size} templates" else "Sync failed or empty"
            isLoading = false
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.save(profile)
            onDone()
        }
    }

    fun clearStatus() { statusMessage = null }
}

class NewProfileActivity : ComponentActivity() {

    private val viewModel: NewProfileViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NewProfileViewModel() as T
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getLongExtra("profile_id", 0L)
        if (profileId > 0) viewModel.load(profileId)

        setContent {
            AntiDetectTheme {
                NewProfileScreen(
                    viewModel = viewModel,
                    isEdit = profileId > 0,
                    onBack = { finish() },
                    onSaved = { finish() },
                    onImportJson = {
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProfileScreen(
    viewModel: NewProfileViewModel,
    isEdit: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onImportJson: () -> Unit
) {
    val profile = viewModel.profile
    val catalog = viewModel.catalog
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Identity", "Hardware", "Fingerprint", "Network", "Devices", "Proxy")
    var remoteUrl by remember { mutableStateOf("") }

    // Snackbar for status
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEdit) "Edit Profile" else "New Profile",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onImportJson) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import JSON")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(color = SurfaceDark, tonalElevation = 8.dp) {
                Button(
                    onClick = { viewModel.save(onSaved) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Save Profile", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        },
        containerColor = BackgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = textFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OSSelector(
                    selected = profile.os,
                    onSelect = { os ->
                        viewModel.update { it.copy(os = os, fingerprintTemplate = "") }
                        // Auto-apply first fingerprint of that OS so GPU/UA never stay from previous OS
                        val first = catalog.firstOrNull {
                            val t = it.os.lowercase()
                            val s = os.lowercase()
                            when {
                                s == "ios" -> t.contains("ios") || t.contains("iphone")
                                s == "macos" -> t.contains("mac") && !t.contains("ios")
                                s == "android" -> t.contains("android")
                                s == "windows" -> t.contains("win")
                                s == "linux" -> t.contains("linux") || t.contains("ubuntu")
                                else -> t.contains(s)
                            }
                        }
                        if (first != null) {
                            viewModel.applyTemplateByName(first.name)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                // Catalog filtered by selected OS only
                CatalogDropdown(
                    catalog = catalog,
                    selectedOs = profile.os,
                    selected = profile.fingerprintTemplate,
                    onSelect = { viewModel.applyTemplateByName(it) }
                )

                Spacer(Modifier.height(8.dp))

                // Remote sync row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("Remote catalog URL") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = textFieldColors(),
                        placeholder = { Text("https://…/fingerprints.json") }
                    )
                    IconButton(
                        onClick = { if (remoteUrl.isNotBlank()) viewModel.syncRemote(remoteUrl) }
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Sync", tint = AccentPurple)
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = BackgroundDark,
                contentColor = AccentPurple,
                edgePadding = 16.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> IdentityTab(profile, viewModel)
                    1 -> HardwareTab(profile, viewModel)
                    2 -> FingerprintTab(profile, viewModel)
                    3 -> NetworkTab(profile, viewModel)
                    4 -> DevicesTab(profile, viewModel)
                    5 -> ProxyTab(profile, viewModel)
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentPurple,
    unfocusedBorderColor = Divider,
    focusedLabelColor = AccentPurple,
    cursorColor = AccentPurple,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)

@Composable
fun OSSelector(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf("Windows", "macOS", "Linux", "Android", "iOS")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { os ->
            FilterChip(
                selected = selected.equals(os, ignoreCase = true),
                onClick = { onSelect(os) },
                label = { Text(os, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPurple,
                    selectedLabelColor = TextPrimary
                )
            )
        }
    }
}

/** Map UI OS label → template.os values that belong to that family */
private fun osMatches(templateOs: String, selectedOs: String): Boolean {
    val t = templateOs.lowercase()
    val s = selectedOs.lowercase()
    return when {
        s == "ios" || s == "iphone" ->
            t.contains("ios") || t.contains("iphone") || t.contains("ipad")
        s == "macos" || s == "mac" ->
            t.contains("mac") && !t.contains("ios") && !t.contains("iphone")
        s == "android" -> t.contains("android")
        s == "windows" -> t.contains("win")
        s == "linux" -> t.contains("linux") || t.contains("ubuntu") || t.contains("fedora")
        else -> t.contains(s)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDropdown(
    catalog: List<FingerprintTemplate>,
    selectedOs: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(catalog, selectedOs) {
        val fromCatalog = if (catalog.isNotEmpty()) catalog else emptyList()
        val base = if (fromCatalog.isNotEmpty()) {
            fromCatalog.filter { osMatches(it.os, selectedOs) }
        } else {
            FingerprintTemplates.templates
                .filter { osMatches(it.os, selectedOs) }
                .map {
                    // Minimal stand-in so dropdown still works offline
                    FingerprintTemplate(name = it.name, os = it.os)
                }
        }
        base.ifEmpty {
            // Fallback: show all if filter empty (should not happen with full library)
            if (fromCatalog.isNotEmpty()) fromCatalog else emptyList()
        }
    }
    val names = filtered.map { it.name }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "Select fingerprint ($selectedOs)" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Fingerprint · $selectedOs (${names.size})") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = textFieldColors()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (names.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No templates for $selectedOs") },
                    onClick = { expanded = false }
                )
            } else {
                names.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun IdentityTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    OutlinedTextField(
        value = profile.userAgent,
        onValueChange = { v -> vm.update { it.copy(userAgent = v) } },
        label = { Text("User-Agent") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = textFieldColors()
    )
    OutlinedTextField(
        value = profile.platform,
        onValueChange = { v -> vm.update { it.copy(platform = v) } },
        label = { Text("navigator.platform") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = textFieldColors()
    )
    OutlinedTextField(
        value = profile.language,
        onValueChange = { v -> vm.update { it.copy(language = v) } },
        label = { Text("Language (e.g. es-MX, en-US)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = textFieldColors()
    )
}

@Composable
fun HardwareTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = profile.screenWidth.toString(),
            onValueChange = { v -> vm.update { it.copy(screenWidth = v.toIntOrNull() ?: it.screenWidth) } },
            label = { Text("Screen Width") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = textFieldColors()
        )
        OutlinedTextField(
            value = profile.screenHeight.toString(),
            onValueChange = { v -> vm.update { it.copy(screenHeight = v.toIntOrNull() ?: it.screenHeight) } },
            label = { Text("Screen Height") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = textFieldColors()
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = if (profile.hardwareConcurrency > 0) profile.hardwareConcurrency.toString() else "",
            onValueChange = { v ->
                vm.update {
                    it.copy(hardwareConcurrency = v.toIntOrNull()?.coerceIn(1, 128) ?: 0)
                }
            },
            label = { Text("CPU Cores (hardwareConcurrency)") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = textFieldColors(),
            placeholder = { Text("ej. 8, 12, 16") }
        )
        OutlinedTextField(
            value = if (profile.deviceMemory > 0) profile.deviceMemory.toString() else "",
            onValueChange = { v ->
                vm.update {
                    it.copy(deviceMemory = v.toIntOrNull()?.coerceIn(1, 256) ?: 0)
                }
            },
            label = { Text("RAM GB (deviceMemory)") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = textFieldColors(),
            placeholder = { Text("ej. 8, 16, 32") }
        )
    }
    OutlinedTextField(
        value = profile.webglVendor,
        onValueChange = { v -> vm.update { it.copy(webglVendor = v) } },
        label = { Text("WebGL UNMASKED_VENDOR") },
        modifier = Modifier.fillMaxWidth(),
        colors = textFieldColors()
    )
    OutlinedTextField(
        value = profile.webglRenderer,
        onValueChange = { v -> vm.update { it.copy(webglRenderer = v) } },
        label = { Text("WebGL UNMASKED_RENDERER") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        colors = textFieldColors()
    )
}

@Composable
fun NoiseSelector(label: String, value: String, onChange: (String) -> Unit) {
    val options = listOf("Real", "AutoNoise", "Disabled")
    Column {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                FilterChip(
                    selected = value == opt,
                    onClick = { onChange(opt) },
                    label = { Text(opt, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPurple,
                        selectedLabelColor = TextPrimary
                    )
                )
            }
        }
    }
}

@Composable
fun FingerprintTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    NoiseSelector("Canvas", profile.canvasNoise) { v -> vm.update { it.copy(canvasNoise = v) } }
    NoiseSelector("WebGL", profile.webglNoise) { v -> vm.update { it.copy(webglNoise = v) } }
    NoiseSelector("Audio", profile.audioNoise) { v -> vm.update { it.copy(audioNoise = v) } }
    NoiseSelector("ClientRects", profile.clientRectsNoise) { v -> vm.update { it.copy(clientRectsNoise = v) } }
    NoiseSelector("Quads", profile.quadsNoise) { v -> vm.update { it.copy(quadsNoise = v) } }
    NoiseSelector("Fonts", profile.fontsNoise) { v -> vm.update { it.copy(fontsNoise = v) } }

    Spacer(Modifier.height(8.dp))
    SwitchRow("Block WebRTC", profile.blockWebRTC) { v -> vm.update { it.copy(blockWebRTC = v) } }
    SwitchRow("Block 3rd-party cookies", profile.blockThirdPartyCookies) { v -> vm.update { it.copy(blockThirdPartyCookies = v) } }
    SwitchRow(
        "Borrar datos al salir (OFF = guardar sesión)",
        profile.autoCleanOnExit
    ) { v -> vm.update { it.copy(autoCleanOnExit = v) } }
    SwitchRow("JavaScript enabled", profile.javascriptEnabled) { v -> vm.update { it.copy(javascriptEnabled = v) } }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AccentPurple)
        )
    }
}

@Composable
fun NetworkTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    val timezones = listOf(
        "America/Mexico_City", "America/New_York", "America/Los_Angeles",
        "Europe/London", "Europe/Madrid", "Europe/Berlin",
        "Asia/Tokyo", "Asia/Shanghai", "UTC"
    )
    var tzExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = tzExpanded, onExpandedChange = { tzExpanded = it }) {
        OutlinedTextField(
            value = profile.timezone,
            onValueChange = {},
            readOnly = true,
            label = { Text("IANA Timezone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tzExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = textFieldColors()
        )
        ExposedDropdownMenu(expanded = tzExpanded, onDismissRequest = { tzExpanded = false }) {
            timezones.forEach { tz ->
                DropdownMenuItem(
                    text = { Text(tz) },
                    onClick = {
                        vm.update { it.copy(timezone = tz) }
                        tzExpanded = false
                    }
                )
            }
        }
    }

    Text("Geolocation", color = TextSecondary, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Block", "AutoIP", "Manual").forEach { mode ->
            FilterChip(
                selected = profile.geoMode == mode,
                onClick = { vm.update { it.copy(geoMode = mode) } },
                label = { Text(mode) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPurple,
                    selectedLabelColor = TextPrimary
                )
            )
        }
    }
    if (profile.geoMode == "Manual") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = profile.geoLatitude.toString(),
                onValueChange = { v -> vm.update { it.copy(geoLatitude = v.toDoubleOrNull() ?: 0.0) } },
                label = { Text("Latitude") },
                modifier = Modifier.weight(1f),
                colors = textFieldColors()
            )
            OutlinedTextField(
                value = profile.geoLongitude.toString(),
                onValueChange = { v -> vm.update { it.copy(geoLongitude = v.toDoubleOrNull() ?: 0.0) } },
                label = { Text("Longitude") },
                modifier = Modifier.weight(1f),
                colors = textFieldColors()
            )
        }
    }
}

@Composable
fun CounterRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (value > 0) onChange(value - 1) }) {
                Text("−", color = TextPrimary, fontSize = 20.sp)
            }
            Text("$value", color = TextPrimary, modifier = Modifier.width(32.dp))
            IconButton(onClick = { onChange(value + 1) }) {
                Text("+", color = TextPrimary, fontSize = 20.sp)
            }
        }
    }
}

@Composable
fun DevicesTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    CounterRow("Microphones", profile.microphones) { v -> vm.update { it.copy(microphones = v) } }
    CounterRow("Speakers", profile.speakers) { v -> vm.update { it.copy(speakers = v) } }
    CounterRow("Webcams", profile.webcams) { v -> vm.update { it.copy(webcams = v) } }
    OutlinedTextField(
        value = profile.portsToBlock,
        onValueChange = { v -> vm.update { it.copy(portsToBlock = v) } },
        label = { Text("Ports to block (comma separated)") },
        modifier = Modifier.fillMaxWidth(),
        colors = textFieldColors()
    )
}

@Composable
fun ProxyTab(profile: ProfileEntity, vm: NewProfileViewModel) {
    Text("Proxy Type", color = TextSecondary, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("None", "HTTP", "SOCKS5").forEach { type ->
            FilterChip(
                selected = profile.proxyType == type,
                onClick = { vm.update { it.copy(proxyType = type) } },
                label = { Text(type) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentPurple,
                    selectedLabelColor = TextPrimary
                )
            )
        }
    }
    if (profile.proxyType != "None") {
        SwitchRow("Activar proxy al abrir sesión", profile.proxyEnabled) { v ->
            vm.update { it.copy(proxyEnabled = v) }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = profile.proxyHost,
            onValueChange = { v -> vm.update { it.copy(proxyHost = v) } },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )
        OutlinedTextField(
            value = if (profile.proxyPort == 0) "" else profile.proxyPort.toString(),
            onValueChange = { v -> vm.update { it.copy(proxyPort = v.toIntOrNull() ?: 0) } },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )
        OutlinedTextField(
            value = profile.proxyUsername,
            onValueChange = { v -> vm.update { it.copy(proxyUsername = v) } },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )
        OutlinedTextField(
            value = profile.proxyPassword,
            onValueChange = { v -> vm.update { it.copy(proxyPassword = v) } },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = textFieldColors()
        )
        if (profile.proxyType == "SOCKS5") {
            Text(
                "DNS will be resolved remotely (socks_remote_dns) to prevent local DNS leaks.",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

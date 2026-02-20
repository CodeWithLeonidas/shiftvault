package com.leonidaskiachidis.shiftvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.leonidaskiachidis.shiftvault.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBackIosNew,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Appearance Section
            SettingsGroup(title = "Appearance") {
                SettingsSwitchItem(
                    title = "Dark Mode",
                    subtitle = "Use the dark theme",
                    icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                    checked = isDarkTheme,
                    onCheckedChange = onThemeChange
                )
            }

            // Data Management Section
            SettingsGroup(title = "Data Management") {
                SettingsItem(
                    title = "Export to PDF",
                    icon = Icons.Default.PictureAsPdf,
                    isComingSoon = true,
                    onClick = {}
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                SettingsItem(
                    title = "Export to Excel",
                    icon = Icons.Default.TableChart,
                    isComingSoon = true,
                    onClick = {}
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                SettingsItem(
                    title = "Backup to Save File",
                    icon = Icons.Default.Save,
                    isComingSoon = true,
                    onClick = {}
                )
            }

            // About Application Section
            SettingsGroup(title = "About") {
                SettingsItem(
                    title = "Application Info",
                    subtitle = "Version 1.0.0",
                    icon = Icons.Default.Info,
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer
            Text(
                text = "Made with ❤️ for Shift Vault",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            // Add a border if simple surface color isn't distinct enough in light mode
            border = null
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    isComingSoon: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon = icon)
        Spacer(modifier = Modifier.width(16.dp))
        TextContent(title, subtitle, Modifier.weight(1f))

        if (isComingSoon) {
            BadgeComingSoon()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) } // Clicking row toggles switch
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon = icon)
        Spacer(modifier = Modifier.width(16.dp))
        TextContent(title, subtitle, Modifier.weight(1f))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        )
    }
}

@Composable
fun IconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun TextContent(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun BadgeComingSoon() {
    SuggestionChip(
        onClick = { },
        label = { Text("Soon", style = MaterialTheme.typography.labelSmall) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        border = null,
        modifier = Modifier.height(26.dp)
    )
}

@Preview(showBackground = true, name = "Dark Mode")
@Composable
fun SettingsScreenPreviewDark() {
    ShiftVaultTheme(darkTheme = true) {
        SettingsScreen(isDarkTheme = true, onThemeChange = {}, onBackClick = {})
    }
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun SettingsScreenPreviewLight() {
    ShiftVaultTheme(darkTheme = false) {
        SettingsScreen(isDarkTheme = false, onThemeChange = {}, onBackClick = {})
    }
}

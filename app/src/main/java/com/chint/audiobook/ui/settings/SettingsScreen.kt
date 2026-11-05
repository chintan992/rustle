package com.chint.audiobook.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chint.audiobook.R
import com.chint.audiobook.data.model.AppSettings
import com.chint.audiobook.data.model.ThemeMode
import com.chint.audiobook.data.model.TtsEngine
import com.chint.audiobook.notifications.AnnouncementNotifications
import com.chint.audiobook.ui.reader.DisplaySheet
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PRIVACY_POLICY_URL =
  "https://chintan992.github.io/rustle/privacy-policy/"

@Composable
fun SettingsScreen(
  settings: AppSettings,
  onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
  onOpenVoiceSetup: () -> Unit,
  onOpenLicenses: () -> Unit = {},
  onCheckUpdates: () -> Unit = {},
  onBackClick: () -> Unit
) {
  // UI Round 4 (R5): the same DisplaySheet the reader's "Aa" chip opens, hosted
  // here so transcript typography can be adjusted without leaving Settings.
  var showDisplaySheet by rememberSaveable { mutableStateOf(false) }
  // Multi-voice: dialogue-narrator picker dialog.
  var showDialoguePicker by rememberSaveable { mutableStateOf(false) }
  val selectedDialogueVoice = if (settings.ttsEngine == TtsEngine.KOKORO) {
    settings.kokoroDialogueVoice
  } else {
    settings.dialogueVoice
  }
  val context = LocalContext.current
  var announcementError by remember { mutableStateOf<String?>(null) }
  var announcementUpdateInProgress by remember { mutableStateOf(false) }

  fun updateAnnouncementSubscription(enabled: Boolean) {
    announcementError = null
    announcementUpdateInProgress = true
    if (!enabled) {
      onUpdateSettings { it.copy(announcementNotificationsEnabled = false) }
    }
    val task = if (enabled) {
      AnnouncementNotifications.subscribe()
    } else {
      AnnouncementNotifications.unsubscribe()
    }
    task.addOnCompleteListener { result ->
      announcementUpdateInProgress = false
      if (result.isSuccessful) {
        onUpdateSettings { it.copy(announcementNotificationsEnabled = enabled) }
      } else {
        if (enabled) {
          onUpdateSettings { it.copy(announcementNotificationsEnabled = false) }
        }
        announcementError = context.getString(R.string.settings_announcements_subscription_failed)
      }
    }
  }

  val announcementPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      updateAnnouncementSubscription(true)
    } else {
      announcementError = context.getString(R.string.settings_announcements_permission_denied)
    }
  }

  fun requestAnnouncementOptIn() {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      announcementPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      updateAnnouncementSubscription(true)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    // Standard header: back arrow top-left, title following it.
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onBackClick,
        modifier = Modifier.semantics { contentDescription = "Back" }
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(modifier = Modifier.height(20.dp))

    SectionCard {
      SwitchSettingRow(
        title = stringResource(R.string.settings_announcements_title),
        supporting = stringResource(R.string.settings_announcements_desc),
        checked = settings.announcementNotificationsEnabled,
        enabled = !announcementUpdateInProgress,
        onCheckedChange = { enabled ->
          announcementError = null
          if (enabled) requestAnnouncementOptIn() else updateAnnouncementSubscription(false)
        }
      )
      announcementError?.let { error ->
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SectionCard {
      Text("Speech engine", style = MaterialTheme.typography.titleMedium)
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TtsEngine.entries.forEachIndexed { index, engine ->
          SegmentedButton(
            selected = settings.ttsEngine == engine,
            onClick = { onUpdateSettings { it.copy(ttsEngine = engine) } },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = TtsEngine.entries.size)
          ) {
            Text(if (engine == TtsEngine.SUPERTONIC) "Supertonic" else "Kokoro")
          }
        }
      }
      Text(
        if (settings.ttsEngine == TtsEngine.KOKORO) {
          "Kokoro is English-focused. Other languages continue to use Supertonic. " +
            "Download its model in Model Manager before playback."
        } else {
          "Supertonic remains the default and supports the full language catalog."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      SettingRow(
        title = stringResource(R.string.settings_default_narrator),
        supporting = stringResource(
          R.string.settings_current_voice,
          com.chint.audiobook.ui.onboarding.VoiceCatalog.displayName(
            if (settings.ttsEngine == TtsEngine.KOKORO) settings.kokoroVoice else settings.defaultVoice
          )
        ),
        control = {
          OutlinedButton(onClick = onOpenVoiceSetup) {
            Text(stringResource(R.string.common_change))
          }
        }
      )
      SwitchSettingRow(
        title = stringResource(R.string.settings_highlight_title),
        supporting = stringResource(R.string.settings_highlight_desc),
        checked = settings.highlightEnabled,
        onCheckedChange = { enabled: Boolean ->
          onUpdateSettings { it.copy(highlightEnabled = enabled) }
        },
        modifier = Modifier.semantics { contentDescription = "Sentence Karaoke Highlight" }
      )
      SwitchSettingRow(
        title = stringResource(R.string.settings_wifi_only_title),
        supporting = stringResource(R.string.settings_wifi_only_desc),
        checked = settings.wifiOnlyDownloads,
        onCheckedChange = { wifiOnly: Boolean ->
          onUpdateSettings { it.copy(wifiOnlyDownloads = wifiOnly) }
        },
        modifier = Modifier.semantics { contentDescription = "Wi-Fi Only Downloads" }
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    SectionCard {
      Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.settings_theme_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
          SegmentedButton(
            selected = settings.themeMode == mode,
            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
          ) {
            Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() })
          }
        }
      }
      // UI Round 4 (R5): reader transcript typography — opens the shared
      // DisplaySheet with the summary of the current preferences.
      SettingRow(
        title = stringResource(R.string.settings_reader_text),
        supporting = "${settings.readerFontSizeSp} sp · ${familyLabel(settings.readerFontFamily)} · ${spacingLabel(settings.readerLineSpacing)}",
        control = {
          OutlinedButton(onClick = { showDisplaySheet = true }) {
            Text(stringResource(R.string.common_adjust))
          }
        }
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // WS2: forward-looking listening stats from the playback heartbeat.
    val context = androidx.compose.ui.platform.LocalContext.current
    val stats = remember(context) {
      com.chint.audiobook.engine.ListeningStatsRepository(java.io.File(context.filesDir, "stats"))
    }
    var statsSummary by remember(context) {
      mutableStateOf(stats.load())
    }
    var todayKey by remember(context) {
      mutableStateOf(com.chint.audiobook.engine.ListeningStatsRepository.dayKeyOf(System.currentTimeMillis()))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(stats, lifecycleOwner) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          val now = System.currentTimeMillis()
          statsSummary = withContext(Dispatchers.IO) { stats.load() }
          todayKey = com.chint.audiobook.engine.ListeningStatsRepository.dayKeyOf(now)
          delay(10_000L)
        }
      }
    }
    // Q5: yearly reading goal.
    var showGoalPicker by rememberSaveable { mutableStateOf(false) }
    val yearKey = todayKey.substringBefore("-")
    val yearSeconds = com.chint.audiobook.engine.ListeningStatsRepository.yearSeconds(statsSummary.perDaySeconds, yearKey)
    val goalHours = settings.yearlyGoalHours
    SectionCard {
      Text(stringResource(R.string.settings_listening), style = MaterialTheme.typography.titleMedium)
      Text(
        "This week ${com.chint.audiobook.engine.ListeningStatsRepository.formatDuration(
          com.chint.audiobook.engine.ListeningStatsRepository.weekSeconds(statsSummary.perDaySeconds, todayKey)
        )} · ${com.chint.audiobook.engine.ListeningStatsRepository.streakDays(statsSummary.perDaySeconds, todayKey)}-day streak",
        style = MaterialTheme.typography.bodyMedium
      )
      // Q5: goal progress (Apple-Books-style yearly goal, the most-cited
      // "wish Audible had this").
      if (goalHours > 0) {
        val goalSeconds = goalHours * 3_600L
        val percent = ((yearSeconds * 100) / goalSeconds).coerceAtMost(100)
        Text(
          "Yearly goal: ${com.chint.audiobook.engine.ListeningStatsRepository.formatDuration(yearSeconds)} of ${goalHours}h ($percent%)",
          style = MaterialTheme.typography.bodyMedium
        )
      }
      Text(
        "Counted while the narrator plays; starts from this app version",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      OutlinedButton(onClick = { showGoalPicker = true }) {
        Text(if (goalHours > 0) "Change yearly goal" else "Set a yearly goal")
      }
    }
    if (showGoalPicker) {
      androidx.compose.material3.AlertDialog(
        onDismissRequest = { showGoalPicker = false },
        title = { Text("Yearly listening goal") },
        text = {
          Column {
            listOf(0, 12, 25, 50, 100, 200, 500).forEach { hours ->
              TextButton(
                onClick = {
                  onUpdateSettings { it.copy(yearlyGoalHours = hours) }
                  showGoalPicker = false
                }
              ) {
                Text(if (hours == 0) "No goal" else "$hours hours" + if (settings.yearlyGoalHours == hours) "  ✓" else "")
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showGoalPicker = false }) { Text("Done") }
        }
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Multi-voice round: separate narrator for quoted dialogue.
    SectionCard {
      Text(stringResource(R.string.settings_multi_voice), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.settings_multi_voice_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      SwitchSettingRow(
        title = stringResource(R.string.settings_separate_dialogue_voice),
        supporting = stringResource(R.string.settings_separate_dialogue_desc),
        checked = if (settings.ttsEngine == TtsEngine.KOKORO) {
          settings.kokoroDialogueVoice != null
        } else {
          settings.dialogueVoice != null
        },
        onCheckedChange = { enabled: Boolean ->
          onUpdateSettings {
            if (settings.ttsEngine == TtsEngine.KOKORO) {
              it.copy(kokoroDialogueVoice = if (enabled) "am_fenrir" else null)
            } else {
              it.copy(dialogueVoice = if (enabled) "M3" else null)
            }
          }
        },
        modifier = Modifier.semantics { contentDescription = "Separate dialogue voice" }
      )
      SwitchSettingRow(
        title = stringResource(R.string.settings_expressive_narration),
        supporting = stringResource(R.string.settings_expressive_desc) +
          if (settings.ttsEngine == TtsEngine.KOKORO) " Kokoro does not support acting tags." else "",
        checked = settings.expressiveNarration,
        onCheckedChange = { enabled: Boolean ->
          onUpdateSettings { it.copy(expressiveNarration = enabled) }
        },
        modifier = Modifier.semantics { contentDescription = "Expressive narration" }
      )
      if (selectedDialogueVoice != null) {
        SettingRow(
          title = stringResource(R.string.settings_dialogue_narrator),
          supporting = stringResource(
            R.string.settings_current_voice,
            com.chint.audiobook.ui.onboarding.VoiceCatalog.displayName(selectedDialogueVoice)
          ),
          control = {
            OutlinedButton(onClick = { showDialoguePicker = true }) {
              Text(stringResource(R.string.common_change))
            }
          }
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Cache/prep round (B2): rendered-audio cap + background preparation.
    SectionCard {
      Text(stringResource(R.string.settings_audio_cache), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.settings_audio_cache_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val caps = listOf(0, 1, 2, 5, 10)
        caps.forEachIndexed { index, gb ->
          SegmentedButton(
            selected = settings.cacheCapGb == gb,
            onClick = { onUpdateSettings { it.copy(cacheCapGb = gb) } },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = caps.size)
          ) {
            Text(if (gb == 0) "Off" else "$gb GB")
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SectionCard {
      Text(stringResource(R.string.settings_prepare_ahead_title), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.settings_prepare_ahead_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(12.dp))
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(0, 1, 2, 5)
        options.forEachIndexed { index, chapters ->
          SegmentedButton(
            selected = settings.prepareAheadChapters == chapters,
            onClick = { onUpdateSettings { it.copy(prepareAheadChapters = chapters) } },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
          ) {
            Text(if (chapters == 0) "Off" else "+$chapters")
          }
        }
      }
      SwitchSettingRow(
        title = stringResource(R.string.settings_prepare_wifi_only),
        supporting = stringResource(R.string.settings_prepare_wifi_only_desc),
        checked = settings.prepareWifiOnly,
        onCheckedChange = { wifiOnly: Boolean ->
          onUpdateSettings { it.copy(prepareWifiOnly = wifiOnly) }
        },
        modifier = Modifier.semantics { contentDescription = "Prepare ahead Wi-Fi only" }
      )
      SwitchSettingRow(
        title = stringResource(R.string.settings_only_charging),
        supporting = stringResource(R.string.settings_only_charging_desc),
        checked = settings.prepareOnlyWhileCharging,
        onCheckedChange = { charging: Boolean ->
          onUpdateSettings { it.copy(prepareOnlyWhileCharging = charging) }
        },
        modifier = Modifier.semantics { contentDescription = "Prepare ahead only while charging" }
      )
      SwitchSettingRow(
        title = stringResource(R.string.settings_battery_saver),
        supporting = stringResource(R.string.settings_battery_saver_desc),
        checked = settings.batterySaverEnabled,
        onCheckedChange = { saver: Boolean ->
          onUpdateSettings { it.copy(batterySaverEnabled = saver) }
        },
        modifier = Modifier.semantics { contentDescription = "Battery saver" }
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Play-release compliance: license bundle + GenAI report mechanism.
    SectionCard {
      SettingRow(
        title = stringResource(R.string.settings_licenses_ai_disclosure),
        supporting = stringResource(R.string.settings_licenses_supporting),
        control = {
          OutlinedButton(onClick = onOpenLicenses) {
            Text(stringResource(R.string.settings_view))
          }
        }
      )
      SettingRow(
        title = stringResource(R.string.settings_privacy_policy),
        supporting = stringResource(R.string.settings_privacy_policy_supporting),
        control = {
          OutlinedButton(
            onClick = {
              context.startActivity(
                android.content.Intent(
                  android.content.Intent.ACTION_VIEW,
                  android.net.Uri.parse(PRIVACY_POLICY_URL)
                )
              )
            }
          ) {
            Text(stringResource(R.string.settings_view))
          }
        }
      )
      SettingRow(
        title = stringResource(R.string.settings_report_issue),
        supporting = stringResource(R.string.settings_report_issue_supporting),
        control = {
          OutlinedButton(onClick = {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
              data = android.net.Uri.parse("mailto:")
              putExtra(
                android.content.Intent.EXTRA_EMAIL,
                arrayOf("rustle@chintan992.dev")
              )
              putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                "Rustle issue report"
              )
              putExtra(
                android.content.Intent.EXTRA_TEXT,
                "What happened:\n\n\nWhich book/chapter (optional):\n"
              )
            }
            context.startActivity(
              android.content.Intent.createChooser(intent, "Report an issue")
            )
          }) {
            Text(stringResource(R.string.settings_email))
          }
        }
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // B4: reviewers look for a version indicator; also the license anchor.
    SectionCard {
      Text(stringResource(R.string.settings_updates_title), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(R.string.settings_updates_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      OutlinedButton(onClick = onCheckUpdates) {
        Text(stringResource(R.string.settings_check_updates))
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SectionCard {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Image(
          painter = painterResource(R.drawable.app_logo_card),
          contentDescription = stringResource(R.string.app_name),
          modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(14.dp))
        )
        Column {
          Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
          Text(
            "Rustle ${com.chint.audiobook.BuildConfig.VERSION_NAME} " +
              "(${com.chint.audiobook.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            stringResource(R.string.settings_about_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
  }

  if (showDialoguePicker) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { showDialoguePicker = false },
      title = { Text("Dialogue narrator") },
      text = {
        Column {
          com.chint.audiobook.ui.onboarding.VoiceCatalog.presetsFor(settings.ttsEngine).forEach { preset ->
            TextButton(
              onClick = {
                onUpdateSettings {
                  if (settings.ttsEngine == TtsEngine.KOKORO) {
                    it.copy(kokoroDialogueVoice = preset.id)
                  } else {
                    it.copy(dialogueVoice = preset.id)
                  }
                }
                showDialoguePicker = false
              }
            ) {
              Text(
                "${preset.name} — ${preset.description}" +
                  if (selectedDialogueVoice == preset.id) "  ✓" else ""
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showDialoguePicker = false }) { Text(stringResource(R.string.common_done)) }
      }
    )
  }

  if (showDisplaySheet) {
    DisplaySheet(
      currentFontSizeSp = settings.readerFontSizeSp,
      currentFontFamily = settings.readerFontFamily,
      currentLineSpacing = settings.readerLineSpacing,
      onFontSizeSelect = { size ->
        onUpdateSettings { it.copy(readerFontSizeSp = size) }
      },
      onFontFamilySelect = { family ->
        onUpdateSettings { it.copy(readerFontFamily = family) }
      },
      onLineSpacingSelect = { spacing ->
        onUpdateSettings { it.copy(readerLineSpacing = spacing) }
      },
      onDismiss = { showDisplaySheet = false }
    )
  }
}

/** UI Round 4 (R5): "Sans"/"Serif" label for the settings summary row. */
private fun familyLabel(family: com.chint.audiobook.data.model.ReaderFontFamily): String =
  family.name.lowercase().replaceFirstChar { c -> c.uppercase() }

/** UI Round 4 (R5): "Compact ×1.35"-style label for the settings summary row. */
private fun spacingLabel(spacing: com.chint.audiobook.data.model.ReaderLineSpacing): String =
  spacing.name.lowercase().replaceFirstChar { c -> c.uppercase() } +
    " ×" + spacing.multiplier.toString().trimEnd('0').trimEnd('.')

/** Grouped settings block using the medium card shape + surface container tone. */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      content = content
    )
  }
}

/** One setting line: title + supporting text on the left, control on the right. */
@Composable
private fun SettingRow(
  title: String,
  supporting: String? = null,
  control: @Composable () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      if (supporting != null) {
        Text(
          supporting,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
    Spacer(modifier = Modifier.width(8.dp))
    control()
  }
}

@Composable
private fun SwitchSettingRow(
  title: String,
  supporting: String? = null,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  SettingRow(title = title, supporting = supporting) {
    Switch(
      checked = checked,
      enabled = enabled,
      onCheckedChange = onCheckedChange,
      modifier = modifier
    )
  }
}

package com.humbleplanning.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humbleplanning.app.R
import com.humbleplanning.app.data.UserDto
import com.humbleplanning.app.ui.Banner
import com.humbleplanning.app.ui.BannerTone
import com.humbleplanning.app.ui.ProgressButton
import com.humbleplanning.app.ui.apiErrorMessage
import com.humbleplanning.app.ui.contentColor
import com.humbleplanning.app.ui.parseColor

private val PROFILE_COLORS = listOf(
    "#5A17FF", "#2563EB", "#0891B2", "#059669", "#65A30D", "#D97706", "#EA580C", "#DC2626", "#DB2777", "#7C3AED", "#475569", "#18181B",
)

/** Profile: name, first name, phones, job, colour. The e-mail address can only be changed by an administrator. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileSheet(vm: SettingsViewModel, user: UserDto, profile: ProfileState, onDismiss: () -> Unit) {
    var name by rememberSaveable(user.id) { mutableStateOf(user.name) }
    var firstName by rememberSaveable(user.id) { mutableStateOf(user.firstName.orEmpty()) }
    var phone by rememberSaveable(user.id) { mutableStateOf(user.phone.orEmpty()) }
    var mobile by rememberSaveable(user.id) { mutableStateOf(user.mobile.orEmpty()) }
    var job by rememberSaveable(user.id) { mutableStateOf(user.job.orEmpty()) }
    var color by rememberSaveable(user.id) { mutableStateOf(user.color) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.titleLarge)
            if (profile.saved) Banner(stringResource(R.string.profile_saved), BannerTone.Info, icon = Icons.Outlined.CheckCircle)
            profile.error?.let { Banner(apiErrorMessage(it), BannerTone.Error) }
            ProfileField(name, { name = it.take(100) }, stringResource(R.string.profile_name) + " *", isError = name.isBlank())
            ProfileField(firstName, { firstName = it.take(100) }, stringResource(R.string.profile_first_name))
            OutlinedTextField(
                user.email.orEmpty(), {}, Modifier.fillMaxWidth(), readOnly = true, singleLine = true,
                label = { Text(stringResource(R.string.profile_email)) },
                supportingText = { Text(stringResource(R.string.profile_email_help)) },
                shape = MaterialTheme.shapes.medium,
            )
            ProfileField(phone, { phone = it.take(20) }, stringResource(R.string.profile_phone), KeyboardOptions(keyboardType = KeyboardType.Phone))
            ProfileField(mobile, { mobile = it.take(20) }, stringResource(R.string.profile_mobile), KeyboardOptions(keyboardType = KeyboardType.Phone))
            ProfileField(job, { job = it.take(100) }, stringResource(R.string.profile_job))
            Text(stringResource(R.string.profile_color), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PROFILE_COLORS.forEach { hex ->
                    val swatch = parseColor(hex) ?: return@forEach
                    val chosen = hex.equals(color, ignoreCase = true)
                    val description = stringResource(R.string.profile_color_item, hex)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.RadioButton) { color = hex }
                            .semantics {
                                contentDescription = description
                                selected = chosen
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(swatch)
                                .border(if (chosen) 2.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (chosen) 1f else 0.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = swatch.contentColor())
                        }
                    }
                }
            }
            ProgressButton(
                stringResource(R.string.save),
                profile.saving,
                { vm.saveProfile(name, firstName, phone, mobile, job, color) },
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                enabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ProfileField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardOptions = KeyboardOptions.Default, isError: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = isError, keyboardOptions = keyboard, shape = MaterialTheme.shapes.medium)
}

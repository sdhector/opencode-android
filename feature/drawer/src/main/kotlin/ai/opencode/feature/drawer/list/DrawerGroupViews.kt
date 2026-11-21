package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.serverGroups
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Local Session Groups' list rows and dialogs. A group is a phone-side bundle: every action here
 * lands in Home as a plain group action and none reaches the server.
 */

/** What the group dialogs are asking about; the drawer shows one at a time. */
@Stable
class DrawerGroupEditor {
    sealed interface Request {
        data class Create(val sessionIDs: List<String> = emptyList()) : Request
        data class Rename(val groupID: String, val name: String) : Request
        data class Remove(val groupID: String, val name: String) : Request
        data class Move(val sessionIDs: List<String>) : Request
    }

    var request: Request? by mutableStateOf(null)
}

/** What a group header does, as closures: the row never holds a store. */
class DrawerGroupHeaderActions(
    val toggle: () -> Unit,
    val newSession: () -> Unit,
    val rename: () -> Unit,
    val remove: () -> Unit,
)

/** A group's header: its name and size, the chevron that opens or closes it, + for a new session in it, and ⋮ for Rename and Remove. */
@Composable
fun DrawerListGroupHeaderCell(
    header: DrawerListGroupHeader,
    actions: DrawerGroupHeaderActions,
    modifier: Modifier = Modifier,
    isDropTarget: Boolean = false,
) {
    val colors = OpenCodeTheme.colors
    val count = pluralStringResource(R.plurals.drawer_group_session_count, header.count, header.count)
    val state = stringResource(if (header.isCollapsed) R.string.drawer_group_collapsed_state else R.string.drawer_group_expanded_state)
    var menuOpen by remember { mutableStateOf(false) }
    val newSessionLabel = stringResource(R.string.drawer_group_new_session, header.name)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .heightIn(min = DrawerMetrics.rowHeight)
            .then(if (isDropTarget) Modifier.background(colors.signal.copy(alpha = 0.16f), RoundedCornerShape(DrawerMetrics.rowCornerRadius)) else Modifier)
            .testTag("drawer-group-${header.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = DrawerMetrics.rowHeight)
                .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
                .clickable(role = Role.Button, onClick = actions.toggle)
                .padding(start = DrawerMetrics.textInset)
                .clearAndSetSemantics {
                    contentDescription = "${header.name}, $count"
                    stateDescription = state
                    role = Role.Button
                    heading()
                    onClick { actions.toggle(); true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeIcon(
                if (header.isCollapsed) SFSymbol.ChevronRight else SFSymbol.ChevronDown,
                contentDescription = null,
                size = 14.dp,
                tint = colors.secondaryText,
            )
            Text(
                header.name,
                style = DrawerTunables.rowFont.font.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "${header.count}",
                style = DrawerTunables.rowFont.font.copy(fontFeatureSettings = "tnum"),
                color = colors.secondaryText,
            )
        }
        OpenCodeIconButton(
            SFSymbol.Plus,
            contentDescription = newSessionLabel,
            onClick = actions.newSession,
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.testTag("drawer-group-new-session-${header.id}"),
        )
        Box {
            OpenCodeIconButton(
                SFSymbol.Ellipsis,
                contentDescription = stringResource(R.string.drawer_selection_more_options_description),
                onClick = { menuOpen = true },
                style = OpenCodeIconButtonStyle.Standard,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_group_rename)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                    onClick = { menuOpen = false; actions.rename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_group_remove), color = colors.destructive) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null, tint = colors.destructive) },
                    onClick = { menuOpen = false; actions.remove() },
                )
            }
        }
    }
}

/** The row that ends the group section: New Group. */
@Composable
fun DrawerListNewGroupRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val label = stringResource(R.string.drawer_group_new)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .heightIn(min = DrawerMetrics.rowHeight)
            .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset)
            .testTag("drawer-new-group")
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeIcon(SFSymbol.FolderBadgePlus, contentDescription = null, tint = colors.secondaryText)
        Text(label, style = DrawerTunables.rowFont.font, color = colors.secondaryText)
    }
}

/** Shows the dialog [editor] is asking for: name a group, confirm its removal, or choose a group for sessions. */
@Composable
internal fun DrawerGroupDialogs(store: StoreOf<HomeFeature.State, HomeFeature.Action>, editor: DrawerGroupEditor) {
    val request = editor.request ?: return
    val dismiss = { editor.request = null }
    when (request) {
        is DrawerGroupEditor.Request.Create -> GroupNameDialog(
            title = stringResource(R.string.drawer_group_new),
            initialName = "",
            confirm = stringResource(CoreR.string.common_add),
            onDismiss = dismiss,
            onConfirm = { name ->
                store.send(HomeFeature.Action.CreateGroup(name, request.sessionIDs))
                dismiss()
            },
        )
        is DrawerGroupEditor.Request.Rename -> GroupNameDialog(
            title = stringResource(R.string.drawer_group_rename),
            initialName = request.name,
            confirm = stringResource(CoreR.string.common_save),
            onDismiss = dismiss,
            onConfirm = { name ->
                store.send(HomeFeature.Action.RenameGroup(request.groupID, name))
                dismiss()
            },
        )
        is DrawerGroupEditor.Request.Remove -> OpenCodeAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.drawer_group_remove_title, request.name)) },
            text = { Text(stringResource(R.string.drawer_group_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    store.send(HomeFeature.Action.RemoveGroup(request.groupID))
                    dismiss()
                }) {
                    Text(stringResource(CoreR.string.common_remove), color = OpenCodeTheme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
            modifier = Modifier.testTag("drawer-group-remove-confirmation"),
        )
        is DrawerGroupEditor.Request.Move -> GroupPickerDialog(store, request.sessionIDs, editor, onDismiss = dismiss)
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initialName: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val valid = SessionGroups.normalizedName(name) != null
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OpenCodeTextField(
                value = name,
                onValueChange = { name = it.take(SessionGroups.MAX_NAME_LENGTH) },
                placeholder = stringResource(R.string.drawer_group_name_placeholder),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("drawer-group-name"),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

@Composable
private fun GroupPickerDialog(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    sessionIDs: List<String>,
    editor: DrawerGroupEditor,
    onDismiss: () -> Unit,
) {
    val groups by store.observe { it.serverGroups }
    val colors = OpenCodeTheme.colors
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_group_move)) },
        text = {
            Column(Modifier.testTag("drawer-group-picker")) {
                for (group in groups) {
                    PickerRow(group.name, SFSymbol.Folder, colors.text) {
                        store.send(HomeFeature.Action.MoveSessionsToGroup(sessionIDs, group.id))
                        onDismiss()
                    }
                }
                PickerRow(stringResource(R.string.drawer_group_new), SFSymbol.FolderBadgePlus, colors.secondaryText) {
                    editor.request = DrawerGroupEditor.Request.Create(sessionIDs)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

@Composable
private fun PickerRow(title: String, symbol: SFSymbol, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, tint = tint)
        Text(title, style = DrawerTunables.rowFont.font, color = tint, maxLines = 1)
    }
}

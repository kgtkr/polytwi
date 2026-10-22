package net.kgtkr.polytwi.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import net.kgtkr.polytwi.model.Account

@Composable
fun AccountTabBar(
    accounts: List<Account>,
    activeAccountId: String?,
    unreadAccountIds: Set<String> = emptySet(),
    onSelectAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRenameAccount: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onCopyUrl: () -> Unit,
    onNavigateToUrl: () -> Unit,
    onReload: () -> Unit,
    onOpenCpftSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                accounts.forEach { account ->
                    var menuExpanded by remember { mutableStateOf(false) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = account.id == activeAccountId,
                            onClick = { onSelectAccount(account.id) },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(account.name)
                                    if (account.id in unreadAccountIds) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(Color(0xFFE0245E), CircleShape)
                                        )
                                    }
                                }
                            }
                        )

                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options for ${account.name}"
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuExpanded = false
                                    onRenameAccount(account.id)
                                }
                            )
                            if (accounts.size > 1) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuExpanded = false
                                        onDeleteAccount(account.id)
                                    }
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onAddAccount) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Account"
                    )
                }
            }

            var overflowMenuExpanded by remember { mutableStateOf(false) }

            Box {
                IconButton(onClick = { overflowMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu"
                    )
                }

                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = { overflowMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy Current URL") },
                        onClick = {
                            overflowMenuExpanded = false
                            onCopyUrl()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Go to URL") },
                        onClick = {
                            overflowMenuExpanded = false
                            onNavigateToUrl()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        onClick = {
                            overflowMenuExpanded = false
                            onReload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Control Panel for Twitter Settings") },
                        onClick = {
                            overflowMenuExpanded = false
                            onOpenCpftSettings()
                        }
                    )
                }
            }
        }
    }
}

package com.nebulaforge.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.R
import com.nebulaforge.core.theme.NebulaBackgroundBottom
import com.nebulaforge.core.theme.NebulaBackgroundTop
import com.nebulaforge.core.theme.NebulaSurfaceVariant
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorHomeScreen(
    onWorkspace: () -> Unit = {},
    onProblems: () -> Unit = {},
    onSettings: () -> Unit = {},
    onMcp: () -> Unit = {},
    onPlugins: () -> Unit = {}
) {
    Scaffold(topBar={TopAppBar(title={Text("NebulaForge IDE")},actions={
        IconButton(onClick=onSettings){Icon(Icons.Outlined.SettingsSuggest, stringResource(R.string.action_settings))}
    })}) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(NebulaBackgroundTop,NebulaBackgroundBottom)))) {
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)) {
                item { HeroTitle() }
                item { Spacer(Modifier.height(28.dp)); SectionLabel("工作区"); Spacer(Modifier.height(12.dp)) }
                item { QuickActionRow(Icons.Outlined.CreateNewFolder,"项目工作区","创建模板、导入目录、浏览项目文件",onWorkspace); Spacer(Modifier.height(10.dp)) }
                item { QuickActionRow(Icons.AutoMirrored.Outlined.List,"问题","查看构建与语言诊断产生的全部错误与警告",onProblems); Spacer(Modifier.height(10.dp)) }
                item { QuickActionRow(Icons.Outlined.FolderOpen,"项目文件","打开工作区中的源码文件并编辑保存",onWorkspace); Spacer(Modifier.height(10.dp)) }
                item { QuickActionRow(Icons.Default.Extension,stringResource(R.string.home_action_plugins_title),stringResource(R.string.home_action_plugins_subtitle),onPlugins); Spacer(Modifier.height(10.dp)) }
                item { QuickActionRow(Icons.Default.Extension,stringResource(R.string.home_action_mcp_title),stringResource(R.string.home_action_mcp_subtitle),onMcp) }
            }
        }
    }
}
@Composable private fun HeroTitle() {
    Column {
        Text(stringResource(R.string.home_title_line1),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_title_line2),style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp)); Text(stringResource(R.string.app_full_name_en),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun SectionLabel(text:String){Text(text,style=MaterialTheme.typography.titleLarge)}
@Composable private fun QuickActionRow(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha=.75f),MaterialTheme.shapes.large).clickable(onClick=onClick).padding(16.dp)) {
        Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Icon(Icons.Default.ChevronRight,null)
    }
}

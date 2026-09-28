package com.nebulaforge.app.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulaforge.app.R
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.ProjectTemplate
import com.nebulaforge.core.projectmodel.ProjectTemplateGenerator
import com.nebulaforge.core.projectmodel.TemplateCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 新建项目向导（开发方案 5.3）。
 *
 * 严格遵循全局约定：**全屏路由页 + 顶部步骤指示器，不使用弹窗**。
 * 三个步骤：
 *   1. 选择模板（按「移动端 / 后端服务 / 前端应用 / 原生与系统 / 扩展模块」分类分组，支持关键字搜索）
 *   2. 填写信息（项目名称、包名/命名空间、存放位置）
 *   3. 确认创建（汇总信息 → 真实落盘）
 *
 * 项目统一创建在 [Environment.projectsDir]（公共存储 `/storage/emulated/0/NebulaForgeProjects`，
 * 不可用时自动回退到应用私有目录），落盘后通过 [onCreated] 通知调用方刷新项目列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectWizardScreen(
    onBack: () -> Unit,
    onCreated: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ProjectTemplate?>(null) }
    var keyword by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<TemplateCategory?>(null) }
    var projectName by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf(DEFAULT_PACKAGE) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val projectsRoot = remember(context) { Environment.projectsDir(context) }
    val needsPackage = selected?.let(::templateNeedsPackage) ?: false

    // 系统返回键：第 2/3 步退回上一步，第 1 步退出向导。
    BackHandler(enabled = !creating) {
        if (step > 0) {
            error = null
            step--
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.new_project_title),
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (step > 0) { error = null; step-- } else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.new_project_prev))
                    }
                }
            )
        },
        bottomBar = {
            WizardBottomBar(
                step = step,
                creating = creating,
                canCreate = selected != null,
                onPrev = { error = null; step-- },
                onNext = { error = null; step++ },
                onCreate = {
                    val tpl = selected
                    if (tpl == null) {
                        error = context.getString(R.string.new_project_pick_template)
                        return@WizardBottomBar
                    }
                    val name = projectName.trim()
                    if (!isValidProjectName(name)) {
                        error = context.getString(R.string.new_project_invalid_name)
                        return@WizardBottomBar
                    }
                    if (needsPackage && !isValidPackage(packageName.trim())) {
                        error = context.getString(R.string.new_project_invalid_package)
                        return@WizardBottomBar
                    }
                    val target = File(projectsRoot, name)
                    if (target.exists() && !target.listFiles().isNullOrEmpty()) {
                        error = context.getString(R.string.new_project_target_exists, target.absolutePath)
                        return@WizardBottomBar
                    }
                    creating = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ProjectTemplateGenerator.create(tpl.id, target, packageName.trim()) }
                        }
                        creating = false
                        result
                            .onSuccess { onCreated(it) }
                            .onFailure { error = context.getString(R.string.new_project_failed, it.message ?: it.toString()) }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StepIndicator(current = step)
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            when (step) {
                0 -> TemplatePickerStep(
                    keyword = keyword,
                    onKeyword = { keyword = it },
                    categoryFilter = categoryFilter,
                    onCategory = { categoryFilter = it },
                    selectedId = selected?.id,
                    onSelect = { tpl ->
                        selected = tpl
                        if (projectName.isBlank()) projectName = defaultProjectName(tpl)
                        if (templateNeedsPackage(tpl) && packageName.isBlank()) packageName = DEFAULT_PACKAGE
                    }
                )

                1 -> ProjectInfoStep(
                    projectName = projectName,
                    onProjectName = { projectName = it },
                    packageName = packageName,
                    onPackageName = { packageName = it },
                    showPackage = needsPackage,
                    location = projectsRoot
                )

                else -> ConfirmStep(
                    template = selected,
                    projectName = projectName.trim(),
                    packageName = packageName.trim(),
                    location = projectsRoot,
                    needsPackage = needsPackage
                )
            }
        }
    }
}

/** 顶部步骤指示器：序号圆形徽标 + 步骤名 + 总进度条。 */
@Composable
private fun StepIndicator(current: Int) {
    val labels = listOf(
        stringResource(R.string.new_project_step1),
        stringResource(R.string.new_project_step2),
        stringResource(R.string.new_project_step3)
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val reached = index <= current
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (reached) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (reached) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (reached) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (current + 1).toFloat() / labels.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp)
        )
    }
}

/** 第 1 步：按分类分组的模板选择。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplatePickerStep(
    keyword: String,
    onKeyword: (String) -> Unit,
    categoryFilter: TemplateCategory?,
    onCategory: (TemplateCategory?) -> Unit,
    selectedId: String?,
    onSelect: (ProjectTemplate) -> Unit
) {
    val sections = remember(keyword, categoryFilter) {
        val matched = ProjectTemplateGenerator.search(keyword)
        val categories = categoryFilter?.let { listOf(it) } ?: ProjectTemplateGenerator.categories()
        categories.mapNotNull { category ->
            matched.filter { it.category == category }
                .takeIf { it.isNotEmpty() }
                ?.let { category to it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeyword,
            singleLine = true,
            label = { Text(stringResource(R.string.new_project_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = categoryFilter == null,
                onClick = { onCategory(null) },
                label = { Text(stringResource(R.string.new_project_cat_all)) }
            )
            ProjectTemplateGenerator.categories().forEach { category ->
                FilterChip(
                    selected = categoryFilter == category,
                    onClick = { onCategory(category) },
                    label = { Text(category.displayName) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.new_project_no_result),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sections.forEach { (category, list) ->
                item(key = "header-${category.id}") {
                    Text(
                        text = "${category.displayName}（${list.size}）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                items(list, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        selected = template.id == selectedId,
                        onClick = { onSelect(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = template.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                if (selected) {
                    Text(
                        text = "✓",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = template.description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniTag(template.displayLanguage)
                MiniTag(template.displayFramework)
                MiniTag(template.typeId)
            }
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 第 2 步：项目名称 / 包名 / 存放位置。 */
@Composable
private fun ProjectInfoStep(
    projectName: String,
    onProjectName: (String) -> Unit,
    packageName: String,
    onPackageName: (String) -> Unit,
    showPackage: Boolean,
    location: String
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = projectName,
            onValueChange = onProjectName,
            singleLine = true,
            label = { Text(stringResource(R.string.new_project_name)) },
            supportingText = { Text(stringResource(R.string.new_project_name_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        if (showPackage) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = packageName,
                onValueChange = onPackageName,
                singleLine = true,
                label = { Text(stringResource(R.string.new_project_package)) },
                supportingText = { Text(stringResource(R.string.new_project_package_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.new_project_location),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = location,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 第 3 步：汇总确认。 */
@Composable
private fun ConfirmStep(
    template: ProjectTemplate?,
    projectName: String,
    packageName: String,
    location: String,
    needsPackage: Boolean
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.new_project_summary),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SummaryRow(stringResource(R.string.new_project_summary_type), template?.name ?: "—")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    stringResource(R.string.new_project_summary_stack),
                    template?.let { "${it.displayLanguage} / ${it.displayFramework}" } ?: "—"
                )
                if (needsPackage) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SummaryRow(stringResource(R.string.new_project_package), packageName)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SummaryRow(stringResource(R.string.new_project_summary_path), File(location, projectName).absolutePath)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(text = value, fontSize = 14.sp)
    }
}

/** 底部操作栏：上一步 / 下一步 / 创建项目。 */
@Composable
private fun WizardBottomBar(
    step: Int,
    creating: Boolean,
    canCreate: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCreate: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step > 0) {
                OutlinedButton(onClick = onPrev, enabled = !creating) {
                    Text(stringResource(R.string.new_project_prev))
                }
            }
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                Button(onClick = onNext, enabled = !creating && (step == 0 && canCreate || step == 1)) {
                    Text(stringResource(R.string.new_project_next))
                }
            } else {
                Button(onClick = onCreate, enabled = !creating) {
                    Text(
                        stringResource(
                            if (creating) R.string.new_project_creating else R.string.new_project_create
                        )
                    )
                }
            }
        }
    }
}

private const val DEFAULT_PACKAGE = "com.example.nebulaforge"

/** 只有需要包结构（Android / Xposed / Java 后端）的模板才展示包名字段。 */
private fun templateNeedsPackage(template: ProjectTemplate): Boolean =
    template.id.startsWith("android") || template.id == "xposed-module" || template.typeId == "java-backend"

private fun isValidProjectName(name: String): Boolean =
    name.isNotEmpty() && name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$"))

private fun isValidPackage(pkg: String): Boolean =
    pkg.matches(Regex("^[a-zA-Z_][A-Za-z0-9_]*(\\.[a-zA-Z_][A-Za-z0-9_]*)+$"))

/** 依据模板给出默认项目名，减少用户输入。 */
private fun defaultProjectName(template: ProjectTemplate): String = when (template.id) {
    "android-empty" -> "android-app"
    "android-empty-compose" -> "compose-app"
    "flutter-empty" -> "flutter-app"
    "cpp-cmake" -> "cpp-app"
    "xposed-module" -> "xposed-module"
    else -> template.id.replace(Regex("[^A-Za-z0-9-]"), "-")
}

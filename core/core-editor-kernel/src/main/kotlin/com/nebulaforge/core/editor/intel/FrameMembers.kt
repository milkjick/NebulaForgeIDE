package com.nebulaforge.core.editor.intel

/** Android 平台常见类型的成员表。 */
object AndroidMembers {

    private fun m(name: String, detail: String, kind: CompletionKind = CompletionKind.METHOD, insert: String = name) =
        ApiEntry(name, insert, detail, kind)

    fun members(typeName: String): List<ApiEntry> = when (TypeMembers.normalize(typeName)) {
        "Context" -> listOf(
            m("getString(", "取字符串资源"),
            m("getColor(", "取颜色资源"),
            m("getDrawable(", "取图片资源"),
            m("getSystemService(", "取系统服务"),
            m("getSharedPreferences(", "取偏好设置"),
            m("getExternalFilesDir(", "取外部私有目录"),
            m("getFilesDir()", "取内部私有目录"),
            m("getCacheDir()", "取缓存目录"),
            m("getDatabasePath(", "取数据库路径"),
            m("startActivity(", "启动界面"),
            m("startService(", "启动服务"),
            m("sendBroadcast(", "发送广播"),
            m("registerReceiver(", "注册广播接收器"),
            m("checkSelfPermission(", "检查权限"),
            m("getPackageManager()", "取包管理器"),
            m("getPackageName()", "取包名"),
            m("getApplicationContext()", "取应用上下文"),
            m("openFileOutput(", "打开输出文件"),
            m("openFileInput(", "打开输入文件"),
            m("deleteFile(", "删除私有文件"),
            m("fileList()", "列出私有文件")
        )
        "Activity", "ComponentActivity", "AppCompatActivity" -> listOf(
            m("setContentView(", "设置界面布局"),
            m("findViewById(", "按 ID 找视图"),
            m("getIntent()", "取启动意图"),
            m("finish()", "结束当前界面"),
            m("onCreate(", "创建回调"),
            m("onResume()", "恢复回调"),
            m("onPause()", "暂停回调"),
            m("onDestroy()", "销毁回调"),
            m("onBackPressed()", "返回键回调"),
            m("requestPermissions(", "申请权限"),
            m("shouldShowRequestPermissionRationale(", "是否需要解释权限"),
            m("getWindow()", "取窗口"),
            m("getSupportFragmentManager()", "取 Fragment 管理器"),
            m("getLifecycle()", "取生命周期"),
            m("getViewModelStore()", "取 ViewModel 容器"),
            m("runOnUiThread(", "切回主线程"),
            m("getSupportActionBar()", "取顶部操作栏")
        )
        "View" -> listOf(
            m("id", "视图 ID", CompletionKind.PROPERTY),
            m("visibility", "可见性", CompletionKind.PROPERTY),
            m("setVisibility(", "设置可见性"),
            m("setOnClickListener(", "设置点击监听"),
            m("setOnLongClickListener(", "设置长按监听"),
            m("setBackgroundColor(", "设置背景色"),
            m("setPadding(", "设置内边距"),
            m("setAlpha(", "设置透明度"),
            m("setEnabled(", "设置可用状态"),
            m("performClick()", "触发点击"),
            m("invalidate()", "请求重绘"),
            m("post(", "投递到 UI 线程"),
            m("setTag(", "设置标记"),
            m("getTag()", "取标记"),
            m("measure(", "测量"),
            m("layout(", "布局"),
            m("getWidth()", "取宽度"),
            m("getHeight()", "取高度"),
            m("animate()", "取动画器")
        )
        "TextView" -> listOf(
            m("setText(", "设置文本"),
            m("getText()", "取文本"),
            m("setTextSize(", "设置字号"),
            m("setTextColor(", "设置文字颜色"),
            m("setTypeface(", "设置字体"),
            m("setGravity(", "设置对齐"),
            m("setMaxLines(", "设置最大行数"),
            m("setSingleLine(", "设置单行"),
            m("setEllipsize(", "设置省略方式"),
            m("append(", "追加文本"),
            m("setHint(", "设置提示")
        )
        "Button" -> listOf(
            m("setText(", "设置按钮文字"),
            m("setOnClickListener(", "设置点击监听"),
            m("setEnabled(", "设置可用状态")
        )
        "Intent" -> listOf(
            m("putExtra(", "放入参数"),
            m("getStringExtra(", "取字符串参数"),
            m("getIntExtra(", "取整数参数"),
            m("getBooleanExtra(", "取布尔参数"),
            m("getSerializableExtra(", "取可序列化参数"),
            m("setAction(", "设置动作"),
            m("setData(", "设置数据 URI"),
            m("setFlags(", "设置标志位"),
            m("addFlags(", "追加标志位"),
            m("getAction()", "取动作"),
            m("getData()", "取数据 URI"),
            m("getComponent()", "取目标组件")
        )
        "Bundle" -> listOf(
            m("putString(", "放入字符串"),
            m("putInt(", "放入整数"),
            m("putBoolean(", "放入布尔"),
            m("putParcelable(", "放入可序列化对象"),
            m("putStringArrayList(", "放入字符串列表"),
            m("getString(", "取字符串"),
            m("getInt(", "取整数"),
            m("getBoolean(", "取布尔"),
            m("getParcelable(", "取可序列化对象"),
            m("getStringArrayList(", "取字符串列表"),
            m("containsKey(", "是否含键"),
            m("keySet()", "取键集合"),
            m("isEmpty()", "是否为空")
        )
        "Fragment" -> listOf(
            m("getView()", "取根视图"),
            m("requireContext()", "取非空上下文"),
            m("requireActivity()", "取非空宿主 Activity"),
            m("getParentFragmentManager()", "取父 Fragment 管理器"),
            m("getLifecycle()", "取生命周期"),
            m("getArguments()", "取参数包"),
            m("setArguments(", "设置参数包"),
            m("onCreateView(", "创建视图回调"),
            m("onViewCreated(", "视图已创建回调"),
            m("onDestroyView()", "视图销毁回调"),
            m("isAdded()", "是否已附加"),
            m("dismiss()", "关闭（对 DialogFragment）")
        )
        "SharedPreferences" -> listOf(
            m("getString(", "取字符串"),
            m("getInt(", "取整数"),
            m("getLong(", "取长整数"),
            m("getBoolean(", "取布尔"),
            m("getFloat(", "取浮点"),
            m("getStringSet(", "取字符串集合"),
            m("getAll()", "取全部键值"),
            m("contains(", "是否含键"),
            m("edit()", "取编辑器"),
            m("registerOnSharedPreferenceChangeListener(", "注册变更监听")
        )
        "SharedPreferences.Editor" -> listOf(
            m("putString(", "写入字符串"),
            m("putInt(", "写入整数"),
            m("putLong(", "写入长整数"),
            m("putBoolean(", "写入布尔"),
            m("putFloat(", "写入浮点"),
            m("putStringSet(", "写入字符串集合"),
            m("remove(", "移除键"),
            m("clear()", "清空"),
            m("commit()", "同步提交"),
            m("apply()", "异步提交")
        )
        "Uri" -> listOf(
            m("getPath()", "取路径"),
            m("getScheme()", "取协议"),
            m("getHost()", "取主机"),
            m("getQuery()", "取查询串"),
            m("getLastPathSegment()", "取路径末段"),
            m("getQueryParameter(", "取查询参数"),
            m("toString()", "转字符串")
        )
        "Log" -> listOf(
            m("d(", "调试日志"),
            m("i(", "信息日志"),
            m("w(", "警告日志"),
            m("e(", "错误日志"),
            m("v(", "详细日志")
        )
        "Toast" -> listOf(
            m("makeText(", "创建 Toast"),
            m("show()", "显示"),
            m("setGravity(", "设置位置"),
            m("setDuration(", "设置时长")
        )
        "Application" -> listOf(
            m("onCreate()", "应用创建回调"),
            m("getApplicationContext()", "取应用上下文"),
            m("registerActivityLifecycleCallbacks(", "注册 Activity 生命周期回调"),
            m("getPackageName()", "取包名")
        )
        "Window" -> listOf(
            m("setStatusBarColor(", "设置状态栏颜色"),
            m("setNavigationBarColor(", "设置导航栏颜色"),
            m("setSoftInputMode(", "设置软键盘模式"),
            m("addFlags(", "追加标志位"),
            m("getDecorView()", "取根视图")
        )
        "ViewGroup" -> listOf(
            m("addView(", "添加子视图"),
            m("removeView(", "移除子视图"),
            m("removeAllViews()", "移除全部子视图"),
            m("getChildCount()", "取子视图数量"),
            m("getChildAt(", "取指定子视图"),
            m("setLayoutParams(", "设置布局参数"),
            m("setClipChildren(", "设置子视图裁剪")
        )
        else -> emptyList()
    }
}

/** Jetpack Compose / 协程相关类型的成员表。 */
object ComposeMembers {

    private fun m(name: String, detail: String, kind: CompletionKind = CompletionKind.METHOD, insert: String = name) =
        ApiEntry(name, insert, detail, kind)

    fun members(typeName: String): List<ApiEntry> = when (TypeMembers.normalize(typeName)) {
        "Modifier" -> listOf(
            m("padding(", "设置内边距"),
            m("fillMaxWidth(", "横向撑满父容器"),
            m("fillMaxHeight(", "纵向撑满父容器"),
            m("fillMaxSize(", "撑满父容器"),
            m("width(", "设置宽度"),
            m("height(", "设置高度"),
            m("size(", "同时设置宽高"),
            m("wrapContentSize(", "按内容尺寸"),
            m("background(", "设置背景"),
            m("clip(", "裁剪形状"),
            m("border(", "设置边框"),
            m("clickable(", "设置点击"),
            m("pointerInput(", "自定义指针输入处理"),
            m("align(", "在父容器中对齐"),
            m("weight(", "按权重分配剩余空间"),
            m("offset(", "平移"),
            m("rotate(", "旋转"),
            m("scale(", "缩放"),
            m("alpha(", "设置透明度"),
            m("shadow(", "设置阴影"),
            m("zIndex(", "设置层级"),
            m("aspectRatio(", "设置宽高比"),
            m("verticalScroll(", "纵向滚动"),
            m("horizontalScroll(", "横向滚动"),
            m("scrollable(", "通用滚动"),
            m("weight", "权重", CompletionKind.PROPERTY),
            m("testTag", "测试标记", CompletionKind.PROPERTY),
            m("semantics(", "无障碍语义")
        )
        "State" -> listOf(
            m("value", "当前值", CompletionKind.PROPERTY),
            m("component1()", "解构第一个值")
        )
        "MutableState" -> listOf(
            m("value", "当前值（可写）", CompletionKind.PROPERTY),
            m("getValue(", "委托取值")
        )
        "SnapshotStateList" -> listOf(
            m("add(", "添加元素"),
            m("remove(", "移除元素"),
            m("clear()", "清空"),
            m("size", "元素个数", CompletionKind.PROPERTY)
        )
        "CoroutineScope" -> listOf(
            m("launch(", "启动协程"),
            m("async(", "启动带返回值的协程"),
            m("withContext(", "切换上下文"),
            m("cancel()", "取消作用域内协程"),
            m("coroutineContext", "协程上下文", CompletionKind.PROPERTY),
            m("isActive", "是否仍活跃", CompletionKind.PROPERTY),
            m("ensureActive()", "确保仍活跃"),
            m("plus(", "合并作用域")
        )
        "Flow" -> listOf(
            m("collect(", "收集数据"),
            m("collectLatest(", "收集最新值"),
            m("map(", "映射数据"),
            m("filter(", "过滤数据"),
            m("flowOn(", "切换上游上下文"),
            m("onEach(", "逐项副作用"),
            m("catch(", "捕获上游异常"),
            m("launchIn(", "在作用域中启动收集"),
            m("first()", "取第一个值"),
            m("toList()", "收集为列表"),
            m("stateIn(", "转为 StateFlow"),
            m("shareIn(", "转为 SharedFlow")
        )
        "Job" -> listOf(
            m("cancel()", "取消作业"),
            m("join(", "等待完成"),
            m("isActive", "是否活跃", CompletionKind.PROPERTY),
            m("isCompleted", "是否完成", CompletionKind.PROPERTY),
            m("isCancelled", "是否已取消", CompletionKind.PROPERTY),
            m("invokeOnCompletion(", "完成回调"),
            m("cancelAndJoin(", "取消并等待")
        )
        "Deferred" -> listOf(
            m("await(", "等待结果"),
            m("cancel()", "取消"),
            m("join(", "等待完成"),
            m("isCompleted", "是否完成", CompletionKind.PROPERTY)
        )
        "NavController" -> listOf(
            m("navigate(", "导航到目标"),
            m("popBackStack()", "返回上一页"),
            m("navigateUp()", "向上返回"),
            m("currentDestination", "当前目的地", CompletionKind.PROPERTY),
            m("graph", "导航图", CompletionKind.PROPERTY)
        )
        "NavHostController" -> listOf(
            m("navigate(", "导航到路由"),
            m("popBackStack()", "返回上一页"),
            m("currentBackStackEntry", "当前返回栈条目", CompletionKind.PROPERTY)
        )
        "LazyListState" -> listOf(
            m("firstVisibleItemIndex", "首个可见项下标", CompletionKind.PROPERTY),
            m("firstVisibleItemScrollOffset", "首个可见项偏移", CompletionKind.PROPERTY),
            m("scrollToItem(", "滚动到指定项"),
            m("animateScrollToItem(", "动画滚动到指定项"),
            m("isScrollInProgress", "是否滚动中", CompletionKind.PROPERTY)
        )
        "SnackbarHostState" -> listOf(
            m("showSnackbar(", "显示提示条"),
            m("currentSnackbarData", "当前提示条数据", CompletionKind.PROPERTY)
        )
        "Context" -> AndroidMembers.members("Context")
        "Uri", "Toast", "Log", "Intent", "Bundle" -> AndroidMembers.members(typeName)
        else -> emptyList()
    }
}

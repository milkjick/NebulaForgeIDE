package com.nebulaforge.core.plugin

import android.content.Context
import dalvik.system.DexClassLoader
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile
import com.nebulaforge.core.projectmodel.ProjectType

/** plugin.xml 描述、权限、扩展点和 APK/ZIP ClassLoader 的真实运行时。 */
enum class ExtensionPointType { PROJECT_TYPE, BUILD_SYSTEM, LANGUAGE_SERVICE, TOOL_WINDOW, AI_TOOL, MCP_SERVICE, CODE_INSPECTION, RUN_CONFIGURATION, EDITOR_ACTION }

data class ExtensionDeclaration(val point: ExtensionPointType, val implementationClass: String)
data class PluginPermission(val type: String, val scope: String?)
data class PluginDescriptor(
    val id: String, val name: String, val version: String, val vendor: String?, val vendorUrl: String?, val description: String?,
    val apiMin: Int, val apiMax: Int, val dependencies: List<String>, val permissions: List<PluginPermission>, val extensions: List<ExtensionDeclaration>, val source: File
)

class PluginDescriptorReader(private val context: Context) {
    fun read(file: File): PluginDescriptor {
        require(file.isFile) { "插件文件不存在" }
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("plugin.xml") ?: error("插件缺少 plugin.xml")
            val parser = android.util.Xml.newPullParser(); parser.setInput(zip.getInputStream(entry), "UTF-8")
            var id = ""; var name = ""; var version = ""; var vendor: String? = null; var vendorUrl: String? = null; var desc: String? = null; var min = 1; var max = 1
            val deps = mutableListOf<String>(); val permissions = mutableListOf<PluginPermission>(); val extensions = mutableListOf<ExtensionDeclaration>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) when (parser.name) {
                    "plugin" -> Unit
                    "id" -> id = parser.nextText().trim()
                    "name" -> name = parser.nextText().trim()
                    "version" -> version = parser.nextText().trim()
                    "vendor" -> { vendorUrl = parser.getAttributeValue(null, "url"); vendor = parser.nextText().trim() }
                    "description" -> desc = parser.nextText().trim()
                    "apiVersion" -> { min = parser.getAttributeValue(null, "min")?.toIntOrNull() ?: 1; max = parser.getAttributeValue(null, "max")?.toIntOrNull() ?: min }
                    "dependency" -> parser.getAttributeValue(null, "id")?.let(deps::add)
                    "permission" -> permissions += PluginPermission(parser.getAttributeValue(null, "type") ?: "unknown", parser.getAttributeValue(null, "scope"))
                    "extension" -> {
                        val point = parser.getAttributeValue(null, "point")?.let { runCatching { ExtensionPointType.valueOf(it.uppercase().replace('-', '_')) }.getOrNull() }
                        val impl = parser.getAttributeValue(null, "implementation")
                        if (point != null && !impl.isNullOrBlank()) extensions += ExtensionDeclaration(point, impl)
                    }
                }
                event = parser.next()
            }
            require(id.matches(Regex("[A-Za-z0-9_.-]+"))) { "插件 ID 非法" }
            require(name.isNotBlank() && version.isNotBlank()) { "插件名称或版本为空" }
            return PluginDescriptor(id, name, version, vendor, vendorUrl, desc, min, max, deps, permissions, extensions, file)
        }
    }
}

class PluginPermissionPolicy {
    fun validate(descriptor: PluginDescriptor, granted: Set<String>): List<String> = descriptor.permissions.mapNotNull { p ->
        val key = p.type + (p.scope?.let { ":$it" } ?: "")
        if (key in granted) null else key
    }
}

class PluginClassLoaderFactory(private val context: Context) {
    fun create(descriptor: PluginDescriptor): ClassLoader {
        val dir = File(context.codeCacheDir, "plugins/${descriptor.id}/${descriptor.version}").apply { mkdirs() }
        val optimized = File(dir, "dex").apply { mkdirs() }
        return DexClassLoader(descriptor.source.absolutePath, optimized.absolutePath, null, context.classLoader)
    }
}


/** 插件基接口：所有 Nebula 插件（含 SDK 插件）的公共父类型。 */
interface NebulaPlugin

/** 插件运行时：负责 API 兼容性、权限校验、扩展实例化和生命周期，而不是只解析 plugin.xml。 */
interface NebulaPluginLifecycle {
    fun onLoad(context: Context)
    fun onUnload(context: Context)
}

/** 已加载插件的扩展注册表；卸载插件时同步移除其 ProjectType，避免僵尸扩展继续参与项目识别。 */
class PluginExtensionRegistry {
    private val projectTypes = LinkedHashMap<String, Pair<String, ProjectType>>()
    private val generic = LinkedHashMap<String, MutableList<Pair<String, Any>>>()

    @Synchronized
    fun register(pluginId: String, declaration: ExtensionDeclaration, extension: Any) {
        if (extension is ProjectType) projectTypes[extension.id] = pluginId to extension
        generic.getOrPut(declaration.point.name) { mutableListOf() }.add(pluginId to extension)
    }

    @Synchronized
    fun unregister(pluginId: String) {
        projectTypes.entries.removeIf { it.value.first == pluginId }
        generic.values.forEach { list -> list.removeIf { it.first == pluginId } }
        generic.entries.removeIf { it.value.isEmpty() }
    }

    @Synchronized fun projectTypes(): List<ProjectType> = projectTypes.values.map { it.second }
    @Synchronized fun extensions(point: ExtensionPointType): List<Any> = generic[point.name].orEmpty().map { it.second }
}

data class LoadedPlugin(
    val descriptor: PluginDescriptor,
    val classLoader: ClassLoader,
    val extensions: List<Any>
)

class PluginRuntime(
    private val context: Context,
    private val apiVersion: Int = 1,
    private val permissionPolicy: PluginPermissionPolicy = PluginPermissionPolicy(),
    val extensionRegistry: PluginExtensionRegistry = PluginExtensionRegistry()
) {
    private val loaderFactory = PluginClassLoaderFactory(context)
    private val loaded = LinkedHashMap<String, LoadedPlugin>()

    /** 每个已加载插件的权限沙箱（15.4：运行时权限检查的唯一依据） */
    private val sandboxes = LinkedHashMap<String, PluginSandbox>()

    @Synchronized
    fun load(file: File, grantedPermissions: Set<String> = emptySet()): LoadedPlugin {
        val descriptor = PluginDescriptorReader(context).read(file)
        require(apiVersion in descriptor.apiMin..descriptor.apiMax) { "插件 ${descriptor.id} 不兼容宿主 API $apiVersion" }
        val denied = permissionPolicy.validate(descriptor, grantedPermissions)
        require(denied.isEmpty()) { "插件缺少权限：${denied.joinToString("、")}" }
        require(descriptor.id !in loaded) { "插件 ${descriptor.id} 已加载" }
        descriptor.dependencies.forEach { dependency -> require(dependency in loaded) { "插件 ${descriptor.id} 缺少依赖：$dependency" } }
        val classLoader = loaderFactory.create(descriptor)
        val extensionPairs = descriptor.extensions.map { declaration ->
            val clazz = Class.forName(declaration.implementationClass, true, classLoader)
            val ctor = clazz.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                ?: error("扩展 ${declaration.implementationClass} 必须提供无参构造函数")
            ctor.isAccessible = true
            declaration to ctor.newInstance()
        }
        val extensions = extensionPairs.map { it.second }
        val result = LoadedPlugin(descriptor, classLoader, extensions)
        loaded[descriptor.id] = result
        // 15.4：记录本插件的权限沙箱，后续所有受限 API 调用都经它检查
        sandboxes[descriptor.id] = PluginSandbox(descriptor.id, grantedPermissions)
        extensionPairs.forEach { (declaration, extension) -> extensionRegistry.register(descriptor.id, declaration, extension) }
        try {
            extensions.filterIsInstance<NebulaPluginLifecycle>().forEach { it.onLoad(context) }
            return result
        } catch (t: Throwable) {
            loaded.remove(descriptor.id)
            sandboxes.remove(descriptor.id)
            extensionRegistry.unregister(descriptor.id)
            extensions.filterIsInstance<NebulaPluginLifecycle>().forEach { runCatching { it.onUnload(context) } }
            throw IllegalStateException("插件 ${descriptor.id} 加载失败：${t.message}", t)
        }
    }

    @Synchronized
    fun unload(id: String): Boolean {
        val plugin = loaded.remove(id) ?: return false
        sandboxes.remove(id)
        extensionRegistry.unregister(id)
        plugin.extensions.filterIsInstance<NebulaPluginLifecycle>().forEach { it.onUnload(context) }
        return true
    }

    /** 取某插件的权限沙箱；插件未加载时返回 null */
    @Synchronized
    fun sandbox(id: String): PluginSandbox? = sandboxes[id]

    /**
     * 取某插件的受限访问上下文。受限 API 调用前会经 [PluginSandbox] 校验，
     * 未授权抛 [PluginPermissionDeniedException]（SecurityException 子类）。
     */
    fun contextFor(id: String, projectRoot: File?): PluginContext? =
        sandbox(id)?.let { PluginContext(id, it, projectRoot) }


    /** 按依赖关系返回已安装插件的加载顺序；发现循环依赖时直接拒绝。 */
    @Synchronized
    fun resolveLoadOrder(descriptors: List<PluginDescriptor>): List<PluginDescriptor> {
        val byId = descriptors.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<PluginDescriptor>()
        fun visit(id: String) {
            if (id in visited) return
            require(id !in visiting) { "插件依赖存在循环：$id" }
            val descriptor = byId[id] ?: error("缺少插件依赖：$id")
            visiting += id
            descriptor.dependencies.forEach(::visit)
            visiting -= id
            visited += id
            result += descriptor
        }
        descriptors.forEach { visit(it.id) }
        return result
    }

    fun loadedPlugins(): List<LoadedPlugin> = synchronized(this) { loaded.values.toList() }
    fun find(id: String): LoadedPlugin? = synchronized(this) { loaded[id] }
}

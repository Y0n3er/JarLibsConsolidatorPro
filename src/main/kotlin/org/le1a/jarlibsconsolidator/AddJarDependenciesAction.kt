package org.le1a.jarlibsconsolidator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

/**
 * 一键添加jar依赖的Action类 (最终版)
 * 将每个扫描到的jar文件，作为独立的、具名的模块级库，添加到所有模块中。
 * 兼容多个IDEA版本 (243.x - 251.x+)
 */
class AddJarDependenciesAction : AnAction() {

    // 版本检测：2025.1 对应 build 251
    private val isNewThreadingModel: Boolean by lazy {
        val buildNumber = ApplicationInfo.getInstance().build.baselineVersion
        buildNumber >= 251 // 2025.1及以上版本使用新的线程模型
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: run {
            showError(project, "无法获取项目根目录")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "正在收集并添加jar依赖...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "正在扫描jar文件..."
                    indicator.fraction = 0.1

                    val jarFiles = findJarFiles(File(basePath), indicator)
                    if (jarFiles.isEmpty()) {
                        showInfo(project, "未找到任何jar文件")
                        return
                    }

                    indicator.text = "正在将jar添加到各模块依赖..."
                    indicator.fraction = 0.5

                    if (isNewThreadingModel) {
                        addJarsToLibrary_New(project, jarFiles)
                    } else {
                        addJarsToLibrary_Old(project, jarFiles)
                    }

                    indicator.fraction = 1.0
                    showSuccess(project, jarFiles.size)

                } catch (e: Exception) {
                    showError(project, "操作失败：${e.message}")
                }
            }
        })
    }

    // findJarFiles 和 shouldSkipDirectory 方法保持不变...
    private fun findJarFiles(directory: File, indicator: ProgressIndicator): List<File> {
        val jarFiles = mutableListOf<File>()
        fun searchDirectory(dir: File) {
            if (indicator.isCanceled) return
            try {
                dir.listFiles()?.forEach { file ->
                    if (indicator.isCanceled) return
                    when {
                        file.isDirectory -> {
                            if (!shouldSkipDirectory(file.name)) {
                                searchDirectory(file)
                            }
                        }
                        file.isFile && file.name.endsWith(".jar", ignoreCase = true) -> {
                            jarFiles.add(file)
                            indicator.text2 = "发现: ${file.name}"
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        searchDirectory(directory)
        return jarFiles
    }

    private fun shouldSkipDirectory(dirName: String): Boolean {
        return dirName.startsWith(".") ||
                dirName == "node_modules" ||
                dirName == "target" ||
                dirName == "build" ||
                dirName == ".gradle" ||
                dirName == ".mvn"
    }

    private fun addJarsToLibrary_New(project: Project, jarFiles: List<File>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteAction.run<RuntimeException> {
                    addJarsAsModuleLibraries(project, jarFiles)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "添加到模块库失败：${e.message}", "错误")
                }
            }
        }
    }

    private fun addJarsToLibrary_Old(project: Project, jarFiles: List<File>) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    addJarsAsModuleLibraries(project, jarFiles)
                } catch (e: Exception) {
                    throw RuntimeException("添加到模块库失败：${e.message}")
                }
            }
        }
    }

    /**
     * [核心修改]
     * 核心库操作逻辑。
     * 遍历所有模块，为每个模块单独添加所有jar文件作为独立的、具名的模块级库。
     */
    private fun addJarsAsModuleLibraries(project: Project, jarFiles: List<File>) {
        val modules = ModuleManager.getInstance(project).modules
        modules.forEach { module ->
            // 对每个模块获取一个可修改的依赖模型
            val moduleModel = ModuleRootManager.getInstance(module).modifiableModel
            try {
                // --- 第一步：清理旧的、可能由本插件添加的库 ---
                val toRemove = moduleModel.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    // 筛选出那些库名与我们即将添加的jar文件名相同的条目
                    .filter { entry -> jarFiles.any { it.name == entry.library?.name } }

                toRemove.forEach { moduleModel.removeOrderEntry(it) }

                // --- 第二步：为每个JAR文件创建新的模块级库 ---
                jarFiles.forEach { jarFile ->
                    val virtualFile = VfsUtil.findFileByIoFile(jarFile, true) ?: return@forEach
                    val jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(virtualFile) ?: return@forEach

                    // 创建一个以jar文件命名的模块级库
                    val library = moduleModel.moduleLibraryTable.createLibrary(jarFile.name)
                    val libraryModel = library.modifiableModel

                    try {
                        libraryModel.addRoot(jarRoot, OrderRootType.CLASSES)
                        libraryModel.commit()
                    } catch (e: Exception) {
                        // [MODIFIED] 移除了 isDisposed 检查
                        libraryModel.dispose()
                        println("警告：无法为模块 ${module.name} 添加jar文件 ${jarFile.name}: ${e.message}")
                    }
                }
                // 提交对该模块依赖的全部修改
                moduleModel.commit()
            } catch (e: Exception) {
                // [MODIFIED] 移除了 isDisposed 检查
                moduleModel.dispose()
                throw RuntimeException("为模块 ${module.name} 配置依赖时出错: ${e.message}")
            }
        }
    }

    // showError, showInfo, showSuccess, update 方法保持不变...
    private fun showInfo(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "提示")
        }
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "错误")
        }
    }

    private fun showSuccess(project: Project, count: Int) {
        ApplicationManager.getApplication().invokeLater {
            val versionInfo = if (isNewThreadingModel) "2025.1+" else "2024.3-"
            Messages.showInfoMessage(
                project,
                "成功将 $count 个jar文件作为独立库添加到所有模块\n(兼容模式: $versionInfo)",
                "操作完成"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
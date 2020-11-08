package org.arend.toolWindow.debugExpr

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import org.arend.naming.reference.TCDefReferable
import org.arend.psi.ArendExpr
import org.arend.toolWindow.SimpleToolWindowService
import org.arend.typechecking.LibraryArendExtensionProvider
import org.arend.typechecking.PsiInstanceProviderSet
import org.arend.typechecking.TypeCheckingService
import org.arend.typechecking.error.ErrorService
import org.arend.typechecking.instance.pool.GlobalInstancePool

class ArendDebugService(project: Project) : SimpleToolWindowService(project) {
    companion object Constants {
        const val TITLE = "Typecheck Debug"
        const val ID = "Arend.Typecheck.Debug"
    }

    override val title: String get() = TITLE

    fun showFor(element: ArendExpr, at: TCDefReferable): CheckTypeDebugger {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = myToolWindow ?: registerToolWindow(manager).also { myToolWindow = it }
        val service = project.service<TypeCheckingService>()
        val debugger = CheckTypeDebugger(
            project.service<ErrorService>(),
            LibraryArendExtensionProvider(service.libraryManager).getArendExtension(at),
            element,
            toolWindow,
        )
        debugger.instancePool = GlobalInstancePool(PsiInstanceProviderSet()[at], debugger)
        Disposer.register(toolWindow.disposable, debugger)
        val toolWindowPanel = SimpleToolWindowPanel(false, false)
        toolWindowPanel.setContent(debugger.splitter)
        // toolWindowPanel.toolbar = ActionManager.getInstance()
        //     .createActionToolbar(TITLE, , true).component
        val content = ContentFactory.SERVICE.getInstance()
            .createContent(toolWindowPanel.component, null, false)
        toolWindow.contentManager.addContent(content)
        content.preferredFocusableComponent = toolWindowPanel.content
        activate(toolWindow, manager)
        return debugger
    }
}
package org.arend.search.proof

import com.intellij.ide.IdeEventQueue
import com.intellij.ide.actions.BigPopupUI
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.WindowStateService
import com.intellij.util.ui.JBInsets
import java.awt.Dimension

@Service(Service.Level.PROJECT)
class SignatureSearchService {

    private var mySignatureSearchUI: SignatureSearchUI? = null

    private var myBalloon: JBPopup? = null
    private var myBalloonFullSize: Dimension? = null
    private var lastSearchText: String? = null

    fun show(e: AnActionEvent) {
        IdeEventQueue.getInstance().popupManager.closeAllPopups(false)

        val project: Project = e.project ?: return

        val signatureSearchUI = createView(e)
        mySignatureSearchUI = signatureSearchUI
        val balloon =
            with(JBPopupFactory.getInstance().createComponentPopupBuilder(signatureSearchUI, signatureSearchUI.editorSearchField)) {
                setProject(project)
                setModalContext(false)
                setCancelOnClickOutside(true)
                setRequestFocus(true)
                setCancelKeyEnabled(false)
                setCancelCallback {
                    if (isShown()) {
                        lastSearchText = mySignatureSearchUI?.editorSearchField?.text
                    }
                    true
                }
                addUserData("SIMPLE_WINDOW") // NON-NLS
                setResizable(true)
                setMovable(true)
                    .setDimensionServiceKey(project, PROOF_SEARCH_LOCATION_KEY, true)
                setLocateWithinScreenBounds(false)
                createPopup()
            }.also {
                Disposer.register(it, signatureSearchUI)
                Disposer.register(project, it)
            }
        myBalloon = balloon

        Disposer.register(balloon) {
            if (signatureSearchUI.viewType == BigPopupUI.ViewType.SHORT) {
                WindowStateService.getInstance(project).putSize(PROOF_SEARCH_LOCATION_KEY, myBalloonFullSize)
            }
            mySignatureSearchUI = null
            myBalloon = null
            myBalloonFullSize = null
        }

        if (signatureSearchUI.viewType == BigPopupUI.ViewType.SHORT) {
            myBalloonFullSize = WindowStateService.getInstance(project).getSize(PROOF_SEARCH_LOCATION_KEY)
            balloon.size = signatureSearchUI.preferredSize
        }
        calcPositionAndShow(project, balloon, signatureSearchUI)
        val text = signatureSearchUI.editorSearchField.text
        if (text.isNotEmpty()) {
            signatureSearchUI.editorSearchField.editor?.selectionModel?.setSelection(0, text.length)
            signatureSearchUI.trySearch()
        }
    }

    private fun calcPositionAndShow(project: Project, balloon: JBPopup, signatureSearchUI: SignatureSearchUI) {
        val savedLocation = WindowStateService.getInstance(project).getLocation(PROOF_SEARCH_LOCATION_KEY)
        balloon.showCenteredInCurrentWindow(project)

        if (savedLocation == null && signatureSearchUI.viewType == BigPopupUI.ViewType.SHORT) {
            val location = balloon.locationOnScreen
            location.y /= 2
            balloon.setLocation(location)
        }
    }

    private fun isShown(): Boolean {
        return mySignatureSearchUI != null && myBalloon.let { it != null && !it.isDisposed }
    }

    private fun createView(event: AnActionEvent): SignatureSearchUI {
        val view = SignatureSearchUI(event.project!!)
        view.setSearchFinishedHandler {
            if (isShown()) {
                myBalloon?.cancel()
            }
        }
        view.addViewTypeListener { viewType: BigPopupUI.ViewType ->
            if (!isShown()) {
                return@addViewTypeListener
            }
            ApplicationManager.getApplication().invokeLater {
                // this is EDT, all notnull-assertions are justified by this fact
                val balloon = myBalloon ?: return@invokeLater
                val minSize = view.minimumSize
                JBInsets.addTo(minSize, balloon.content.insets)
                balloon.setMinimumSize(minSize)
                if (viewType == BigPopupUI.ViewType.SHORT) {
                    myBalloonFullSize = balloon.size
                    JBInsets.removeFrom(myBalloonFullSize!!, balloon.content.insets)
                    balloon.pack(false, true)
                } else {
                    if (myBalloonFullSize == null) {
                        myBalloonFullSize = view.preferredSize
                        JBInsets.addTo(myBalloonFullSize!!, balloon.content.insets)
                    }
                    myBalloonFullSize!!.height = Integer.max(myBalloonFullSize!!.height, minSize.height)
                    myBalloonFullSize!!.width = Integer.max(myBalloonFullSize!!.width, minSize.width)
                    balloon.size = myBalloonFullSize!!
                }
            }
        }

        val lastText = lastSearchText
        if (lastText != null) {
            view.editorSearchField.text = lastText
        }
        return view
    }
}

private const val PROOF_SEARCH_LOCATION_KEY = "proof.search.location.key"
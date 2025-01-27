package org.arend.documentation

import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.editor.colors.EditorColorsManager

class GenerateArendLibHtmlStarter : ApplicationStarter {
    override val commandName: String
        get() = "generateArendLibHtml"

    override fun main(args: List<String>) {
        val arguments = args.map { it.ifEmpty { null } }

        val pathToArendLib = arguments.getOrNull(1) ?: run {
            println("The path to the Arend library is not specified")
            return
        }
        val pathToArendLibInArendSite = arguments.getOrNull(2) ?: run {
            println("The path to the Arend library in Arend site is not specified")
            return
        }
        val versionArendLib = arguments.getOrNull(3)

        generateHtmlForArendLib(pathToArendLib, pathToArendLibInArendSite, versionArendLib)
    }
}
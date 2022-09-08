package org.arend.project

import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectImportBuilder
import org.arend.util.FileUtils

@Suppress("UnstableApiUsage")
object ArendOpenProjectProvider : AbstractOpenProjectProvider() {
    override val systemId: ProjectSystemId
        get() = ProjectSystemId("Arend")

    val builder: ArendProjectImportBuilder
        get() = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(ArendProjectImportBuilder::class.java)

    override fun isProjectFile(file: VirtualFile) = file.name == FileUtils.LIBRARY_CONFIG_FILE

    override fun linkToExistingProject(projectFile: VirtualFile, project: Project) {
        try {
            builder.isUpdate = false
            builder.fileToImport = (if (projectFile.isDirectory) projectFile else projectFile.parent).toNioPath().toString()
            if (builder.validate(null, project)) {
                builder.commit(project, null, ModulesProvider.EMPTY_MODULES_PROVIDER)
            }
        }
        finally {
            builder.cleanup()
        }
    }
}
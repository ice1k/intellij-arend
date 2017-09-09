package org.vclang.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.jetbrains.jetpad.vclang.module.ModulePath
import com.jetbrains.jetpad.vclang.term.Abstract
import com.jetbrains.jetpad.vclang.term.AbstractDefinitionVisitor
import org.vclang.Surrogate
import org.vclang.VcFileType
import org.vclang.VcLanguage
import org.vclang.parser.fullName
import org.vclang.psi.ext.VcCompositeElement
import org.vclang.psi.stubs.VcFileStub
import org.vclang.resolve.*
import java.nio.file.Paths

class VcFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, VcLanguage),
                                               VcCompositeElement,
                                               Abstract.ClassDefinition,
                                               Surrogate.StatementCollection {
    private var globalStatements = emptyList<Surrogate.Statement>()

    val relativeModulePath: ModulePath
        get() {
            val sourceRoot = sourceRoot ?: contentRoot ?: error("Failed to find source root")
            val sourcePath = Paths.get(sourceRoot.path)
            val modulePath = Paths.get(
                    virtualFile.path.removeSuffix('.' + VcFileType.defaultExtension)
            )
            val relativeModulePath = sourcePath.relativize(modulePath)
            return ModulePath(relativeModulePath.map { it.toString() })
        }


    override val namespace: Namespace
        get() {
            val statements = children.filterIsInstance<VcStatement>()
            return NamespaceProvider.forDefinitions(statements.mapNotNull { it.statDef })
        }

    override val scope: Scope
        get() {
            val namespaceScope = MergeScope(PreludeScope, NamespaceScope(namespace))
            val statements = children.filterIsInstance<VcStatement>()
            return statements
                    .mapNotNull { it.statCmd }
                    .map { it.scope }
                    .fold(namespaceScope) { scope1, scope2 -> MergeScope(scope1, scope2) }
        }

    override fun setName(name: String): PsiElement {
        val nameWithExtension = if (name.endsWith('.' + VcFileType.defaultExtension)) {
            name
        } else {
            "$name.${VcFileType.defaultExtension}"
        }
        return super.setName(nameWithExtension)
    }

    override fun getStub(): VcFileStub? = super.getStub() as VcFileStub?

    override fun getReference(): VcReference? = null

    override fun getFileType(): FileType = VcFileType

    override fun getPolyParameters(): List<Abstract.TypeParameter> = emptyList()

    override fun getSuperClasses(): List<Abstract.SuperClass> = emptyList()

    override fun getFields(): List<Abstract.ClassField> = emptyList()

    override fun getImplementations(): List<Abstract.Implementation> = emptyList()

    override fun getInstanceDefinitions(): List<Abstract.Definition> = emptyList()

    override fun getGlobalStatements(): List<Surrogate.Statement> = globalStatements

    fun setGlobalStatements(globalStatements: List<Surrogate.Statement>) {
        this.globalStatements = globalStatements
    }

    override fun getPrecedence(): Abstract.Precedence = Abstract.Precedence.DEFAULT

    override fun getParentDefinition(): Abstract.Definition? = null

    override fun isStatic(): Boolean = true

    override fun <P, R> accept(visitor: AbstractDefinitionVisitor<in P, out R>, params: P): R =
            visitor.visitClass(this, params)

    fun findDefinitionByFullName(fullName: String): Abstract.Definition? =
            accept(FindDefinitionVisitor, fullName)

    private object FindDefinitionVisitor : AbstractDefinitionVisitor<String, Abstract.Definition?> {

        override fun visitFunction(
                definition: Abstract.FunctionDefinition,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            definition.globalDefinitions.forEach { it.accept(this, params)?.let { return it } }
            return null
        }

        override fun visitClassField(
                definition: Abstract.ClassField,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            return null
        }

        override fun visitData(
                definition: Abstract.DataDefinition,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            definition.constructorClauses
                    .flatMap { it.constructors }
                    .forEach { it.accept(this, params)?.let { return it } }
            return null
        }

        override fun visitConstructor(
                definition: Abstract.Constructor,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            return null
        }

        override fun visitClass(
                definition: Abstract.ClassDefinition,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            definition.globalDefinitions.forEach { it.accept(this, params)?.let { return it } }
            definition.instanceDefinitions.forEach { it.accept(this, params)?.let { return it } }
            definition.fields.forEach { it.accept(this, params)?.let { return it } }
            return null
        }

        override fun visitImplement(
                definition: Abstract.Implementation,
                params: String
        ): Abstract.Definition? = null

        override fun visitClassView(
                definition: Abstract.ClassView,
                params: String
        ): Abstract.Definition? = null

        override fun visitClassViewField(
                definition: Abstract.ClassViewField,
                params: String
        ): Abstract.Definition? = null

        override fun visitClassViewInstance(
                definition: Abstract.ClassViewInstance,
                params: String
        ): Abstract.Definition? {
            if (definition.fullName == params) return definition
            return null
        }
    }
}
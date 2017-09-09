package org.vclang.psi.ext.adapters

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import com.jetbrains.jetpad.vclang.term.Abstract
import com.jetbrains.jetpad.vclang.term.AbstractDefinitionVisitor
import org.vclang.Surrogate
import org.vclang.VcIcons
import org.vclang.psi.VcDefInstance
import org.vclang.psi.stubs.VcDefInstanceStub
import javax.swing.Icon

abstract class ClassViewInstanceAdapter : DefinitionAdapter<VcDefInstanceStub>,
                                          VcDefInstance {
    private var parameters: List<Surrogate.Parameter>? = null
    private var classView: Surrogate.ReferenceExpression? = null
    private var classFieldImpls: List<Surrogate.ClassFieldImpl>? = null
    private var classifyingDefinition: Abstract.Definition? = null

    constructor(node: ASTNode) : super(node)

    constructor(stub: VcDefInstanceStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override fun getIcon(flags: Int): Icon = VcIcons.CLASS_VIEW_INSTANCE

    fun reconstruct(
            position: Surrogate.Position?,
            name: String?,
            precedence: Abstract.Precedence?,
            parameters: List<Surrogate.Parameter>?,
            classView: Surrogate.ReferenceExpression?,
            classFieldImpls: List<Surrogate.ClassFieldImpl>?
    ): ClassViewInstanceAdapter {
        super.reconstruct(position, name, precedence)
        this.parameters = parameters
        this.classView = classView
        this.classFieldImpls = classFieldImpls
        return this
    }

    override fun isDefault(): Boolean = defaultKw != null

    override fun getParameters(): List<Surrogate.Parameter> =
            parameters ?: throw IllegalStateException()

    override fun getClassView(): Surrogate.ReferenceExpression =
            classView ?: throw IllegalStateException()

    override fun getClassifyingDefinition(): Abstract.Definition =
            classifyingDefinition ?: throw IllegalStateException()

    fun setClassifyingDefinition(classifyingDefinition: Abstract.Definition) {
        this.classifyingDefinition = classifyingDefinition
    }

    override fun getClassFieldImpls(): List<Surrogate.ClassFieldImpl> =
            classFieldImpls ?: throw IllegalStateException()

    override fun <P, R> accept(visitor: AbstractDefinitionVisitor<in P, out R>, params: P): R =
            visitor.visitClassViewInstance(this, params)
}
package org.vclang.typing

import com.jetbrains.jetpad.vclang.naming.reference.ErrorReference
import com.jetbrains.jetpad.vclang.naming.reference.Referable
import com.jetbrains.jetpad.vclang.naming.reference.UnresolvedReference
import com.jetbrains.jetpad.vclang.naming.resolving.visitor.ExpressionResolveNameVisitor
import com.jetbrains.jetpad.vclang.term.abs.Abstract
import com.jetbrains.jetpad.vclang.term.abs.AbstractExpressionVisitor
import com.jetbrains.jetpad.vclang.term.concrete.Concrete
import org.vclang.psi.ext.VcCompositeElement
import java.math.BigInteger


open class GetKindVisitor : AbstractExpressionVisitor<Void, GetKindVisitor.Kind> {
    enum class Kind {
        LAMBDA { override fun isWHNF() = true },
        PI { override fun isWHNF() = true },
        UNIVERSE { override fun isWHNF() = true },
        TUPLE { override fun isWHNF() = true },
        SIGMA { override fun isWHNF() = true },
        CLASS_EXT { override fun isWHNF() = true },
        CONSTRUCTOR { override fun isWHNF() = true }, // maybe with arguments
        DATA { override fun isWHNF() = true }, // maybe with arguments
        CLASS { override fun isWHNF() = true }, // maybe with arguments
        REFERENCE { override fun isWHNF() = true }, // maybe with arguments
        CONSTRUCTOR_WITH_CONDITIONS, // maybe with arguments
        APP, HOLE, GOAL, CASE, PROJ, LET, NUMBER, UNRESOLVED_REFERENCE, FIELD, FIELD_SYN, FUNCTION, INSTANCE;

        open fun isWHNF(): Boolean = false
    }

    private fun getReferenceKind(data: Any?, referent: Referable): Kind {
        val ref = (data as? VcCompositeElement)?.scope?.let { ExpressionResolveNameVisitor.resolve(referent, it) } ?: referent
        return when (ref) {
            is UnresolvedReference, is ErrorReference -> Kind.UNRESOLVED_REFERENCE
            is Abstract.ClassField -> Kind.FIELD
            is Abstract.ClassFieldSynonym -> Kind.FIELD_SYN
            is Abstract.Constructor -> if (ref.clauses.isEmpty()) Kind.CONSTRUCTOR else Kind.CONSTRUCTOR_WITH_CONDITIONS
            is Abstract.DataDefinition -> Kind.DATA
            is Abstract.ClassDefinition -> Kind.CLASS
            is Abstract.FunctionDefinition -> Kind.FUNCTION
            is Abstract.InstanceDefinition -> Kind.INSTANCE
            else -> Kind.REFERENCE
        }
    }

    override fun visitErrors() = false
    override fun visitReference(data: Any?, referent: Referable, level1: Abstract.LevelExpression?, level2: Abstract.LevelExpression?, errorData: Abstract.ErrorData?, params: Void?) = getReferenceKind(data, referent)
    override fun visitReference(data: Any?, referent: Referable, lp: Int, lh: Int, errorData: Abstract.ErrorData?, params: Void?) = getReferenceKind(data, referent)
    override fun visitLam(data: Any?, parameters: Collection<Abstract.Parameter>, body: Abstract.Expression?, errorData: Abstract.ErrorData?, params: Void?) = Kind.LAMBDA
    override fun visitPi(data: Any?, parameters: Collection<Abstract.Parameter>, codomain: Abstract.Expression?, errorData: Abstract.ErrorData?, params: Void?) = Kind.PI
    override fun visitUniverse(data: Any?, pLevelNum: Int?, hLevelNum: Int?, pLevel: Abstract.LevelExpression?, hLevel: Abstract.LevelExpression?, errorData: Abstract.ErrorData?, params: Void?) = Kind.UNIVERSE
    override fun visitTuple(data: Any?, fields: Collection<Abstract.Expression>, errorData: Abstract.ErrorData?, params: Void?) = Kind.TUPLE
    override fun visitSigma(data: Any?, parameters: Collection<Abstract.Parameter>, errorData: Abstract.ErrorData?, params: Void?) = Kind.SIGMA
    override fun visitClassExt(data: Any?, isNew: Boolean, baseClass: Abstract.Expression?, implementations: Collection<Abstract.ClassFieldImpl>?, sequence: Collection<Abstract.BinOpSequenceElem>, errorData: Abstract.ErrorData?, params: Void?) = Kind.CLASS_EXT
    override fun visitApp(data: Any?, expr: Abstract.Expression, arguments: Collection<Abstract.Argument>, errorData: Abstract.ErrorData?, params: Void?) = Kind.APP
    override fun visitInferHole(data: Any?, errorData: Abstract.ErrorData?, params: Void?) = Kind.HOLE
    override fun visitGoal(data: Any?, name: String?, expression: Abstract.Expression?, errorData: Abstract.ErrorData?, params: Void?) = Kind.GOAL
    override fun visitCase(data: Any?, expressions: Collection<Abstract.Expression>, clauses: Collection<Abstract.FunctionClause>, errorData: Abstract.ErrorData?, params: Void?) = Kind.CASE
    override fun visitFieldAccs(data: Any?, expression: Abstract.Expression, fieldAccs: MutableCollection<Int>, errorData: Abstract.ErrorData?, params: Void?) = Kind.PROJ
    override fun visitLet(data: Any?, clauses: Collection<Abstract.LetClause>, expression: Abstract.Expression?, errorData: Abstract.ErrorData?, params: Void?) = Kind.LET
    override fun visitNumericLiteral(data: Any?, number: BigInteger, errorData: Abstract.ErrorData?, params: Void?) = Kind.NUMBER

    override fun visitBinOpSequence(data: Any?, left: Abstract.Expression, sequence: Collection<Abstract.BinOpSequenceElem>, errorData: Abstract.ErrorData?, params: Void?): Kind {
        val expr = parseBinOp(left, sequence)
        if (expr is Concrete.ReferenceExpression) {
            return getReferenceKind(null, expr.referent)
        }

        val reference = ((expr as? Concrete.AppExpression)?.function as? Concrete.ReferenceExpression)?.referent
        return when (reference) {
            null -> Kind.APP
            is Abstract.DataDefinition -> Kind.DATA
            is Abstract.Constructor -> if (reference.clauses.isEmpty()) Kind.CONSTRUCTOR else Kind.CONSTRUCTOR_WITH_CONDITIONS
            is Abstract.ClassDefinition -> Kind.CLASS
            is Abstract.ReferableDefinition -> Kind.APP
            else -> Kind.REFERENCE
        }
    }
}
package org.arend.refactoring.move

import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.MoveMemberViewDescriptor
import com.intellij.refactoring.move.moveMembers.MoveMembersImpl
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import org.arend.codeInsight.getOptimalImportStructure
import org.arend.codeInsight.processRedundantImportedDefinitions
import org.arend.codeInsight.importRemover
import org.arend.ext.module.LongName
import org.arend.intention.SplitAtomPatternIntention.Companion.doSubstituteUsages
import org.arend.naming.reference.ClassReferable
import org.arend.naming.reference.GlobalReferable
import org.arend.naming.scope.ClassFieldImplScope
import org.arend.psi.*
import org.arend.psi.ArendElementTypes.*
import org.arend.psi.ext.*
import org.arend.psi.ext.ArendGroup
import org.arend.psi.ext.ArendInternalReferable
import org.arend.quickfix.referenceResolve.ResolveReferenceAction
import org.arend.quickfix.referenceResolve.ResolveReferenceAction.Companion.getTargetName
import org.arend.refactoring.LocationData
import org.arend.refactoring.*
import org.arend.term.Fixity
import org.arend.term.concrete.Concrete
import org.arend.util.appExprToConcrete
import org.arend.util.findDefAndArgsInParsedBinop
import java.util.ArrayList
import java.util.Collections.singletonList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.List
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.any
import kotlin.collections.emptyList
import kotlin.collections.iterator
import kotlin.collections.lastOrNull
import kotlin.collections.map
import kotlin.collections.mapIndexed
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.toSet
import kotlin.collections.toTypedArray
import kotlin.collections.withIndex

class ArendMoveRefactoringProcessor(project: Project,
                                    private val myMoveCallback: () -> Unit,
                                    private var myMembers: List<ArendGroup>,
                                    private val mySourceContainer: ArendGroup,
                                    private val myTargetContainer: ArendGroup,
                                    private val insertIntoDynamicPart: Boolean,
                                    private val myOpenInEditor: Boolean,
                                    private val optimizeImportsAfterMove: Boolean = true) : BaseRefactoringProcessor(project, myMoveCallback) {
    private val myReferableDescriptors = ArrayList<LocationDescriptor>()

    override fun findUsages(): Array<UsageInfo> {
        val usagesList = ArrayList<UsageInfo>()
        val statCmdsToFix = HashMap<ArendStatCmd, PsiReference>()

        for (psiReference in ReferencesSearch.search(mySourceContainer)) {
            val statCmd = isStatCmdUsage(psiReference, true)
            if (statCmd is ArendStatCmd && psiReference.element.findNextSibling(DOT) !is ArendReferenceElement &&
                    myMembers.any { getImportedNames(statCmd, it.name).isNotEmpty() })
                statCmdsToFix[statCmd] = psiReference
        }

        for ((index, member) in myMembers.withIndex())
            for (entry in collectInternalReferablesWithSelf(member, index)) {
                myReferableDescriptors.add(entry.second)

                for (psiReference in ReferencesSearch.search(entry.first)) {
                    val referenceElement = psiReference.element
                    val referenceParent = referenceElement.parent
                    if (!isInMovedMember(psiReference.element)) {
                        val statCmd = isStatCmdUsage(psiReference, false)
                        val isUsageInHiding = referenceElement is ArendRefIdentifier && referenceParent is ArendStatCmd
                        if (statCmd == null || isUsageInHiding || !statCmdsToFix.contains(statCmd))
                            usagesList.add(ArendUsageInfo(psiReference, entry.second))
                    }
                }
            }
        //TODO: Somehow determine which of the statCmd usages are not relevant and filter them out

        for (statCmd in statCmdsToFix) usagesList.add(ArendStatCmdUsageInfo(statCmd.key, statCmd.value))

        var usageInfos = usagesList.toTypedArray()
        usageInfos = UsageViewUtil.removeDuplicatedUsages(usageInfos)
        return usageInfos
    }

    private fun collectInternalReferablesWithSelf(element: ArendGroup, index: Int): ArrayList<Pair<PsiLocatedReferable, LocationDescriptor>> {
        val result = ArrayList<Pair<PsiLocatedReferable, LocationDescriptor>>()
        result.add(Pair(element, LocationDescriptor(index, emptyList())))
        for (internalReferable in element.internalReferables)
            if (internalReferable.isVisible) {
                val path = ArrayList<Int>()
                var psi: PsiElement = internalReferable
                while (psi.parent != null && psi != element) {
                    val i = psi.parent.children.indexOf(psi)
                    path.add(0, i)
                    psi = psi.parent
                }
                result.add(Pair(internalReferable, LocationDescriptor(index, path)))
            }

        return result
    }

    private fun determineDynamicClassElements(defClass: ArendDefClass): Set<PsiLocatedReferable> {
        val result = HashSet<PsiLocatedReferable>()
        val isRecord = defClass.isRecord
        for (element in ClassFieldImplScope(defClass, true).elements) when (element) {
            is ArendDefClass -> if (isRecord || element.isRecord) result.addAll(element.dynamicSubgroups)
        }
        if (isRecord) result.addAll(defClass.dynamicSubgroups)
        return result
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        var insertAnchor: PsiElement?
        val psiFactory = ArendPsiFactory(myProject)
        val forceThisParameter = insertIntoDynamicPart && myTargetContainer is ArendDefClass && mySourceContainer is ArendDefClass &&
                !myTargetContainer.isSubClassOf(mySourceContainer)
        val sourceContainerIsARecord = (mySourceContainer as? ArendDefClass)?.isRecord == true

        insertAnchor = if (myTargetContainer is ArendFile) {
            myTargetContainer.lastChild //null means file is empty
        } else if (myTargetContainer is ArendDefClass && insertIntoDynamicPart) {
            if (myTargetContainer.lbrace == null && myTargetContainer.rbrace == null) surroundWithBraces(psiFactory, myTargetContainer)
            myTargetContainer.classStatList.lastOrNull() ?: myTargetContainer.lbrace!!
        } else {
            getAnchorInAssociatedModule(psiFactory, myTargetContainer)
        }

        //Memorize references in myMembers being moved
        val descriptorsOfAllMovedMembers = HashMap<PsiLocatedReferable, LocationDescriptor>() //This set may be strictly larger than the set of myReferableDescriptors
        val bodiesRefsFixData = HashMap<LocationDescriptor, TargetReference>()
        val bodiesClassFieldUsages = HashSet<LocationDescriptor>() //We don't need to keep a link to class field as we replace its usages by "this.field" anyway
        val recordOtherDynamicMembers = (mySourceContainer as? ArendDefClass)?.let { determineDynamicClassElements(it) }
                ?: emptySet()
        val memberReferences = HashSet<ArendReferenceElement>()

        run {
            val usagesInMovedBodies = HashMap<PsiLocatedReferable, MutableSet<LocationDescriptor>>()
            val targetReferences = HashMap<PsiLocatedReferable, TargetReference>()

            val recordFields = if (sourceContainerIsARecord) ClassFieldImplScope(mySourceContainer as ClassReferable, false).elements.filterIsInstanceTo(HashSet())
            else emptySet<PsiLocatedReferable>()

            for ((mIndex, m) in myMembers.withIndex())
                collectUsagesAndMembers(emptyList(), m, mIndex, recordFields, usagesInMovedBodies, descriptorsOfAllMovedMembers, memberReferences)

            for (referable in usagesInMovedBodies.keys.minus(recordFields))
                targetReferences[referable] = descriptorsOfAllMovedMembers[referable]?.let { DescriptorTargetReference(it) }
                        ?: ReferableTargetReference(referable)

            for (usagePack in usagesInMovedBodies) for (usage in usagePack.value) {
                val referable = usagePack.key
                if (recordFields.contains(referable))
                    bodiesClassFieldUsages.add(usage) else {
                    targetReferences[referable]?.let { bodiesRefsFixData[usage] = it }
                }
            }
        }

        // Determine which moved members should be added to the "remainder" namespace command
        val remainderReferables = HashSet<LocationDescriptor>()
        for (usage in usages) if (usage is ArendUsageInfo) {
            val referenceElement = usage.reference?.element
            if (referenceElement is ArendReferenceElement &&
                    mySourceContainer.textRange.contains(referenceElement.textOffset) && !memberReferences.contains(referenceElement)) { // Normal usage inside source container
                val member = referenceElement.reference?.resolve()
                val descriptor = (member as? PsiLocatedReferable)?.let{ descriptorsOfAllMovedMembers[it] }
                if (descriptor != null) remainderReferables.add(descriptor)
                if (member is ArendConstructor) {
                    val containingDataType = member.parentOfType<ArendDefData>()
                    val dataTypeDescriptor = containingDataType?.let { descriptorsOfAllMovedMembers[it] }
                    if (dataTypeDescriptor != null) remainderReferables.add(dataTypeDescriptor)
                }
            }
        }

        //Do move myMembers
        val holes = ArrayList<RelativePosition>()
        val newMemberList = ArrayList<ArendGroup>()
        val definitionsThatNeedThisParameter = ArrayList<PsiConcreteReferable>()
        val containingClass: ArendDefClass? = mySourceContainer.ancestors.filterIsInstance<ArendDefClass>().firstOrNull()

        for (member in myMembers) {
            val mStatementOrClassStat = member.parent
            val doc = (member as? ArendDefinition<*>)?.documentation
            val memberIsInDynamicPart = isInDynamicPart(mStatementOrClassStat) != null
            val docCopy = doc?.copy()

            val copyOfMemberStatement: PsiElement =
                    if (myTargetContainer is ArendDefClass && insertIntoDynamicPart) {
                        val mCopyClassStat = if (mStatementOrClassStat is ArendClassStat) mStatementOrClassStat.copy() else {
                            val classStatContainer = psiFactory.createClassStat()
                            classStatContainer.definition!!.replace(member.copy())
                            classStatContainer
                        }

                        insertAnchor!!.parent!!.addAfter(mCopyClassStat, insertAnchor)
                    } else {
                        val mCopyStatement = if (mStatementOrClassStat is ArendClassStat) {
                            val statementContainer = psiFactory.createFromText("\\func foo => {?}")?.descendantOfType<ArendStat>()
                            statementContainer!!.group!!.replace(member.copy())
                            statementContainer
                        } else mStatementOrClassStat.copy()

                        insertAnchor?.parent?.addAfter(mCopyStatement, insertAnchor)
                                ?: myTargetContainer.add(mCopyStatement)
                    }

            fun markGroupAsTheOneThatNeedsLeadingThisParameter(group: ArendGroup) {
                if (group is PsiConcreteReferable) definitionsThatNeedThisParameter.add(group)
                for (stat in group.statements) markGroupAsTheOneThatNeedsLeadingThisParameter(stat.group ?: continue)
            }

            if (memberIsInDynamicPart && copyOfMemberStatement is ArendStat) {
                copyOfMemberStatement.group?.let { markGroupAsTheOneThatNeedsLeadingThisParameter(it) }
            } else if (memberIsInDynamicPart && copyOfMemberStatement is ArendClassStat && forceThisParameter) {
                copyOfMemberStatement.group?.let { markGroupAsTheOneThatNeedsLeadingThisParameter(it) }
            }

            if (docCopy != null) {
                copyOfMemberStatement.parent?.addBefore(docCopy, copyOfMemberStatement)
            }

            val mCopy = copyOfMemberStatement.descendantOfType<ArendGroup>()!!
            newMemberList.add(mCopy)

            (doc?.prevSibling as? PsiWhiteSpace)?.delete()
            (mStatementOrClassStat.prevSibling as? PsiWhiteSpace)?.delete()
            mStatementOrClassStat.deleteAndGetPosition()?.let { if (!memberIsInDynamicPart) holes.add(it) }

            doc?.delete()
            insertAnchor = copyOfMemberStatement
        }

        var sourceContainerWhereBlockFreshlyCreated = false
        if (holes.isEmpty()) {
            sourceContainerWhereBlockFreshlyCreated = mySourceContainer.where == null
            val anchor = getAnchorInAssociatedModule(psiFactory, mySourceContainer)
            if (anchor != null) holes.add(RelativePosition(PositionKind.AFTER_ANCHOR, anchor))
        }

        myMembers = newMemberList

        //Create map from descriptors to actual psi elements of myMembers
        val movedReferablesMap = LinkedHashMap<LocationDescriptor, PsiLocatedReferable>()
        for (descriptor in myReferableDescriptors) (locateChild(descriptor) as? PsiLocatedReferable)?.let { movedReferablesMap[descriptor] = it }
        val movedReferablesNamesSet = movedReferablesMap.values.mapNotNull { it.name }.toSet()
        run {
            val referablesWithUsagesInSourceContainer = movedReferablesMap.values.intersect(remainderReferables.map { movedReferablesMap[it] }.toSet()).
                union(movedReferablesMap.values.filterIsInstance<ArendDefInstance>()).mapNotNull { it?.name }.toList()
            val movedReferablesUniqueNames = movedReferablesNamesSet.filter { name -> referablesWithUsagesInSourceContainer.filter { it == name }.size == 1 }
            val referablesWithUniqueNames = HashMap<String, PsiLocatedReferable>()
            for (entry in movedReferablesMap) {
                val name = entry.value.name
                if (name != null && movedReferablesUniqueNames.contains(name)) referablesWithUniqueNames[name] = entry.value
            }

            //Prepare the "remainder" namespace command (the one which is inserted in the place where one of the moved definitions was)
            if (holes.isNotEmpty()) {
                val uppermostHole = holes.asSequence().sorted().first()
                var remainderAnchor: PsiElement? = uppermostHole.anchor

                if (uppermostHole.kind != PositionKind.INSIDE_EMPTY_ANCHOR) {
                    val next = remainderAnchor?.rightSibling<ArendCompositeElement>()
                    val prev = remainderAnchor?.leftSibling<ArendCompositeElement>()
                    if (next != null) remainderAnchor = next else
                        if (prev != null) remainderAnchor = prev
                }

                while (remainderAnchor !is ArendCompositeElement && remainderAnchor != null) remainderAnchor = remainderAnchor.parent

                if (remainderAnchor is ArendCompositeElement) {
                    val sourceContainerFile = (mySourceContainer as PsiElement).containingFile as ArendFile
                    val targetLocation = LocationData(myTargetContainer as PsiLocatedReferable)
                    val importData = calculateReferenceName(targetLocation, sourceContainerFile, remainderAnchor)

                    if (importData != null && movedReferablesUniqueNames.isNotEmpty()) {
                        val importAction: NsCmdRefactoringAction? = importData.first
                        val openedName: List<String> = importData.second

                        importAction?.execute()
                        val renamings = movedReferablesUniqueNames.map { Pair(it, null as String?) } // filter this list
                        val groupMember = if (uppermostHole.kind == PositionKind.INSIDE_EMPTY_ANCHOR) {
                            if (remainderAnchor.children.isNotEmpty()) remainderAnchor.firstChild else null
                        } else remainderAnchor

                        val nsIds = addIdToUsing(groupMember, myTargetContainer, LongName(openedName).toString(), renamings, psiFactory, uppermostHole).first
                        for (nsId in nsIds) {
                            val target = nsId.refIdentifier.reference.resolve()
                            val name = nsId.refIdentifier.referenceName
                            if (target != referablesWithUniqueNames[name]) /* reference that we added to the namespace command is corrupt, so we need to remove it right after it was added */
                                doRemoveRefFromStatCmd(nsId.refIdentifier)
                        }
                    }
                }
            }

            val where = mySourceContainer.where
            if (where != null && where.statList.isEmpty() && (sourceContainerWhereBlockFreshlyCreated || where.lbrace == null))  where.delete()
        }

        //Fix usages of namespace commands
        for (usage in usages) if (usage is ArendStatCmdUsageInfo) {
            val statCmd = usage.command
            val statCmdStatement = statCmd.parent
            val usageFile = statCmd.containingFile as ArendFile
            val renamings = ArrayList<Pair<String, String?>>()
            val nsIdToRemove = HashSet<ArendNsId>()

            for (memberName in movedReferablesNamesSet) {
                val importedName = getImportedNames(statCmd, memberName)

                for (name in importedName) {
                    val newName = if (name.first == memberName) null else name.first
                    renamings.add(Pair(memberName, newName))
                    val nsId = name.second
                    if (nsId != null) nsIdToRemove.add(nsId)
                }
            }

            val importData = calculateReferenceName(LocationData(myTargetContainer as PsiLocatedReferable), usageFile, statCmd, myTargetContainer is ArendFile)
            val currentName: List<String>? = importData?.second

            if (renamings.isNotEmpty() && currentName != null) {
                importData.first?.execute()
                val name = when {
                    currentName.isNotEmpty() -> LongName(currentName).toString()
                    myTargetContainer is ArendFile -> myTargetContainer.moduleLocation?.modulePath?.lastName ?: ""
                    else -> ""
                }
                addIdToUsing(statCmdStatement, myTargetContainer, name, renamings, psiFactory, RelativePosition(PositionKind.AFTER_ANCHOR, statCmdStatement))
            }

            for (nsId in nsIdToRemove) doRemoveRefFromStatCmd(nsId.refIdentifier)
        }

        //Now fix references of "normal" usages
        for (usage in usages) if (usage is ArendUsageInfo) {
            val referenceElement = usage.reference?.element
            val referenceParent = referenceElement?.parent

            if (referenceElement is ArendRefIdentifier && referenceParent is ArendStatCmd) //Usage in "hiding" list which we simply delete
                doRemoveRefFromStatCmd(referenceElement)
            else if (referenceElement is ArendReferenceElement) //Normal usage which we try to fix
                movedReferablesMap[usage.referableDescriptor]?.let { ResolveReferenceAction.getProposedFix(it, referenceElement)?.execute(null) }
        }

        //Fix references in the elements that have been moved
        for ((mIndex, m) in myMembers.withIndex()) restoreReferences(emptyList(), m, mIndex, bodiesRefsFixData)

        //Prepare a map which would allow us to fix class field usages on the next step (this step is needed only when we are moving definitions out of a record)
        val fieldsUsagesFixMap = HashMap<PsiConcreteReferable, HashSet<ArendReferenceElement>>()
        for (usage in bodiesClassFieldUsages) {
            val element = locateChild(usage)
            if (element is ArendReferenceElement) {
                val ancestor = element.ancestor<PsiConcreteReferable>()
                if (ancestor != null && definitionsThatNeedThisParameter.contains(ancestor)) {
                    val set = fieldsUsagesFixMap[ancestor] ?: HashSet()
                    fieldsUsagesFixMap[ancestor] = set
                    set.add(element)
                }
            }
        }

        if (sourceContainerIsARecord) for (dynamicSubgroup in (mySourceContainer as ArendDefClass).dynamicSubgroups) {
            modifyRecordDynamicDefCalls(dynamicSubgroup, definitionsThatNeedThisParameter.toSet(), psiFactory, "\\this")
        }

        if (forceThisParameter) for (dynamicSubgroup in (mySourceContainer as ArendDefClass).dynamicSubgroups) {
            modifyRecordDynamicDefCalls(dynamicSubgroup, definitionsThatNeedThisParameter.toSet(), psiFactory, "_", true)
        }

        if (forceThisParameter) for (dynamicSubgroup in (myTargetContainer as ArendDefClass).dynamicSubgroups) {
            modifyRecordDynamicDefCalls(dynamicSubgroup, definitionsThatNeedThisParameter.toSet(), psiFactory, "\\this", true)
        }


        //Add "this" parameters/arguments to definitions moved out of the class + definitions inside it
        if (containingClass is ArendDefClass) for (definition in definitionsThatNeedThisParameter) if (definition is ArendFunctionDefinition<*> || definition is ArendDefData) {
            val className = getTargetName(containingClass, definition).let { if (it.isNullOrEmpty()) containingClass.defIdentifier?.textRepresentation() else it }
            if (className != null) {
                val thisVarName = addImplicitClassDependency(psiFactory, definition, className)
                val classifyingField = getClassifyingField(containingClass)

                fun doSubstituteThisKwWithThisVar(psi: PsiElement) {
                    if (psi is ArendWhere) return
                    if (psi is ArendAtom && psi.thisKw != null) {
                        val literal = psiFactory.createExpression(thisVarName).descendantOfType<ArendAtom>()!!
                        psi.replace(literal)
                    } else for (c in psi.children) doSubstituteThisKwWithThisVar(c)
                }

                doSubstituteThisKwWithThisVar(definition)
                if (classifyingField != null) doSubstituteUsages(classifyingField.project, classifyingField, definition, thisVarName)

                val recordFieldsUsagesToFix = fieldsUsagesFixMap[definition]
                if (recordFieldsUsagesToFix != null) for (refElement in recordFieldsUsagesToFix)
                    RenameReferenceAction(refElement, listOf(thisVarName, refElement.referenceName)).execute(null)

                modifyRecordDynamicDefCalls(definition, recordOtherDynamicMembers, psiFactory, thisVarName)
            }

        }

        if (optimizeImportsAfterMove) {
            val optimalStructure = getOptimalImportStructure(mySourceContainer)
            val (fileImports, optimalTree, _) = optimalStructure
            processRedundantImportedDefinitions(mySourceContainer, fileImports, optimalTree, importRemover)
        }

        myMoveCallback.invoke()

        if (myOpenInEditor && myMembers.isNotEmpty()) {
            val item = myMembers.first()
            if (item.isValid) EditorHelper.openInEditor(item)
        }
    }

    private fun modifyRecordDynamicDefCalls(psiElementToModify: PsiElement,
                                            dynamicMembers: Set<PsiLocatedReferable>,
                                            psiFactory: ArendPsiFactory,
                                            argument: String,
                                            proceedEvenIfThereIsAnImplicitFirstParameter: Boolean = false) {
        fun addImplicitFirstArgument(literal: ArendLiteral, fieldsSuffixes: List<PsiLocatedReferable>) {
            fun doAddImplicitFirstArgument(argumentOrFieldsAcc: PsiElement) {
                var localArgumentAppExpr = argumentOrFieldsAcc.parent as ArendArgumentAppExpr
                var localLiteral = literal
                var localArgumentOrFieldsAcc = argumentOrFieldsAcc
                val atomFieldsAcc = if (argumentOrFieldsAcc is ArendAtomArgument) argumentOrFieldsAcc.atomFieldsAcc else argumentOrFieldsAcc as? ArendAtomFieldsAcc
                var needsBeingSurroundedByParentheses = atomFieldsAcc != null && atomFieldsAcc.numberList.isNotEmpty() || fieldsSuffixes.isNotEmpty() && argumentOrFieldsAcc is ArendAtomArgument
                while ((surroundingTupleExpr(localArgumentAppExpr)?.parent as? ArendTuple)?.let { tuple ->
                            tuple.tupleExprList.size == 1 && tuple.tupleExprList.first().colon == null
                        } == true) {
                    val tuple = localArgumentAppExpr.parent.parent.parent
                    if ((tuple.parent as? ArendAtom)?.let {
                                (it.parent as? ArendAtomFieldsAcc)?.let { atomFieldsAcc ->
                                    atomFieldsAcc.numberList.isEmpty() && (atomFieldsAcc.parent is ArendArgumentAppExpr)
                                }
                            } == true) {
                        localArgumentAppExpr = tuple.parent.parent.parent as ArendArgumentAppExpr
                        needsBeingSurroundedByParentheses = false
                    }
                    else break
                }

                if (needsBeingSurroundedByParentheses) {
                    val tupleExpr = psiFactory.createExpression("(${localLiteral.text})").descendantOfType<ArendTuple>()
                    val replacedExpr = if (tupleExpr != null) localLiteral.replace(tupleExpr) else null
                    if (replacedExpr != null)  {
                        val newArgumentAppExpr = replacedExpr.descendantOfType<ArendArgumentAppExpr>()
                        if (newArgumentAppExpr != null) localArgumentAppExpr = newArgumentAppExpr
                        localLiteral = localArgumentAppExpr.atomFieldsAcc?.atom?.literal ?: localLiteral
                        localArgumentOrFieldsAcc = localArgumentAppExpr.atomFieldsAcc ?: localArgumentOrFieldsAcc
                    }
                }

                if (fieldsSuffixes.isNotEmpty()) {
                    var template = "{?}"
                    val renamer = PsiLocatedRenamer(localLiteral)
                    for (suffix in fieldsSuffixes) template = "${renamer.renameDefinition(suffix).toString()} {$template}"
                    renamer.writeAllImportCommands()
                    val newAppExpr = psiFactory.createExpression(template).descendantOfType<ArendArgumentAppExpr>()
                    val literalCopy = localLiteral.copy()
                    val currentAtomFieldsAcc = localArgumentAppExpr.atomFieldsAcc
                    if (currentAtomFieldsAcc != null && newAppExpr != null) {
                        val insertedAtomFieldsAcc = currentAtomFieldsAcc.replace(newAppExpr.atomFieldsAcc!!)
                        val insertedImplicitArgument = localArgumentAppExpr.addAfter(newAppExpr.argumentList.first(), insertedAtomFieldsAcc)
                        localArgumentAppExpr.addAfter(psiFactory.createWhitespace(" "), insertedAtomFieldsAcc)
                        val goalLiteral = insertedImplicitArgument.descendantOfType<ArendGoal>()!!.parent as ArendLiteral
                        localLiteral = goalLiteral.replace(literalCopy) as ArendLiteral
                        localArgumentOrFieldsAcc = localLiteral.parent.parent
                        localArgumentAppExpr = localArgumentOrFieldsAcc.parent as ArendArgumentAppExpr
                     }
                }

                val cExpr = appExprToConcrete(localArgumentAppExpr)
                val defArgsData = if (cExpr != null) findDefAndArgsInParsedBinop(localLiteral, cExpr) else null
                if (defArgsData != null && (defArgsData.argumentsConcrete.isEmpty() || defArgsData.argumentsConcrete.first().isExplicit || proceedEvenIfThereIsAnImplicitFirstParameter)) {
                    val ipName = defArgsData.functionReferenceContainer as? ArendIPName
                    if (ipName != null) when (ipName.fixity) {
                        Fixity.INFIX -> addImplicitArgAfter(psiFactory, localArgumentOrFieldsAcc, argument, true)
                        Fixity.POSTFIX -> {
                            val operatorConcrete = defArgsData.operatorConcrete.let { if (it is Concrete.LamExpression) it.body else it }
                            val transformedAtomFieldsAcc = transformPostfixToPrefix(psiFactory, localArgumentOrFieldsAcc, ipName, operatorConcrete)?.atomFieldsAcc
                            if (transformedAtomFieldsAcc != null) addImplicitArgAfter(psiFactory, transformedAtomFieldsAcc, argument, true)
                        }
                        else -> {}
                    } else {
                        val prec = getPrec(localLiteral.longName?.refIdentifierList?.lastOrNull()?.reference?.resolve())
                        val infixMode = localLiteral.ipName?.infix != null || prec != null && isInfix(prec)
                        addImplicitArgAfter(psiFactory, localArgumentOrFieldsAcc, argument, infixMode)
                    }
                }
             }

            val parent = literal.parent
            val grandparent = parent.parent
            if (parent is ArendAtom && grandparent is ArendAtomFieldsAcc) {
                val greatGrandParent = grandparent.parent
                if (greatGrandParent is ArendArgumentAppExpr) {
                    doAddImplicitFirstArgument(grandparent)
                } else if (greatGrandParent is ArendAtomArgument) {
                    val greatGreatGrandParent = greatGrandParent.parent
                    if (greatGreatGrandParent is ArendArgumentAppExpr) doAddImplicitFirstArgument(greatGrandParent)
                }
            } else if (parent is ArendTypeTele) {
                val newTypeTele = psiFactory.createExpression("\\Pi (${literal.text} {$argument}) -> \\Set").descendantOfType<ArendTypeTele>()
                if (newTypeTele != null) parent.replace(newTypeTele)
            }
        }

        for (child in psiElementToModify.children) {
            modifyRecordDynamicDefCalls(child, dynamicMembers, psiFactory, argument, proceedEvenIfThereIsAnImplicitFirstParameter)
            if (child is ArendLiteral) {
                val longName = child.longName
                val refIdentifierList = longName?.refIdentifierList
                val firstDynamicMember = refIdentifierList?.withIndex()?.firstOrNull{ dynamicMembers.contains(it.value.resolve) }
                val target = child.ipName?.resolve.let{ if (dynamicMembers.contains(it)) it else null } ?: firstDynamicMember?.value?.resolve
                val suffixTargets = ArrayList<PsiLocatedReferable>()
                if (firstDynamicMember != null) {
                    val suffix = refIdentifierList.drop(firstDynamicMember.index + 1)
                    if (suffix.isNotEmpty()) {
                        val suffixElements = suffix.map { it.resolve }
                        if (suffixElements.all { it is PsiLocatedReferable }) {
                            suffixTargets.addAll(suffixElements.filterIsInstance<PsiLocatedReferable>())
                            longName.deleteChildRange(firstDynamicMember.value.nextSibling, suffix.last())
                        }
                    }
                }
                if (target != null) addImplicitFirstArgument(child, suffixTargets)
            }
        }
    }

    private fun locateChild(element: PsiElement, childPath: List<Int>): PsiElement? {
        return if (childPath.isEmpty()) element else {
            val shorterPrefix = childPath.subList(1, childPath.size)
            val childElement = element.children[childPath[0]]
            locateChild(childElement, shorterPrefix)
        }
    }

    private fun locateChild(descriptor: LocationDescriptor): PsiElement? {
        val num = descriptor.groupNumber
        val group = if (num < myMembers.size) myMembers[num] else null
        return if (group != null) locateChild(group, descriptor.childPath) else null
    }

    private fun collectUsagesAndMembers(prefix: List<Int>, element: PsiElement, groupNumber: Int,
                                        recordFields: Set<PsiLocatedReferable>,
                                        usagesData: MutableMap<PsiLocatedReferable, MutableSet<LocationDescriptor>>,
                                        memberData: MutableMap<PsiLocatedReferable, LocationDescriptor>,
                                        memberReferences: MutableSet<ArendReferenceElement>) {
        when (element) {
            is ArendFieldDefIdentifier -> memberData[element] = LocationDescriptor(groupNumber, prefix)
            is ArendLongName -> {
                val reference = computeReferenceToBeFixed(element, recordFields)
                if (reference != null) collectUsagesAndMembers(prefix + singletonList(reference.index), reference.value, groupNumber, recordFields, usagesData, memberData, memberReferences)
            }
            is ArendReferenceElement ->
                if (element !is ArendDefIdentifier) element.reference?.resolve().let {
                    if (it is PsiLocatedReferable) {
                        var set = usagesData[it]
                        if (set == null) {
                            set = HashSet()
                            usagesData[it] = set
                        }
                        set.add(LocationDescriptor(groupNumber, prefix))
                        memberReferences.add(element)
                    }
                }
            else -> {
                if (element is PsiLocatedReferable) memberData[element] = LocationDescriptor(groupNumber, prefix)
                element.children.mapIndexed { i, e -> collectUsagesAndMembers(prefix + singletonList(i), e, groupNumber, recordFields, usagesData, memberData, memberReferences) }
            }
        }
    }

    private fun computeReferenceToBeFixed(element: ArendLongName, recordFields: Set<PsiLocatedReferable>): IndexedValue<PsiElement>? {
        val references = element.children.withIndex().filter { (_, m) -> m is ArendReferenceElement }
        var classReference = true
        var prevTarget: PsiElement? = null
        for (ref in references) { // This piece of code works nontrivially only when we are moving static definitions out of a record
            val refElement = ref.value
            val target = refElement.reference?.resolve()
            if (target is ArendDefClass) {
                classReference = true
                prevTarget = target
                continue
            }
            if (recordFields.contains(target)) {
                return if (classReference) ref else
                    null //Prevents the default mechanism of links repairing from being engaged on a longName which includes reference to a non-local classfield (it may break the reference by writing unnecessary "this" before it)
            }
            if (prevTarget is ArendGroup && (target is ArendInternalReferable && !prevTarget.internalReferables.contains(target))) { // this indicates that prevTarget is a class/record instance, so we only need to fix the link to "prevTarget" (and not to the instance member)
                return references[references.indexOf(ref) - 1]
            }
            classReference = false
            prevTarget = target
        }

        return references.lastOrNull() //this is the default behavior
    }

    private fun restoreReferences(prefix: List<Int>, element: PsiElement, groupIndex: Int,
                                  fixMap: HashMap<LocationDescriptor, TargetReference>) {
        if (element is ArendReferenceElement && element !is ArendDefIdentifier) {
            val descriptor = LocationDescriptor(groupIndex, prefix)
            val correctTarget = fixMap[descriptor]?.resolve()
            if (correctTarget != null && correctTarget !is ArendFile) {
                ResolveReferenceAction.getProposedFix(correctTarget, element)?.execute(null)
            }
        } else element.children.mapIndexed { i, e -> restoreReferences(prefix + singletonList(i), e, groupIndex, fixMap) }
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        val usages = refUsages.get()

        if (mySourceContainer != myTargetContainer) {
            val localGroup = HashSet(myTargetContainer.statements.mapNotNull { it.group })
            localGroup.addAll(myTargetContainer.dynamicSubgroups)

            val localNamesMap = HashMap<String, ArendGroup>()
            for (psi in localGroup) {
                localNamesMap[psi.textRepresentation()] = psi
                if (psi is GlobalReferable) {
                    val aliasName = psi.aliasName
                    if (aliasName != null) localNamesMap[aliasName] = psi
                }
            }

            for (member in myMembers) {
                val text = member.textRepresentation()
                val psi = localNamesMap[text]
                if (psi != null) conflicts.put(psi, singletonList("Name clash with one of the members of the target module ($text)"))
            }
        }

        return showConflicts(conflicts, usages)
    }

    override fun getCommandName(): String = MoveMembersImpl.getRefactoringName()

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor =
            MoveMemberViewDescriptor(PsiUtilCore.toPsiElementArray(myMembers.map { it }))

    private fun isInMovedMember(element: PsiElement): Boolean = myMembers.any { PsiTreeUtil.isAncestor(it, element, false) }

    private fun isStatCmdUsage(reference: PsiReference, insideLongNameOnly: Boolean): ArendStatCmd? {
        val parent = reference.element.parent
        if (parent is ArendStatCmd && !insideLongNameOnly) return parent
        if (parent is ArendLongName) {
            val grandparent = parent.parent
            if (grandparent is ArendStatCmd) return grandparent
        }
        return null
    }

    data class LocationDescriptor(val groupNumber: Int, val childPath: List<Int>)

    class ArendUsageInfo(reference: PsiReference, val referableDescriptor: LocationDescriptor) : UsageInfo(reference)

    class ArendStatCmdUsageInfo(val command: ArendStatCmd, reference: PsiReference) : UsageInfo(reference)

    private interface TargetReference {
        fun resolve(): PsiLocatedReferable?
    }

    private class ReferableTargetReference(private val myReferable: PsiLocatedReferable) : TargetReference {
        override fun resolve() = myReferable
    }

    private inner class DescriptorTargetReference(val myDescriptor: LocationDescriptor) : TargetReference {
        private var myCachedResult: PsiLocatedReferable? = null

        override fun resolve(): PsiLocatedReferable? {
            if (myCachedResult != null) return myCachedResult
            myCachedResult = locateChild(myDescriptor) as? PsiLocatedReferable
            return myCachedResult
        }
    }
}
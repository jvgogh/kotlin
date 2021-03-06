/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirImportImpl
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedImportImpl
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.constructClassType
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculator
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculatorWithJump
import org.jetbrains.kotlin.fir.resolve.transformers.firUnsafe
import org.jetbrains.kotlin.fir.scopes.FirPosition
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.impl.FirExplicitSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.processClassifiersByNameWithAction
import org.jetbrains.kotlin.fir.service
import org.jetbrains.kotlin.fir.symbols.*
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintStorage
import org.jetbrains.kotlin.resolve.calls.inference.model.SimpleConstraintSystemConstraintPosition
import org.jetbrains.kotlin.resolve.calls.model.PostponedResolvedAtomMarker
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.utils.addToStdlib.cast
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted

class CallInfo(
    val callKind: CallKind,

    val explicitReceiver: FirExpression?,
    val arguments: List<FirExpression>,
    val isSafeCall: Boolean,

    val typeArguments: List<FirTypeProjection>,
    val session: FirSession,
    val containingFile: FirFile,
    val container: FirDeclaration,
    val typeProvider: (FirExpression) -> FirTypeRef?
) {
    val argumentCount get() = arguments.size
}

interface CheckerSink {
    fun reportApplicability(new: CandidateApplicability)
    suspend fun yield()
    suspend fun yieldApplicability(new: CandidateApplicability) {
        reportApplicability(new)
        yield()
    }

    val components: InferenceComponents

    suspend fun yieldIfNeed()
}


class CheckerSinkImpl(override val components: InferenceComponents, var continuation: Continuation<Unit>? = null) : CheckerSink {
    var current = CandidateApplicability.RESOLVED
    override fun reportApplicability(new: CandidateApplicability) {
        if (new < current) current = new
    }

    override suspend fun yield() = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
        continuation = it
        kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
    }

    override suspend fun yieldIfNeed() {
        if (current < CandidateApplicability.SYNTHETIC_RESOLVED) {
            yield()
        }
    }
}


class Candidate(
    val symbol: ConeSymbol,
    val dispatchReceiverValue: ClassDispatchReceiverValue?,
    val implicitExtensionReceiverValue: ImplicitReceiverValue?,
    val explicitReceiverKind: ExplicitReceiverKind,
    private val inferenceComponents: InferenceComponents,
    private val baseSystem: ConstraintStorage
) {
    val system by lazy {
        val system = inferenceComponents.createConstraintSystem()
        system.addOtherSystem(baseSystem)
        system
    }
    lateinit var substitutor: ConeSubstitutor

    var argumentMapping: Map<FirExpression, FirValueParameter>? = null
    val postponedAtoms = mutableListOf<PostponedResolvedAtomMarker>()
}

sealed class CallKind {
    abstract fun sequence(): List<ResolutionStage>

    object Function : CallKind() {
        override fun sequence(): List<ResolutionStage> {
            return functionCallResolutionSequence()
        }
    }

    object VariableAccess : CallKind() {
        override fun sequence(): List<ResolutionStage> {
            return qualifiedAccessResolutionSequence()
        }
    }
}


enum class TowerDataKind {
    EMPTY,
    TOWER_LEVEL
}

interface TowerScopeLevel {

    sealed class Token<out T : ConeSymbol> {
        object Properties : Token<ConePropertySymbol>()

        object Functions : Token<ConeFunctionSymbol>()
        object Objects : Token<ConeClassifierSymbol>()
    }

    fun <T : ConeSymbol> processElementsByName(
        token: Token<T>,
        name: Name,
        explicitReceiver: ExpressionReceiverValue?,
        processor: TowerScopeLevelProcessor<T>
    ): ProcessorAction

    interface TowerScopeLevelProcessor<T : ConeSymbol> {
        fun consumeCandidate(
            symbol: T,
            dispatchReceiverValue: ClassDispatchReceiverValue?,
            implicitExtensionReceiverValue: ImplicitReceiverValue?
        ): ProcessorAction
    }

    object Empty : TowerScopeLevel {
        override fun <T : ConeSymbol> processElementsByName(
            token: Token<T>,
            name: Name,
            explicitReceiver: ExpressionReceiverValue?,
            processor: TowerScopeLevelProcessor<T>
        ): ProcessorAction = ProcessorAction.NEXT
    }
}

abstract class SessionBasedTowerLevel(val session: FirSession) : TowerScopeLevel {
    protected fun ConeSymbol.dispatchReceiverValue(): ClassDispatchReceiverValue? {
        return when (this) {
            is FirFunctionSymbol -> fir.dispatchReceiverValue(session)
            is FirClassSymbol -> ClassDispatchReceiverValue(fir.symbol)
            else -> null
        }
    }

    protected fun ConeCallableSymbol.hasConsistentExtensionReceiver(extensionReceiver: ReceiverValue?): Boolean {
        val hasExtensionReceiver = hasExtensionReceiver()
        return hasExtensionReceiver == (extensionReceiver != null)
    }
}

// This is more like "dispatch receiver-based tower level"
// Here we always have an explicit or implicit dispatch receiver, and can access members of its scope
// (which is separated from currently accessible scope, see below)
// So: dispatch receiver = given explicit or implicit receiver (always present)
// So: extension receiver = either none, if dispatch receiver = explicit receiver,
//     or given implicit or explicit receiver, otherwise
class MemberScopeTowerLevel(
    session: FirSession,
    val dispatchReceiver: ReceiverValue,
    val implicitExtensionReceiver: ImplicitReceiverValue? = null
) : SessionBasedTowerLevel(session) {

    private fun <T : ConeSymbol> processMembers(
        output: TowerScopeLevel.TowerScopeLevelProcessor<T>,
        explicitExtensionReceiver: ExpressionReceiverValue?,
        processScopeMembers: FirScope.(processor: (T) -> ProcessorAction) -> ProcessorAction
    ): ProcessorAction {
        if (implicitExtensionReceiver != null && explicitExtensionReceiver != null) return ProcessorAction.NEXT
        val extensionReceiver = implicitExtensionReceiver ?: explicitExtensionReceiver
        val scope = dispatchReceiver.type.scope(session, ScopeSession()) ?: return ProcessorAction.NEXT
        if (scope.processScopeMembers { candidate ->
                if (candidate is ConeCallableSymbol && candidate.hasConsistentExtensionReceiver(extensionReceiver)) {
                    // NB: we do not check dispatchReceiverValue != null here,
                    // because of objects & constructors (see comments in dispatchReceiverValue() implementation)
                    output.consumeCandidate(candidate, candidate.dispatchReceiverValue(), implicitExtensionReceiver)
                } else if (candidate is ConeClassLikeSymbol) {
                    output.consumeCandidate(candidate, null, implicitExtensionReceiver)
                } else {
                    ProcessorAction.NEXT
                }
            }.stop()
        ) return ProcessorAction.STOP
        val withSynthetic = FirSyntheticPropertiesScope(session, scope, ReturnTypeCalculatorWithJump(session))
        return withSynthetic.processScopeMembers { symbol ->
            output.consumeCandidate(symbol, symbol.dispatchReceiverValue(), implicitExtensionReceiver)
        }
    }

    override fun <T : ConeSymbol> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        explicitReceiver: ExpressionReceiverValue?,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        val explicitExtensionReceiver = if (dispatchReceiver == explicitReceiver) null else explicitReceiver
        return when (token) {
            TowerScopeLevel.Token.Properties -> processMembers(processor, explicitExtensionReceiver) { symbol ->
                this.processPropertiesByName(name, symbol.cast())
            }
            TowerScopeLevel.Token.Functions -> processMembers(processor, explicitExtensionReceiver) { symbol ->
                this.processFunctionsByName(name, symbol.cast())
            }
            TowerScopeLevel.Token.Objects -> processMembers(processor, explicitExtensionReceiver) { symbol ->
                this.processClassifiersByNameWithAction(name, FirPosition.OTHER, symbol.cast())
            }
        }
    }

}

private fun ConeCallableSymbol.hasExtensionReceiver(): Boolean = (this as? FirCallableSymbol)?.fir?.receiverTypeRef != null

// This is more like "scope-based tower level"
// We can access here members of currently accessible scope which is not influenced by explicit receiver
// We can either have no explicit receiver at all, or it can be an extension receiver
// An explicit receiver never can be a dispatch receiver at this level
// So: dispatch receiver = strictly NONE
// So: extension receiver = either none or explicit
// (if explicit receiver exists, it always *should* be an extension receiver)
class ScopeTowerLevel(
    session: FirSession,
    val scope: FirScope,
    val implicitExtensionReceiver: ImplicitReceiverValue? = null
) : SessionBasedTowerLevel(session) {
    override fun <T : ConeSymbol> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        explicitReceiver: ExpressionReceiverValue?,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        if (explicitReceiver != null && implicitExtensionReceiver != null) {
            return ProcessorAction.NEXT
        }
        val extensionReceiver = explicitReceiver ?: implicitExtensionReceiver
        return when (token) {

            TowerScopeLevel.Token.Properties -> scope.processPropertiesByName(name) { candidate ->
                if (candidate.hasConsistentExtensionReceiver(extensionReceiver) && candidate.dispatchReceiverValue() == null) {
                    processor.consumeCandidate(
                        candidate as T, dispatchReceiverValue = null,
                        implicitExtensionReceiverValue = implicitExtensionReceiver
                    )
                } else {
                    ProcessorAction.NEXT
                }
            }
            TowerScopeLevel.Token.Functions -> scope.processFunctionsByName(name) { candidate ->
                if (candidate.hasConsistentExtensionReceiver(extensionReceiver) && candidate.dispatchReceiverValue() == null) {
                    processor.consumeCandidate(
                        candidate as T, dispatchReceiverValue = null,
                        implicitExtensionReceiverValue = implicitExtensionReceiver)
                } else {
                    ProcessorAction.NEXT
                }
            }
            TowerScopeLevel.Token.Objects -> scope.processClassifiersByNameWithAction(name, FirPosition.OTHER) {
                processor.consumeCandidate(
                    it as T, dispatchReceiverValue = null,
                    implicitExtensionReceiverValue = null
                )
            }
        }
    }

}

/**
 *  Handles only statics and top-levels, DOES NOT handle objects/companions members
 */
class QualifiedReceiverTowerLevel(session: FirSession) : SessionBasedTowerLevel(session) {
    override fun <T : ConeSymbol> processElementsByName(
        token: TowerScopeLevel.Token<T>,
        name: Name,
        explicitReceiver: ExpressionReceiverValue?,
        processor: TowerScopeLevel.TowerScopeLevelProcessor<T>
    ): ProcessorAction {
        val qualifiedReceiver = explicitReceiver?.explicitReceiverExpression as FirResolvedQualifier
        val scope = FirExplicitSimpleImportingScope(
            listOf(
                FirResolvedImportImpl(
                    session,
                    FirImportImpl(session, null, FqName.topLevel(name), false, null),
                    qualifiedReceiver.packageFqName,
                    qualifiedReceiver.relativeClassFqName
                )
            ), session
        )

        return if (token == TowerScopeLevel.Token.Objects) {
            scope.processClassifiersByNameWithAction(name, FirPosition.OTHER) {
                processor.consumeCandidate(it as T, null, null)
            }
        } else {
            scope.processCallables(name, token.cast()) {
                val fir = it.firUnsafe<FirCallableMemberDeclaration>()
                if (fir.isStatic || it.callableId.classId == null) {
                    processor.consumeCandidate(it as T, null, null)
                } else {
                    ProcessorAction.NEXT
                }
            }
        }
    }

}

class QualifiedReceiverTowerDataConsumer<T : ConeSymbol>(
    val session: FirSession,
    val name: Name,
    val token: TowerScopeLevel.Token<T>,
    val explicitReceiver: ExpressionReceiverValue,
    val candidateFactory: CandidateFactory
) : TowerDataConsumer() {
    override fun consume(
        kind: TowerDataKind,
        towerScopeLevel: TowerScopeLevel,
        resultCollector: CandidateCollector,
        group: Int
    ): ProcessorAction {
        if (checkSkip(group, resultCollector)) return ProcessorAction.NEXT
        if (kind != TowerDataKind.EMPTY) return ProcessorAction.NEXT

        return QualifiedReceiverTowerLevel(session).processElementsByName(
            token,
            name,
            explicitReceiver,
            processor = object : TowerScopeLevel.TowerScopeLevelProcessor<T> {
                override fun consumeCandidate(
                    symbol: T,
                    dispatchReceiverValue: ClassDispatchReceiverValue?,
                    implicitExtensionReceiverValue: ImplicitReceiverValue?
                ): ProcessorAction {
                    assert(dispatchReceiverValue == null)
                    resultCollector.consumeCandidate(
                        group,
                        candidateFactory.createCandidate(
                            symbol,
                            dispatchReceiverValue = null,
                            implicitExtensionReceiverValue = null,
                            explicitReceiverKind = ExplicitReceiverKind.NO_EXPLICIT_RECEIVER
                        )
                    )
                    return ProcessorAction.NEXT
                }
            }
        )
    }
}


abstract class TowerDataConsumer {
    abstract fun consume(
        kind: TowerDataKind,
        towerScopeLevel: TowerScopeLevel,
        resultCollector: CandidateCollector,
        group: Int
    ): ProcessorAction

    private var stopGroup = Int.MAX_VALUE
    fun checkSkip(group: Int, resultCollector: CandidateCollector): Boolean {
        if (resultCollector.isSuccess() && stopGroup == Int.MAX_VALUE) {
            stopGroup = group
        }
        return group > stopGroup
    }
}


fun createVariableAndObjectConsumer(
    session: FirSession,
    name: Name,
    callInfo: CallInfo,
    inferenceComponents: InferenceComponents
): TowerDataConsumer {
    return PrioritizedTowerDataConsumer(
        createSimpleConsumer(
            session,
            name,
            TowerScopeLevel.Token.Properties,
            callInfo,
            inferenceComponents
        ),
        createSimpleConsumer(
            session,
            name,
            TowerScopeLevel.Token.Objects,
            callInfo,
            inferenceComponents
        )
    )

}

fun createFunctionConsumer(
    session: FirSession,
    name: Name,
    callInfo: CallInfo,
    inferenceComponents: InferenceComponents
): TowerDataConsumer {
    return createSimpleConsumer(
        session,
        name,
        TowerScopeLevel.Token.Functions,
        callInfo,
        inferenceComponents
    )
}


fun createSimpleConsumer(
    session: FirSession,
    name: Name,
    token: TowerScopeLevel.Token<*>,
    callInfo: CallInfo,
    inferenceComponents: InferenceComponents
): TowerDataConsumer {
    val factory = CandidateFactory(inferenceComponents, callInfo)
    val explicitReceiver = callInfo.explicitReceiver
    return if (explicitReceiver != null) {
        val receiverValue = ExpressionReceiverValue(explicitReceiver, callInfo.typeProvider)
        if (explicitReceiver is FirResolvedQualifier) {
            val qualified =
                QualifiedReceiverTowerDataConsumer(session, name, token, receiverValue, factory)

            if (explicitReceiver.classId != null) {
                PrioritizedTowerDataConsumer(
                    qualified,
                    ExplicitReceiverTowerDataConsumer(session, name, token, receiverValue, factory)
                )
            } else {
                qualified
            }

        } else {
            ExplicitReceiverTowerDataConsumer(session, name, token, receiverValue, factory)
        }
    } else {
        NoExplicitReceiverTowerDataConsumer(session, name, token, factory)
    }
}


class PrioritizedTowerDataConsumer(
    vararg val consumers: TowerDataConsumer
) : TowerDataConsumer() {

    override fun consume(
        kind: TowerDataKind,
        towerScopeLevel: TowerScopeLevel,
        resultCollector: CandidateCollector,
        group: Int
    ): ProcessorAction {
        if (checkSkip(group, resultCollector)) return ProcessorAction.NEXT
        for ((index, consumer) in consumers.withIndex()) {
            val action = consumer.consume(kind, towerScopeLevel, resultCollector, group * consumers.size + index)
            if (action.stop()) {
                return ProcessorAction.STOP
            }
        }
        return ProcessorAction.NEXT
    }
}

class ExplicitReceiverTowerDataConsumer<T : ConeSymbol>(
    val session: FirSession,
    val name: Name,
    val token: TowerScopeLevel.Token<T>,
    val explicitReceiver: ExpressionReceiverValue,
    val candidateFactory: CandidateFactory
) : TowerDataConsumer() {

    companion object {
        val defaultPackage = Name.identifier("kotlin")
    }


    override fun consume(
        kind: TowerDataKind,
        towerScopeLevel: TowerScopeLevel,
        resultCollector: CandidateCollector,
        group: Int
    ): ProcessorAction {
        if (checkSkip(group, resultCollector)) return ProcessorAction.NEXT
        return when (kind) {
            TowerDataKind.EMPTY ->
                MemberScopeTowerLevel(session, explicitReceiver).processElementsByName(
                    token,
                    name,
                    explicitReceiver = null,
                    processor = object : TowerScopeLevel.TowerScopeLevelProcessor<T> {
                        override fun consumeCandidate(
                            symbol: T,
                            dispatchReceiverValue: ClassDispatchReceiverValue?,
                            implicitExtensionReceiverValue: ImplicitReceiverValue?
                        ): ProcessorAction {
                            resultCollector.consumeCandidate(
                                group,
                                candidateFactory.createCandidate(
                                    symbol,
                                    dispatchReceiverValue,
                                    implicitExtensionReceiverValue,
                                    ExplicitReceiverKind.DISPATCH_RECEIVER
                                )
                            )
                            return ProcessorAction.NEXT
                        }
                    }
                )
            TowerDataKind.TOWER_LEVEL -> {
                if (token == TowerScopeLevel.Token.Objects) return ProcessorAction.NEXT
                towerScopeLevel.processElementsByName(
                    token,
                    name,
                    explicitReceiver = explicitReceiver,
                    processor = object : TowerScopeLevel.TowerScopeLevelProcessor<T> {
                        override fun consumeCandidate(
                            symbol: T,
                            dispatchReceiverValue: ClassDispatchReceiverValue?,
                            implicitExtensionReceiverValue: ImplicitReceiverValue?
                        ): ProcessorAction {
                            if (symbol is FirFunctionSymbol && symbol.callableId.packageName.startsWith(defaultPackage)) {
                                val explicitReceiverType = explicitReceiver.type
                                if (dispatchReceiverValue == null && explicitReceiverType is ConeClassType) {
                                    val declarationReceiverTypeRef =
                                        (symbol as? FirCallableSymbol)?.fir?.receiverTypeRef as? FirResolvedTypeRef
                                    val declarationReceiverType = declarationReceiverTypeRef?.type
                                    if (declarationReceiverType is ConeClassType) {
                                        if (!AbstractTypeChecker.isSubtypeOf(
                                                candidateFactory.inferenceComponents.ctx,
                                                explicitReceiverType,
                                                declarationReceiverType.lookupTag.constructClassType(
                                                    declarationReceiverType.typeArguments.map { ConeStarProjection }.toTypedArray(),
                                                    isNullable = true
                                                )
                                            )
                                        ) {
                                            return ProcessorAction.NEXT
                                        }
                                    }
                                }
                            }
                            val candidate = candidateFactory.createCandidate(
                                symbol,
                                dispatchReceiverValue,
                                implicitExtensionReceiverValue,
                                ExplicitReceiverKind.EXTENSION_RECEIVER
                            )

                            resultCollector.consumeCandidate(
                                group,
                                candidate
                            )
                            return ProcessorAction.NEXT
                        }
                    }
                )
            }
        }
    }

}

class NoExplicitReceiverTowerDataConsumer<T : ConeSymbol>(
    val session: FirSession,
    val name: Name,
    val token: TowerScopeLevel.Token<T>,
    val candidateFactory: CandidateFactory
) : TowerDataConsumer() {


    override fun consume(
        kind: TowerDataKind,
        towerScopeLevel: TowerScopeLevel,
        resultCollector: CandidateCollector,
        group: Int
    ): ProcessorAction {
        if (checkSkip(group, resultCollector)) return ProcessorAction.NEXT
        return when (kind) {

            TowerDataKind.TOWER_LEVEL -> {
                towerScopeLevel.processElementsByName(
                    token,
                    name,
                    explicitReceiver = null,
                    processor = object : TowerScopeLevel.TowerScopeLevelProcessor<T> {
                        override fun consumeCandidate(
                            symbol: T,
                            dispatchReceiverValue: ClassDispatchReceiverValue?,
                            implicitExtensionReceiverValue: ImplicitReceiverValue?
                        ): ProcessorAction {
                            resultCollector.consumeCandidate(
                                group,
                                candidateFactory.createCandidate(
                                    symbol,
                                    dispatchReceiverValue,
                                    implicitExtensionReceiverValue,
                                    ExplicitReceiverKind.NO_EXPLICIT_RECEIVER
                                )
                            )
                            return ProcessorAction.NEXT
                        }
                    }
                )
            }
            else -> ProcessorAction.NEXT
        }
    }

}

class CallResolver(val typeCalculator: ReturnTypeCalculator, val components: InferenceComponents) {

    var callInfo: CallInfo? = null

    var scopes: List<FirScope>? = null

    val session: FirSession get() = components.session

    private fun processImplicitReceiver(
        towerDataConsumer: TowerDataConsumer,
        implicitReceiverValue: ImplicitReceiverValue,
        collector: CandidateCollector,
        oldGroup: Int
    ): Int {
        var group = oldGroup
        towerDataConsumer.consume(
            TowerDataKind.TOWER_LEVEL,
            MemberScopeTowerLevel(session, implicitReceiverValue),
            collector, group++
        )

        // This is an equivalent to the old "BothTowerLevelAndImplicitReceiver"
        towerDataConsumer.consume(
            TowerDataKind.TOWER_LEVEL,
            MemberScopeTowerLevel(session, implicitReceiverValue, implicitReceiverValue),
            collector, group++
        )

        for (scope in scopes!!) {
            towerDataConsumer.consume(
                TowerDataKind.TOWER_LEVEL,
                ScopeTowerLevel(session, scope, implicitReceiverValue),
                collector, group++
            )
        }

        return group
    }

    fun runTowerResolver(towerDataConsumer: TowerDataConsumer, implicitReceiverValues: List<ImplicitReceiverValue>): CandidateCollector {
        val collector = CandidateCollector(callInfo!!, components)

        var group = 0

        towerDataConsumer.consume(TowerDataKind.EMPTY, TowerScopeLevel.Empty, collector, group++)

        for (scope in scopes!!) {
            towerDataConsumer.consume(TowerDataKind.TOWER_LEVEL, ScopeTowerLevel(session, scope), collector, group++)
        }

        var blockDispatchReceivers = false
        for (implicitReceiverValue in implicitReceiverValues) {
            if (implicitReceiverValue is ImplicitDispatchReceiverValue) {
                if (blockDispatchReceivers) {
                    continue
                }
                if (!implicitReceiverValue.boundSymbol.fir.isInner) {
                    blockDispatchReceivers = true
                }
            }
            processImplicitReceiver(towerDataConsumer, implicitReceiverValue, collector, group)
        }

        return collector
    }


}


enum class CandidateApplicability {
    HIDDEN,
    WRONG_RECEIVER,
    PARAMETER_MAPPING_ERROR,
    INAPPLICABLE,
    SYNTHETIC_RESOLVED,
    RESOLVED
}

class CandidateCollector(val callInfo: CallInfo, val components: InferenceComponents) {

    val groupNumbers = mutableListOf<Int>()
    val candidates = mutableListOf<Candidate>()


    var currentApplicability = CandidateApplicability.HIDDEN


    fun newDataSet() {
        groupNumbers.clear()
        candidates.clear()
        currentApplicability = CandidateApplicability.HIDDEN
    }

    protected fun getApplicability(
        group: Int,
        candidate: Candidate
    ): CandidateApplicability {

        val sink = CheckerSinkImpl(components)
        var finished = false
        sink.continuation = suspend {
            for (stage in callInfo.callKind.sequence()) {
                stage.check(candidate, sink, callInfo)
            }
        }.createCoroutineUnintercepted(completion = object : Continuation<Unit> {
            override val context: CoroutineContext
                get() = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                result.exceptionOrNull()?.let { throw it }
                finished = true
            }
        })




        while (!finished) {
            sink.continuation!!.resume(Unit)
            if (sink.current < CandidateApplicability.SYNTHETIC_RESOLVED) {
                break
            }
        }
        return sink.current
    }

    fun consumeCandidate(group: Int, candidate: Candidate) {
        val applicability = getApplicability(group, candidate)

        if (applicability > currentApplicability) {
            groupNumbers.clear()
            candidates.clear()
            currentApplicability = applicability
        }


        if (applicability == currentApplicability) {
            candidates.add(candidate)
            groupNumbers.add(group)
        }
    }


    fun bestCandidates(): List<Candidate> {
        if (groupNumbers.isEmpty()) return emptyList()
        val result = mutableListOf<Candidate>()
        var bestGroup = groupNumbers.first()
        for ((index, candidate) in candidates.withIndex()) {
            val group = groupNumbers[index]
            if (bestGroup > group) {
                bestGroup = group
                result.clear()
            }
            if (bestGroup == group) {
                result.add(candidate)
            }
        }
        return result
    }

    fun isSuccess(): Boolean {
        return currentApplicability == CandidateApplicability.RESOLVED
    }
}

fun FirCallableDeclaration.dispatchReceiverValue(session: FirSession): ClassDispatchReceiverValue? {
    // TODO: this is not true at least for inner class constructors
    if (this is FirConstructor) return null
    val id = (this.symbol as ConeCallableSymbol).callableId.classId ?: return null
    val symbol = session.service<FirSymbolProvider>().getClassLikeSymbolByFqName(id) as? FirClassSymbol ?: return null
    val regularClass = symbol.fir

    // TODO: this is also not true, but objects can be also imported
    if (regularClass.classKind == ClassKind.OBJECT) return null

    return ClassDispatchReceiverValue(regularClass.symbol)
}

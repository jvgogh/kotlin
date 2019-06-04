/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.declarations.impl

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.impl.FirImplicitTypeRefImpl
import org.jetbrains.kotlin.fir.visitors.FirTransformer

class FirAnonymousFunctionImpl(
    session: FirSession,
    psi: PsiElement?,
    override var returnTypeRef: FirTypeRef,
    override var receiverTypeRef: FirTypeRef?
) : FirAnonymousFunction(session, psi), FirModifiableFunction {
    override var body: FirBlock? = null

    override val valueParameters = mutableListOf<FirValueParameter>()

    override var typeRef: FirTypeRef = FirImplicitTypeRefImpl(session, null)

    override var label: FirLabel? = null

    override fun replaceTypeRef(newTypeRef: FirTypeRef) {
        typeRef = newTypeRef
    }

    override fun <D> transformChildren(transformer: FirTransformer<D>, data: D): FirElement {
        valueParameters.transformInplace(transformer, data)
        typeRef = typeRef.transformSingle(transformer, data)
        returnTypeRef = returnTypeRef.transformSingle(transformer, data)
        receiverTypeRef = receiverTypeRef?.transformSingle(transformer, data)
        label = label?.transformSingle(transformer, data)
        body = body?.transformSingle(transformer, data)
        return super.transformChildren(transformer, data)
    }

    override fun <D> transformReturnTypeRef(transformer: FirTransformer<D>, data: D) {
        returnTypeRef = returnTypeRef.transformSingle(transformer, data)
    }
}
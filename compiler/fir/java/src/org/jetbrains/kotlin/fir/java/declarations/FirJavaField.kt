/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.declarations

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.name.Name

class FirJavaField(
    session: FirSession,
    symbol: FirFieldSymbol,
    name: Name,
    visibility: Visibility,
    modality: Modality?,
    returnTypeRef: FirTypeRef,
    isVar: Boolean,
    isStatic: Boolean
) : FirField(
    session, symbol, name,
    visibility, modality,
    returnTypeRef, isVar
) {
    init {
        symbol.bind(this)
    }

    override val delegate: FirExpression?
        get() = null

    override val initializer: FirExpression?
        get() = null

    init {
        status.isStatic = isStatic
    }
}
/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.inlineclasses

import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParameters
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.ir.createDispatchReceiverParameter
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassAbi.mangledNameFor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.explicitParameters
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager

class IrReplacementFunction(
    val function: IrFunction,
    val valueParameterMap: Map<IrValueParameterSymbol, IrValueParameter>
)

/**
 * Keeps track of replacement functions and inline class box/unbox functions.
 */
class MemoizedInlineClassReplacements {
    private val storageManager = LockBasedStorageManager("inline-class-replacements")

    /**
     * Get a replacement for a function or a constructor.
     */
    val getReplacementFunction: (IrFunction) -> IrReplacementFunction? =
        storageManager.createMemoizedFunctionWithNullableValues {
            when {
                !it.hasInlineClassParameters || it.isSyntheticInlineClassMember || it.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA -> null
                it.hasMethodReplacement -> createMethodReplacement(it)
                it.hasStaticReplacement -> createStaticReplacement(it)
                else -> null
            }
        }

    private val IrFunction.hasInlineClassParameters: Boolean
        get() = explicitParameters.any { it.type.erasedUpperBound.isInline } || (this is IrConstructor && constructedClass.isInline)

    private val IrFunction.hasStaticReplacement: Boolean
        get() = origin != IrDeclarationOrigin.FAKE_OVERRIDE &&
                (this is IrSimpleFunction || this is IrConstructor && constructedClass.isInline)

    private val IrFunction.hasMethodReplacement: Boolean
        get() = dispatchReceiverParameter != null && this is IrSimpleFunction && (parent as? IrClass)?.isInline != true

    private val IrFunction.isSyntheticInlineClassMember: Boolean
        get() = origin == JvmLoweredDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER || isInlineClassFieldGetter

    /**
     * Get the box function for an inline class. Concretely, this is a synthetic
     * static function named "box-impl" which takes an unboxed value and returns
     * a boxed value.
     */
    val getBoxFunction: (IrClass) -> IrSimpleFunction =
        storageManager.createMemoizedFunction { irClass ->
            require(irClass.isInline)
            buildFun {
                name = Name.identifier("box-impl")
                origin = JvmLoweredDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER
                returnType = irClass.defaultType
            }.apply {
                parent = irClass
                copyTypeParametersFrom(irClass)
                addValueParameter {
                    name = Name.identifier("v")
                    type = InlineClassAbi.getUnderlyingType(irClass)
                }
            }
        }

    /**
     * Get the unbox function for an inline class. Concretely, this is a synthetic
     * member function named "unbox-impl" which returns an unboxed result.
     */
    val getUnboxFunction: (IrClass) -> IrSimpleFunction =
        storageManager.createMemoizedFunction { irClass ->
            require(irClass.isInline)
            buildFun {
                name = Name.identifier("unbox-impl")
                origin = JvmLoweredDeclarationOrigin.SYNTHETIC_INLINE_CLASS_MEMBER
                returnType = InlineClassAbi.getUnderlyingType(irClass)
            }.apply {
                parent = irClass
                createDispatchReceiverParameter()
            }
        }

    private fun createMethodReplacement(function: IrFunction): IrReplacementFunction? {
        require(function.dispatchReceiverParameter != null && function is IrSimpleFunction)
        val overrides = function.overriddenSymbols.mapNotNull {
            getReplacementFunction(it.owner)?.function?.symbol as? IrSimpleFunctionSymbol
        }
        if (function.origin == IrDeclarationOrigin.FAKE_OVERRIDE && overrides.isEmpty())
            return null

        val parameterMap = mutableMapOf<IrValueParameterSymbol, IrValueParameter>()
        val replacement = buildReplacement(function) {
            annotations += function.annotations
            metadata = function.metadata
            overriddenSymbols.addAll(overrides)

            for ((index, parameter) in function.explicitParameters.withIndex()) {
                val name = if (parameter == function.extensionReceiverParameter) Name.identifier("\$receiver") else parameter.name
                val newParameter = parameter.copyTo(this, index = index - 1, name = name)
                if (parameter == function.dispatchReceiverParameter)
                    dispatchReceiverParameter = newParameter
                else
                    valueParameters.add(newParameter)
                parameterMap[parameter.symbol] = newParameter
            }
        }
        return IrReplacementFunction(replacement, parameterMap)
    }

    private fun createStaticReplacement(function: IrFunction): IrReplacementFunction {
        val parameterMap = mutableMapOf<IrValueParameterSymbol, IrValueParameter>()
        val replacement = buildReplacement(function) {
            if (function !is IrSimpleFunction || function.overriddenSymbols.isEmpty())
                metadata = function.metadata

            for ((index, parameter) in function.explicitParameters.withIndex()) {
                val name = when (parameter) {
                    function.dispatchReceiverParameter -> Name.identifier("\$this")
                    function.extensionReceiverParameter -> Name.identifier("\$receiver")
                    else -> parameter.name
                }

                val newParameter = parameter.copyTo(this, index = index, name = name)
                valueParameters.add(newParameter)
                parameterMap[parameter.symbol] = newParameter
            }
        }
        return IrReplacementFunction(replacement, parameterMap)
    }

    private fun buildReplacement(function: IrFunction, body: IrFunctionImpl.() -> Unit) =
        buildFun {
            updateFrom(function)
            name = mangledNameFor(function)
            returnType = function.returnType
        }.apply {
            parent = function.parent
            if (function is IrConstructor) {
                copyTypeParameters(function.constructedClass.typeParameters + function.typeParameters)
            } else {
                copyTypeParametersFrom(function)
            }
            body()
        }
}

/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.common.EmptyLoggingContext
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.lower.serializeIrFile
import org.jetbrains.kotlin.backend.jvm.lower.serializeToplevelIrClass
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.ir.backend.jvm.lower.serialization.ir.JvmIrDeserializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.IrDeserializer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext

object JvmBackendFacade {
    fun doGenerateFiles(
        files: Collection<KtFile>,
        state: GenerationState,
        errorHandler: CompilationErrorHandler,
        phaseConfig: PhaseConfig
    ) {
        val psi2ir = Psi2IrTranslator(state.languageVersionSettings)
        val psi2irContext = psi2ir.createGeneratorContext(state.module, state.bindingContext, extensions = JvmGeneratorExtensions)
        val deserializer = JvmIrDeserializer(state.module, EmptyLoggingContext, psi2irContext.irBuiltIns, psi2irContext.symbolTable, state.languageVersionSettings)
        val irModuleFragment = psi2ir.generateModuleFragment(psi2irContext, files, deserializer)

        doGenerateFilesInternal(state, errorHandler, irModuleFragment, psi2irContext, phaseConfig, deserializer)
    }

    internal fun doGenerateFilesInternal(
        state: GenerationState,
        errorHandler: CompilationErrorHandler,
        irModuleFragment: IrModuleFragment,
        psi2irContext: GeneratorContext,
        phaseConfig: PhaseConfig,
        deserializer: IrDeserializer?
    ) {
        val jvmBackendContext = JvmBackendContext(
            state, psi2irContext.sourceManager, psi2irContext.irBuiltIns, irModuleFragment, psi2irContext.symbolTable, phaseConfig
        )

        //TODO
        ExternalDependenciesGenerator(
            irModuleFragment.descriptor,
            psi2irContext.symbolTable,
            psi2irContext.irBuiltIns,
            JvmGeneratorExtensions.externalDeclarationOrigin,
            deserializer
        ).generateUnboundSymbolsAsDependencies()

        for (irFile in irModuleFragment.files) {
            irFile.metadata!!.serializedIr = serializeIrFile(jvmBackendContext, irFile)
            for (irClass in irFile.declarations.filterIsInstance<IrClass>()) {
                (irClass.metadata as MetadataSource.Class).serializedIr = serializeToplevelIrClass(jvmBackendContext, irClass)
            }
        }

        val jvmBackend = JvmBackend(jvmBackendContext)

        for (irFile in irModuleFragment.files) {
            try {
                jvmBackend.generateFile(irFile)
                state.afterIndependentPart()
            } catch (e: Throwable) {
                errorHandler.reportException(e, null) // TODO ktFile.virtualFile.url
            }
        }
    }
}

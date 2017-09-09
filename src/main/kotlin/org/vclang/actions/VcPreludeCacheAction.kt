package org.vclang.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.jetbrains.jetpad.vclang.error.ListErrorReporter
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleDynamicNamespaceProvider
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleModuleNamespaceProvider
import com.jetbrains.jetpad.vclang.frontend.namespace.SimpleStaticNamespaceProvider
import com.jetbrains.jetpad.vclang.frontend.resolving.HasOpens
import com.jetbrains.jetpad.vclang.frontend.resolving.NamespaceProviders
import com.jetbrains.jetpad.vclang.module.caching.CacheManager
import com.jetbrains.jetpad.vclang.naming.NameResolver
import com.jetbrains.jetpad.vclang.term.Prelude
import com.jetbrains.jetpad.vclang.typechecking.Typechecking
import com.jetbrains.jetpad.vclang.typechecking.order.DependencyListener
import org.vclang.module.source.VcPreludeStorage
import org.vclang.typechecking.PreludeCacheGenerator
import java.nio.file.Paths

class VcPreludeCacheAction : AnAction() {

    init {
        templatePresentation.text = "Generate Prelude cache"
        templatePresentation.description = "Generate Prelude cache"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = checkNotNull(e.project) { "Invalid action event" }

        val errorReporter = ListErrorReporter()

        val moduleNsProvider = SimpleModuleNamespaceProvider()
        val staticNsProvider = SimpleStaticNamespaceProvider()
        val dynamicNsProvider = SimpleDynamicNamespaceProvider()
        val nameResolver = NameResolver(NamespaceProviders(
                moduleNsProvider,
                staticNsProvider,
                dynamicNsProvider
        ))

        val storage = VcPreludeStorage(project, nameResolver)
        val cacheManager = CacheManager<VcPreludeStorage.SourceId>(
                PreludeCacheGenerator.PreludePersistenceProvider(),
                PreludeCacheGenerator.PreludeBuildCacheSupplier(Paths.get("/")),
                PreludeCacheGenerator.PreludeVersionTracker(),
                PreludeCacheGenerator.PreludeDefLocator(storage.preludeSourceId)
        )

        val prelude = storage.loadSource(storage.preludeSourceId, errorReporter)?.definition
        check(errorReporter.errorList.isEmpty()) {
            "Some errors occurred while loading prelude:\n" +
                    errorReporter.errorList.joinToString("\n") { it.getMessage() }
        }

        Typechecking(
                cacheManager.typecheckerState,
                staticNsProvider,
                dynamicNsProvider,
                HasOpens.GET,
                errorReporter,
                Prelude.UpdatePreludeReporter(cacheManager.typecheckerState),
                object : DependencyListener {}
        ).typecheckModules(setOf(prelude))
        cacheManager.persistCache(storage.preludeSourceId)

        println("Prelude cache updated")
    }
}
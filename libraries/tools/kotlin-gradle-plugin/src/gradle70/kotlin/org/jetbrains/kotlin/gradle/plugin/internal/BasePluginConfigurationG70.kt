/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.internal

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.BasePluginConvention
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.utils.chainedFinalizeValueOnRead
import org.jetbrains.kotlin.gradle.utils.propertyWithConvention

internal class BasePluginConfigurationG70(
    private val basePluginConvention: BasePluginConvention,
    private val objects: ObjectFactory
) : BasePluginConfiguration {
    override val archivesName: Property<String>
        get() = objects
            .propertyWithConvention(basePluginConvention.archivesBaseName)
            .chainedFinalizeValueOnRead()

    override val distsDirectory: DirectoryProperty
        get() = basePluginConvention.distsDirectory

    override val libsDirectory: DirectoryProperty
        get() = basePluginConvention.libsDirectory

    internal class BasePluginConfigurationVariantFactoryG70 : BasePluginConfiguration.BasePluginConfigurationVariantFactory {
        override fun getInstance(project: Project): BasePluginConfiguration {
            return BasePluginConfigurationG70(
                project.convention.getPlugin(BasePluginConvention::class.java),
                project.objects
            )
        }
    }
}

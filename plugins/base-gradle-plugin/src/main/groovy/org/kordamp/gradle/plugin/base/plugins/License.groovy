/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kordamp.gradle.plugin.base.plugins

import groovy.transform.Canonical
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.util.ConfigureUtil
import org.kordamp.gradle.plugin.base.model.Information
import org.kordamp.gradle.plugin.base.model.LicenseSet

/**
 * @author Andres Almiray
 * @since 0.4.0
 */
@CompileStatic
@Canonical
@EqualsAndHashCode(excludes = ['project'])
@ToString(includeNames = true, excludes = ['project'])
class License {
    boolean enabled = true
    final LicenseSet licenses = new LicenseSet()

    private boolean enabledSet

    private final Project project

    License(Project project) {
        this.project = project
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled
        this.enabledSet = true
    }

    boolean isEnabledSet() {
        this.enabledSet
    }

    void licenses(Action<? super LicenseSet> action) {
        action.execute(licenses)
    }

    void licenses(@DelegatesTo(LicenseSet) Closure action) {
        ConfigureUtil.configure(action, licenses)
    }

    void copyInto(License copy) {
        copy.@enabled = enabled
        copy.@enabledSet = enabledSet
        licenses.copyInto(copy.licenses)
    }

    static void merge(License o1, License o2) {
        o1.setEnabled((boolean) (o1.enabledSet ? o1.enabled : o2.enabled))
        LicenseSet.merge(o1.licenses, o2.licenses)
    }

    List<String> validate(Information info) {
        List<String> errors = []

        if (!enabled) return errors

        errors = licenses.validate(project)

        errors
    }

    List<org.kordamp.gradle.plugin.base.model.License> allLicenses() {
        licenses.licenses
    }

    @CompileDynamic
    List<String> resolveBintrayLicenseIds() {
        List<String> ids = allLicenses().collect { it.id?.bintray() ?: '' }.unique()
        ids.remove('')
        ids
    }
}
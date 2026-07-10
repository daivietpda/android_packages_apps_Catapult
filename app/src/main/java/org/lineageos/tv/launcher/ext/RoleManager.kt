/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.ext

import android.app.role.RoleManager
import android.os.Build

fun RoleManager.roleCanBeRequested(roleName: String) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isRoleAvailable(roleName) && !isRoleHeld(roleName)
    } else {
        false
    }

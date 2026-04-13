// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.mu.runtime

import one.wabbit.mu.MuException
import one.wabbit.mu.types.MuType
import one.wabbit.mu.types.TypeFormatter.Companion.default as F

class MissingRequiredArgumentException(val function: String?, val argument: String) :
    MuException("Missing required argument: '$argument'${function?.let { " (in $it)" } ?: ""}")

class TypeMismatchInArgumentException(
    val function: String?,
    val argument: String,
    val expected: MuType,
    val actual: MuType,
    val extra: String? = null,
) :
    MuException(
        buildString {
            append(
                "Type mismatch in argument $argument: expected ${F.format(expected)}, found ${F.format(actual)}"
            )
            function?.let { append(" (in $it)") }
            extra?.let { append(". $it") }
        }
    )

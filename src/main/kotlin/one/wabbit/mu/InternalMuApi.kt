// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.mu

@RequiresOptIn(message = "This API is internal.")
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
annotation class InternalMuApi

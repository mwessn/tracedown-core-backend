package dev.tracedown.gateway.util

fun validatePassword(password: String, policy: PasswordPolicyConfig): List<String> {
    val errors = mutableListOf<String>()

    if (password.length < policy.minLength) {
        errors.add("Password must be at least ${policy.minLength} characters")
    }
    if (password.count { it.isUpperCase() } < policy.minUppercase) {
        errors.add("Password must contain at least ${policy.minUppercase} uppercase character(s)")
    }
    if (password.count { it.isDigit() } < policy.minDigits) {
        errors.add("Password must contain at least ${policy.minDigits} digit(s)")
    }
    val specialCount = password.count { !it.isLetterOrDigit() }
    if (specialCount < policy.minSpecial) {
        errors.add("Password must contain at least ${policy.minSpecial} special character(s)")
    }

    return errors
}

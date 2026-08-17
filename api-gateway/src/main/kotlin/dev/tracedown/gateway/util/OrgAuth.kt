package dev.tracedown.gateway.util

import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.common.auth.canRead
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.auth.resolveCachedPermissions
import dev.tracedown.common.auth.resolveOrgPermissions
import dev.tracedown.common.errors.ErrorCodes
import java.util.UUID

/**
 * Resolves org permissions and requires read access on the given section.
 * Throws [ForbiddenException] if the user is not a member or lacks permission.
 * Owner always passes.
 */
fun requireOrgRead(orgId: UUID, userId: UUID, section: (OrgPermissions) -> Short): OrgPermissions {
    val perms = resolveOrgPermissions(orgId, userId)
        ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
    if (!section(perms).canRead()) {
        throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
    }
    return perms
}

/**
 * Resolves org permissions and requires write access on the given section.
 * Throws [ForbiddenException] if the user is not a member or lacks permission.
 * Owner always passes.
 */
fun requireOrgWrite(orgId: UUID, userId: UUID, section: (OrgPermissions) -> Short): OrgPermissions {
    val perms = resolveOrgPermissions(orgId, userId)
        ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
    if (!section(perms).canWrite()) {
        throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
    }
    return perms
}

/**
 * Resolves full cached permissions for resource-level access checks.
 * Throws [ForbiddenException] if not a member.
 */
fun requireCachedPermissions(orgId: UUID, userId: UUID): CachedPermissions {
    return resolveCachedPermissions(orgId, userId)
        ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
}

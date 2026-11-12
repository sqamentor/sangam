// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Writes a file that only its owner can read: mode 0600 on POSIX file systems, set when the file is created (so there is
 * no moment with wider rights), or an access list with a single entry for the owner on Windows (NTFS). The content goes
 * to a fresh file in the same directory first and is then moved over the target, so a crash never leaves a half-written
 * credentials file.
 */
final class OwnerOnlyFiles {

    /** How the file is protected. */
    enum Protection { POSIX_0600, OWNER_ACL, PROFILE_ONLY }

    private OwnerOnlyFiles() {
    }

    static Protection write(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrictDirectory(dir);
        }
        Path tmp = dir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Protection p;
        try {
            p = create(tmp);
            Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return p;
    }

    /** Creates an empty file with owner-only rights; fails if it exists. */
    static Protection create(Path file) throws IOException {
        if (Files.getFileStore(file.toAbsolutePath().getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            return Protection.POSIX_0600;
        }
        Files.createFile(file);
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            return Protection.PROFILE_ONLY;
        }
        acl.setAcl(List.of(ownerOnly(acl.getOwner(), EnumSet.allOf(AclEntryPermission.class))));
        return Protection.OWNER_ACL;
    }

    static AclEntry ownerOnly(UserPrincipal owner, java.util.Set<AclEntryPermission> permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(permissions).build();
    }

    /** A directory this class created ({@code ~/.sangam}): 0700 on POSIX; on Windows the files carry their own access list. */
    private static void restrictDirectory(Path dir) throws IOException {
        PosixFileAttributeView v = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        if (v != null) {
            try {
                v.setPermissions(PosixFilePermissions.fromString("rwx------"));
            } catch (IOException e) {
                // a file system that refuses the change: the file mode still protects the file
            }
        }
    }

    /** Removes a file; a missing one is not an error. */
    static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}

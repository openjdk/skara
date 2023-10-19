package org.openjdk.skara.forge;

import org.openjdk.skara.host.HostUser;

/**
 * A repository collaborator is a user and a set of permissions, currently only
 * 'canPush'.
 */
public record Collaborator(HostUser user, boolean canPush) {
}

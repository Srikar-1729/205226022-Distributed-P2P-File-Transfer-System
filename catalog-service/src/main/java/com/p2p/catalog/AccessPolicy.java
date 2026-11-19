package com.p2p.catalog;

import com.p2p.common.Principal;
import com.p2p.proto.Visibility;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.Collection;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Who may see / download a file. The same rule is used for single-file checks
 * ({@link #canAccess}) and, as a MongoDB query, for search ({@link #visibleTo}).
 *
 * <pre>
 *              owner  allowedUsers  other users  guests
 * PUBLIC         ✓         ✓             ✓          ✓
 * REGISTERED     ✓         ✓             ✓          ✗
 * PRIVATE        ✓         ✓             ✗          ✗
 * </pre>
 */
public final class AccessPolicy {
    private AccessPolicy() {}

    public static boolean canAccess(Visibility visibility, String ownerId, Collection<String> allowedUsers, Principal p) {
        if (p == null) {
            return false;
        }
        if (p.id().equals(ownerId)) {
            return true;
        }
        return switch (visibility) {
            case PUBLIC -> true;
            case REGISTERED -> !p.isGuest();
            case PRIVATE -> !p.isGuest() && allowedUsers.contains(p.username());
            default -> false;
        };
    }

    /** MongoDB criteria matching exactly the files {@link #canAccess} allows. */
    public static Criteria visibleTo(Principal p) {
        if (p.isGuest()) {
            return new Criteria().orOperator(
                    where("ownerId").is(p.id()),
                    where("visibility").is(Visibility.PUBLIC.name()));
        }
        return new Criteria().orOperator(
                where("ownerId").is(p.id()),
                where("visibility").in(Visibility.PUBLIC.name(), Visibility.REGISTERED.name()),
                new Criteria().andOperator(
                        where("visibility").is(Visibility.PRIVATE.name()),
                        where("allowedUsers").is(p.username())));
    }
}

package com.p2p.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.google.protobuf.ByteString;
import com.p2p.common.Principal;
import com.p2p.common.Tokens;
import com.p2p.proto.AuthResponse;
import com.p2p.proto.AuthServiceGrpc;
import com.p2p.proto.Empty;
import com.p2p.proto.LoginRequest;
import com.p2p.proto.PublicKey;
import com.p2p.proto.RegisterRequest;
import com.p2p.proto.Role;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** gRPC AuthService: accounts (bcrypt), guest sessions and JWT issuing. */
@GrpcService
class AuthServiceImpl extends AuthServiceGrpc.AuthServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{3,32}");
    private static final Duration TOKEN_TTL = Duration.ofHours(12);
    private static final int BCRYPT_COST = 10;

    private final UserRepository users;
    private final KeyPair keys;

    AuthServiceImpl(UserRepository users, KeyPair keys) {
        this.users = users;
        this.keys = keys;
    }

    @Override
    public void register(RegisterRequest req, StreamObserver<AuthResponse> out) {
        String username = req.getUsername().trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw Status.INVALID_ARGUMENT.withDescription("username must be 3-32 chars: letters, digits, _").asRuntimeException();
        }
        if (username.startsWith("guest")) {
            throw Status.INVALID_ARGUMENT.withDescription("usernames starting with 'guest' are reserved").asRuntimeException();
        }
        if (req.getPassword().length() < 6) {
            throw Status.INVALID_ARGUMENT.withDescription("password must be at least 6 characters").asRuntimeException();
        }
        User u;
        try {
            u = users.insert(new User(new ObjectId().toHexString(), username,
                    BCrypt.withDefaults().hashToString(BCRYPT_COST, req.getPassword().toCharArray()), Instant.now()));
        } catch (DuplicateKeyException e) {
            throw Status.ALREADY_EXISTS.withDescription("username already taken").asRuntimeException();
        }
        log.info("registered user {}", username);
        reply(out, new Principal(u.id(), username, Role.USER));
    }

    @Override
    public void login(LoginRequest req, StreamObserver<AuthResponse> out) {
        String username = req.getUsername().trim().toLowerCase(Locale.ROOT);
        User u = users.findByUsername(username)
                .filter(x -> BCrypt.verifyer().verify(req.getPassword().toCharArray(), x.passwordHash()).verified)
                .orElseThrow(() -> Status.UNAUTHENTICATED.withDescription("wrong username or password").asRuntimeException());
        reply(out, new Principal(u.id(), username, Role.USER));
    }

    @Override
    public void guestLogin(Empty req, StreamObserver<AuthResponse> out) {
        String id = "guest-" + UUID.randomUUID();
        reply(out, new Principal(id, "guest-" + id.substring(6, 12), Role.GUEST));
    }

    @Override
    public void getPublicKey(Empty req, StreamObserver<PublicKey> out) {
        out.onNext(PublicKey.newBuilder()
                .setDer(ByteString.copyFrom(keys.getPublic().getEncoded()))
                .setAlgorithm("RSA").build());
        out.onCompleted();
    }

    private void reply(StreamObserver<AuthResponse> out, Principal p) {
        Instant exp = Instant.now().plus(TOKEN_TTL);
        out.onNext(AuthResponse.newBuilder()
                .setToken(Tokens.issueUserToken(keys.getPrivate(), p, exp))
                .setUserId(p.id())
                .setUsername(p.username())
                .setRole(p.role())
                .setExpiresAt(exp.toEpochMilli())
                .build());
        out.onCompleted();
    }
}

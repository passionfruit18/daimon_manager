package com.daimon.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Ed25519 signing of serialization commits. The signature covers the commit hash, which in turn
 * covers the entire workspace tree and history.
 *
 * <p>Keys are held in memory. Persisting the private key (OS keychain, Tauri stronghold, etc.)
 * is left to the hosting application.
 */
public final class WorkspaceSigner {

    private static final String ALGORITHM = "Ed25519";

    private final KeyPair keyPair;
    private final String publicKeyBase64;

    private WorkspaceSigner(KeyPair keyPair) {
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public static WorkspaceSigner generate() {
        try {
            return new WorkspaceSigner(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is unavailable on this JVM", e);
        }
    }

    public static WorkspaceSigner fromKeyPair(KeyPair keyPair) {
        return new WorkspaceSigner(keyPair);
    }

    /** Base64 X.509 SubjectPublicKeyInfo encoding of the public key. */
    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    /** Returns {@code commit} with a detached signature over its hash. */
    public SerializationCommit sign(SerializationCommit commit) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(commit.hash().getBytes(StandardCharsets.UTF_8));
            return commit.withSignature(Base64.getEncoder().encodeToString(signer.sign()), publicKeyBase64);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign commit " + commit.shortHash(), e);
        }
    }

    /**
     * Verifies a commit's signature against the public key embedded in it. Returns {@code false}
     * for unsigned commits or malformed keys/signatures.
     */
    public static boolean verify(SerializationCommit commit) {
        if (commit.signature().isEmpty() || commit.signerPublicKey().isEmpty()) {
            return false;
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(commit.signerPublicKey().get())));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(commit.hash().getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(commit.signature().get()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}

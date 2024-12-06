/*
 * Copyright 2022 The Sigstore Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sigstore;

import com.google.api.client.util.Preconditions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.VerificationOptions.UncheckedCertificateException;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.encryption.certificates.Certificates;
import dev.sigstore.encryption.signers.Verifiers;
import dev.sigstore.fulcio.client.FulcioVerificationException;
import dev.sigstore.fulcio.client.FulcioVerifier;
import dev.sigstore.rekor.client.HashedRekordRequest;
import dev.sigstore.rekor.client.RekorEntry;
import dev.sigstore.rekor.client.RekorVerificationException;
import dev.sigstore.rekor.client.RekorVerifier;
import dev.sigstore.tuf.SigstoreTufClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.sql.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;

/** Verify hashrekords from rekor signed using the keyless signing flow with fulcio certificates. */
public class KeylessVerifier {

  private final FulcioVerifier fulcioVerifier;
  private final RekorVerifier rekorVerifier;

  private KeylessVerifier(FulcioVerifier fulcioVerifier, RekorVerifier rekorVerifier) {
    this.fulcioVerifier = fulcioVerifier;
    this.rekorVerifier = rekorVerifier;
  }

  public static KeylessVerifier.Builder builder() {
    return new KeylessVerifier.Builder();
  }

  public static class Builder {

    private TrustedRootProvider trustedRootProvider;

    public KeylessVerifier build()
        throws InvalidAlgorithmParameterException, CertificateException, InvalidKeySpecException,
            NoSuchAlgorithmException, IOException, InvalidKeyException {
      Preconditions.checkNotNull(trustedRootProvider);
      var trustedRoot = trustedRootProvider.get();
      var fulcioVerifier = FulcioVerifier.newFulcioVerifier(trustedRoot);
      var rekorVerifier = RekorVerifier.newRekorVerifier(trustedRoot);
      return new KeylessVerifier(fulcioVerifier, rekorVerifier);
    }

    public Builder sigstorePublicDefaults() {
      var sigstoreTufClientBuilder = SigstoreTufClient.builder().usePublicGoodInstance();
      trustedRootProvider = TrustedRootProvider.from(sigstoreTufClientBuilder);
      return this;
    }

    public Builder sigstoreStagingDefaults() {
      var sigstoreTufClientBuilder = SigstoreTufClient.builder().useStagingInstance();
      trustedRootProvider = TrustedRootProvider.from(sigstoreTufClientBuilder);
      return this;
    }

    public Builder trustedRootProvider(TrustedRootProvider trustedRootProvider) {
      this.trustedRootProvider = trustedRootProvider;
      return this;
    }
  }

  /** Convenience wrapper around {@link #verify(byte[], Bundle, VerificationOptions)}. */
  public void verify(Path artifact, Bundle bundle, VerificationOptions options)
      throws KeylessVerificationException {
    try {
      byte[] artifactDigest =
          Files.asByteSource(artifact.toFile()).hash(Hashing.sha256()).asBytes();
      verify(artifactDigest, bundle, options);
    } catch (IOException e) {
      throw new KeylessVerificationException("Could not hash provided artifact path: " + artifact);
    }
  }

  /**
   * Verify that the inputs can attest to the validity of a signature using sigstore's keyless
   * infrastructure. If no exception is thrown, it should be assumed verification has passed.
   *
   * @param artifactDigest the sha256 digest of the artifact that is being verified
   * @param bundle the sigstore signature bundle to verify
   * @param options the keyless verification data and options
   * @throws KeylessVerificationException if the signing information could not be verified
   */
  public void verify(byte[] artifactDigest, Bundle bundle, VerificationOptions options)
      throws KeylessVerificationException {

    if (bundle.getDsseEnvelope().isPresent()) {
      throw new KeylessVerificationException("Cannot verify DSSE signature based bundles");
    }

    if (bundle.getMessageSignature().isEmpty()) {
      // this should be unreachable
      throw new IllegalStateException("Bundle must contain a message signature to verify");
    }
    var messageSignature = bundle.getMessageSignature().get();

    if (bundle.getEntries().isEmpty()) {
      throw new KeylessVerificationException("Cannot verify bundle without tlog entry");
    }

    if (bundle.getEntries().size() > 1) {
      throw new KeylessVerificationException(
          "Bundle verification expects 1 entry, but found " + bundle.getEntries().size());
    }

    if (!bundle.getTimestamps().isEmpty()) {
      throw new KeylessVerificationException(
          "Cannot verify bundles with timestamp verification material");
    }

    var signingCert = bundle.getCertPath();
    var leafCert = Certificates.getLeaf(signingCert);

    // this ensures the provided artifact digest matches what may have come from a bundle (in
    // keyless signature)
    if (messageSignature.getMessageDigest().isPresent()) {
      var bundleDigest = messageSignature.getMessageDigest().get().getDigest();
      if (!Arrays.equals(artifactDigest, bundleDigest)) {
        throw new KeylessVerificationException(
            "Provided artifact digest does not match digest used for verification"
                + "\nprovided(hex) : "
                + Hex.toHexString(artifactDigest)
                + "\nverification  : "
                + Hex.toHexString(bundleDigest));
      }
    }

    // verify the certificate chains up to a trusted root (fulcio) and contains a valid SCT from
    // a trusted CT log
    try {
      fulcioVerifier.verifySigningCertificate(signingCert);
    } catch (FulcioVerificationException | IOException ex) {
      throw new KeylessVerificationException(
          "Fulcio certificate was not valid: " + ex.getMessage(), ex);
    }

    // verify the certificate identity if options are present
    checkCertificateMatchers(leafCert, options.getCertificateMatchers());

    var signature = messageSignature.getSignature();

    RekorEntry rekorEntry = bundle.getEntries().get(0);

    // verify the rekor entry is signed by the log keys
    try {
      rekorVerifier.verifyEntry(rekorEntry);
    } catch (RekorVerificationException ex) {
      throw new KeylessVerificationException("Rekor entry signature was not valid", ex);
    }

    // verify the log entry is relevant to the provided verification materials
    try {
      var calculatedHashedRekord =
          Base64.toBase64String(
              HashedRekordRequest.newHashedRekordRequest(
                      artifactDigest, Certificates.toPemBytes(leafCert), signature)
                  .toJsonPayload()
                  .getBytes(StandardCharsets.UTF_8));
      if (!Objects.equals(calculatedHashedRekord, rekorEntry.getBody())) {
        throw new KeylessVerificationException(
            "Provided verification materials are inconsistent with log entry");
      }
    } catch (IOException e) {
      // this should be unreachable, we know leafCert is a valid certificate at this point
      throw new RuntimeException("Unexpected IOException on valid leafCert", e);
    }

    // check if the time of entry inclusion in the log (a stand-in for signing time) is within the
    // validity period for the certificate
    var entryTime = Date.from(rekorEntry.getIntegratedTimeInstant());
    try {
      leafCert.checkValidity(entryTime);
    } catch (CertificateNotYetValidException e) {
      throw new KeylessVerificationException("Signing time was before certificate validity", e);
    } catch (CertificateExpiredException e) {
      throw new KeylessVerificationException("Signing time was after certificate expiry", e);
    }

    // finally check the supplied signature can be verified by the public key in the certificate
    var publicKey = leafCert.getPublicKey();
    try {
      var verifier = Verifiers.newVerifier(publicKey);
      if (!verifier.verifyDigest(artifactDigest, signature)) {
        throw new KeylessVerificationException("Artifact signature was not valid");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new RuntimeException(ex);
    } catch (SignatureException ex) {
      throw new KeylessVerificationException(
          "Signature could not be processed: " + ex.getMessage(), ex);
    }
  }

  @VisibleForTesting
  void checkCertificateMatchers(X509Certificate cert, List<CertificateMatcher> matchers)
      throws KeylessVerificationException {
    try {
      if (matchers.size() > 0 && matchers.stream().noneMatch(matcher -> matcher.test(cert))) {
        var matcherSpec =
            matchers.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
        throw new KeylessVerificationException(
            "No provided certificate identities matched values in certificate: " + matcherSpec);
      }
    } catch (UncheckedCertificateException ce) {
      throw new KeylessVerificationException(
          "Could not verify certificate identities: " + ce.getMessage());
    }
  }
}

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
package dev.sigstore.encryption;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

class KeysTest {

  static final String RSA_PUB_PATH = "dev/sigstore/samples/keys/test-rsa.pub";
  static final String RSA_PUB_PKCS1_PATH = "dev/sigstore/samples/keys/test-rsa-pkcs1.pub";
  static final String EC_PUB_PATH = "dev/sigstore/samples/keys/test-ec.pub";
  static final String ED25519_PUB_PATH = "dev/sigstore/samples/keys/test-ed25519.pub";
  static final String DSA_PUB_PATH = "dev/sigstore/samples/keys/test-dsa.pub";

  static final String ECDSA_SHA2_NISTP256 =
      "dev/sigstore/samples/keys/test-ecdsa-sha2-nistp256.pub";

  @Test
  void parsePublicKey_invalid() {
    var key =
        "-----BEGIN Ã-----\nMGMGB1gFB00gFM0EEEEEEEzEEEEEEEEEEEEEEEEEEEEEEEEEEEEEFB00gFM0EEEEEEEzEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEFGB1g070v129B1700372=\n-----END ïI";
    Assertions.assertThrows(
        IOException.class, () -> Keys.parsePublicKey(key.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parsePublicKey_rsa() throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(RSA_PUB_PATH)));
    assertEquals("RSA", result.getAlgorithm());
  }

  @Test
  void parsePublicKey_rsaPkcs1()
      throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(RSA_PUB_PKCS1_PATH)));
    assertEquals("RSA", result.getAlgorithm());
  }

  @Test
  void parsePublicKey_ec() throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(EC_PUB_PATH)));
    assertEquals("ECDSA", result.getAlgorithm());
  }

  @Test
  @EnabledForJreRange(max = JRE.JAVA_14)
  void parsePublicKey_ed25519_withBouncyCastle()
      throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(ED25519_PUB_PATH)));
    // BouncyCastle names the algorithm differently than the JDK (Ed25519 vs EdDSA)
    assertEquals("Ed25519", result.getAlgorithm());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_15)
  void parsePublicKey_ed25519_withStdLib()
      throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(ED25519_PUB_PATH)));
    assertEquals("EdDSA", result.getAlgorithm());
  }

  @Test
  void parsePublicKey_dsaShouldFail() {
    Assertions.assertThrows(
        NoSuchAlgorithmException.class,
        () -> Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(DSA_PUB_PATH))));
  }

  @Test
  void parseTufPublicKeyPemEncoded_sha2_nistp256()
      throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    PublicKey result =
        Keys.parsePublicKey(Resources.toByteArray(Resources.getResource(ECDSA_SHA2_NISTP256)));
    assertEquals("ECDSA", result.getAlgorithm());
  }

  @Test
  void parseTufPublicKey_ecdsa() throws NoSuchAlgorithmException, InvalidKeySpecException {
    PublicKey key =
        Keys.constructTufPublicKey(
            Hex.decode(
                "04cbc5cab2684160323c25cd06c3307178a6b1d1c9b949328453ae473c5ba7527e35b13f298b41633382241f3fd8526c262d43b45adee5c618fa0642c82b8a9803"),
            "ecdsa-sha2-nistp256");
    assertNotNull(key);
    assertEquals("ECDSA", key.getAlgorithm());
  }

  @Test
  void parseTufPublicKey_ecdsaBad() {
    Assertions.assertThrows(
        InvalidKeySpecException.class,
        () -> {
          Keys.constructTufPublicKey(
              Hex.decode(
                  "04cbcdcab2684160323c25cd06c3307178a6b1d1c9b949328453ae473c5ba7527e35b13f298b41633382241f3fd8526c262d43b45adee5c618fa0642c82b8a9803"),
              "ecdsa-sha2-nistp256");
        });
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_15)
  void parseTufPublicKey_ed25519_java15Plus()
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    // {@code step crypto keypair ed25519.pub /dev/null --kty OKP --curve Ed25519}
    // copy just the key part out of ed25519.pub removing PEM header and footer
    // {@code echo $(copied content) | base64 -d | hexdump -v -e '/1 "%02x" '}
    PublicKey key =
        Keys.constructTufPublicKey(
            Hex.decode(
                "302a300506032b65700321008b2e369230c3b97f4627fd6a59eb054a83ec15ed929ab3d983a40ffd322a223d"),
            "ed25519");
    assertNotNull(key);
    assertEquals("EdDSA", key.getAlgorithm());
  }

  @Test
  @EnabledForJreRange(max = JRE.JAVA_14)
  void parseTufPublicKey_ed25519_lteJava14()
      throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchProviderException {
    // {@code step crypto keypair ed25519.pub /dev/null --kty OKP --curve Ed25519}
    // copy just the key part out of ed25519.pub removing PEM header and footer
    // {@code echo $(copied content) | base64 -d | hexdump -v -e '/1 "%02x" '}
    PublicKey key =
        Keys.constructTufPublicKey(
            Hex.decode(
                "302a300506032b65700321008b2e369230c3b97f4627fd6a59eb054a83ec15ed929ab3d983a40ffd322a223d"),
            "ed25519");
    assertNotNull(key);
    assertEquals("Ed25519", key.getAlgorithm());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_15)
  void parseTufPublicKey_ed25519_rawBytes_java15plus() throws Exception {
    PublicKey key =
        Keys.constructTufPublicKey(
            Hex.decode("2d7218ce609f85de4b0d29d9e679cfd73e96756652f7069a0cf00acb752e5d3c"),
            "ed25519");
    assertNotNull(key);
    assertEquals("EdDSA", key.getAlgorithm());
  }

  @Test
  @EnabledForJreRange(max = JRE.JAVA_14)
  void parseTufPublicKey_ed25519_rawBytes_lteJava14() throws Exception {
    PublicKey key =
        Keys.constructTufPublicKey(
            Hex.decode("2d7218ce609f85de4b0d29d9e679cfd73e96756652f7069a0cf00acb752e5d3c"),
            "ed25519");
    assertNotNull(key);
    assertEquals("Ed25519", key.getAlgorithm());
  }

  @Test
  void parseTufPublicKey_ed25519Bad() {
    Assertions.assertThrows(
        InvalidKeySpecException.class,
        () ->
            Keys.constructTufPublicKey(
                Hex.decode(
                    "302b300506032b65700321008b2e369230c3b97f4627fd6a59eb054a83ec15ed929ab3d983a40ffd322a223d"),
                "ed25519"));
  }

  @Test
  void parseTufPublicKey_rsa() throws NoSuchAlgorithmException, InvalidKeySpecException {
    // {@code step crypto keypair ed25519.pub /dev/null --kty OKP --curve Ed25519}
    // copy just the key part out of ed25519.pub removing PEM header and footer
    // {@code echo $(copied content) | base64 -d | hexdump -v -e '/1 "%02x" '}
    Assertions.assertThrows(
        RuntimeException.class,
        () ->
            Keys.constructTufPublicKey(
                Hex.decode(
                    "302a300506032b65700321008b2e369230c3b97f4627fd6a59eb054a83ec15ed929ab3d983a40ffd322a223d"),
                "rsassa-pss-sha256"));
  }

  @Test
  void parsePkixPublicKey_rsa() throws NoSuchAlgorithmException, InvalidKeySpecException {
    var base64Key =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAghWkDAnX9F5QZZ9NxIWg9vcjULtD/kkbQwlcSm22e06FrgOdiFy1fKN/Ng32qEk1ZIKyi0HFzZxzPIcvg7eaFTRb7+AQiG6eMDmUzPGr67Jp0Di2ncH9+uOZmv4PVKovvQLq7qnEwbDk0HttxUscLQ2e36Lfv/2lpGW7apVmHVMoC5kwZ3KTiAk/DUtDhD4VQjU2rBy6OneO6pm6vdNzG4Jktjc0uUKFCRRUzydGEh05PgC9vSQu/EOiU+7aQPV1ZDUGpjg9tOM0SgaTOU3YSUfGiXZNHoiS2HwLyQPaxiHR2NPVH75bwnUFBHhdMxT1rhU+yLhXaweDQW6GQ0ti8wIDAQAB";
    Assertions.assertNotNull(Keys.parsePkixPublicKey(Base64.decode(base64Key), "RSA"));
  }

  @Test
  void parsePkixPublicKey_rsaKeyButWrongAlgorithm() {
    var base64Key =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAghWkDAnX9F5QZZ9NxIWg9vcjULtD/kkbQwlcSm22e06FrgOdiFy1fKN/Ng32qEk1ZIKyi0HFzZxzPIcvg7eaFTRb7+AQiG6eMDmUzPGr67Jp0Di2ncH9+uOZmv4PVKovvQLq7qnEwbDk0HttxUscLQ2e36Lfv/2lpGW7apVmHVMoC5kwZ3KTiAk/DUtDhD4VQjU2rBy6OneO6pm6vdNzG4Jktjc0uUKFCRRUzydGEh05PgC9vSQu/EOiU+7aQPV1ZDUGpjg9tOM0SgaTOU3YSUfGiXZNHoiS2HwLyQPaxiHR2NPVH75bwnUFBHhdMxT1rhU+yLhXaweDQW6GQ0ti8wIDAQAB";
    Assertions.assertThrows(
        InvalidKeySpecException.class,
        () -> Keys.parsePkixPublicKey(Base64.decode(base64Key), "EC"));
  }

  @Test
  void parsePkixPublicKey_eddsa() throws NoSuchAlgorithmException, InvalidKeySpecException {
    var base64Key = "MCowBQYDK2VwAyEAixzZOnx34hveTZ69J5iBCkmerK5Oh7EzJqTh3YY55jI=";
    Assertions.assertNotNull(Keys.parsePkixPublicKey(Base64.decode(base64Key), "EdDSA"));
  }

  @Test
  void parsePkixPublicKey_ecdsa() throws NoSuchAlgorithmException, InvalidKeySpecException {
    var base64Key =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEVqBnvab9XEVlTLW4iGKBIdrL6Sxf0x5vclZyXtR6hl79/o+RSgyr1ZQKLLCUC20imDWUgFMmfLu4UUiKNcI2uQ==";
    Assertions.assertNotNull(Keys.parsePkixPublicKey(Base64.decode(base64Key), "EC"));
  }

  @Test
  void parsePublicKey_failOnBadPEM() throws Exception {
    byte[] byteArray = "-----BEGIN A-----\nBBBBB-----END A".getBytes(StandardCharsets.UTF_8);
    Assertions.assertThrows(IOException.class, () -> Keys.parsePublicKey(byteArray));
  }
}

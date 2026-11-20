package com.example.customer_retail_system.system.returns.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed return label codes (G14): {@code RR-{rma}-{hmac8}}, where {@code hmac8} is the first
 * 8 hex characters of HMAC-SHA256(secret, rma). A label cannot be forged or re-pointed at
 * another return without the secret.
 */
public final class LabelCodec {

	private static final String PREFIX = "RR-";
	private static final int SIGNATURE_LENGTH = 8;

	private final SecretKeySpec key;

	public LabelCodec(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("Label secret must be set (app.label.secret)");
		}
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	public String encode(String rma) {
		return PREFIX + rma + "-" + signature(rma);
	}

	/** The RMA number if {@code labelCode} is well-formed and correctly signed; empty otherwise. */
	public Optional<String> verify(String labelCode) {
		if (labelCode == null || !labelCode.startsWith(PREFIX)) {
			return Optional.empty();
		}
		int dash = labelCode.lastIndexOf('-');
		if (dash <= PREFIX.length() || labelCode.length() - dash - 1 != SIGNATURE_LENGTH) {
			return Optional.empty();
		}
		String rma = labelCode.substring(PREFIX.length(), dash);
		byte[] given = labelCode.substring(dash + 1).getBytes(StandardCharsets.US_ASCII);
		byte[] expected = signature(rma).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(given, expected) ? Optional.of(rma) : Optional.empty();
	}

	private String signature(String rma) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			byte[] digest = mac.doFinal(rma.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, SIGNATURE_LENGTH);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 not available", ex);
		}
	}
}

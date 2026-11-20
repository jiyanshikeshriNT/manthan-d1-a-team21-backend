package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LabelCodecTest {

	final LabelCodec codec = new LabelCodec("unit-test-secret");

	@Test
	void encodesAsPrefixRmaAndEightHexSignature() {
		assertThat(codec.encode("RMA-100001")).matches("RR-RMA-100001-[0-9a-f]{8}");
		assertThat(codec.encode("RMA-100001")).isEqualTo(codec.encode("RMA-100001")); // deterministic
		assertThat(codec.encode("RMA-100001")).isNotEqualTo(codec.encode("RMA-100002"));
	}

	@Test
	void verifiesItsOwnLabels() {
		assertThat(codec.verify(codec.encode("RMA-100001"))).contains("RMA-100001");
	}

	@Test
	void rejectsTamperedOrForeignLabels() {
		String label = codec.encode("RMA-100001");
		String otherRmaSameSignature = label.replace("RMA-100001", "RMA-100002");
		char last = label.charAt(label.length() - 1);
		String flippedSignature = label.substring(0, label.length() - 1) + (last == '0' ? '1' : '0');

		assertThat(codec.verify(otherRmaSameSignature)).isEmpty();
		assertThat(codec.verify(flippedSignature)).isEmpty();
		assertThat(new LabelCodec("another-secret").verify(label)).isEmpty();
	}

	@Test
	void rejectsMalformedInput() {
		assertThat(codec.verify(null)).isEmpty();
		assertThat(codec.verify("")).isEmpty();
		assertThat(codec.verify("RMA-100001")).isEmpty();
		assertThat(codec.verify("RR-")).isEmpty();
		assertThat(codec.verify("RR-RMA-100001-abc")).isEmpty();
		assertThat(codec.verify("XX-RMA-100001-0123abcd")).isEmpty();
	}

	@Test
	void requiresASecret() {
		assertThatThrownBy(() -> new LabelCodec(" ")).isInstanceOf(IllegalArgumentException.class);
	}
}

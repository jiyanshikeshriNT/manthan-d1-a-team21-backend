package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class QrCodesTest {

	/**
	 * Regression for a defect found in Phase 5 testing: with a fixed error-correction level about 1% of
	 * labels (e.g. RR-RMA-100035-…) rendered QR codes that ZXing's detector could not locate. Every
	 * issued label must now decode with the warehouse decoder.
	 */
	@Test
	void everyIssuedLabelDecodesWithTheWarehouseDecoder() throws Exception {
		LabelCodec codec = new LabelCodec("qr-test-secret");
		List<String> failures = new ArrayList<>();
		for (int n = 100_001; n <= 100_500; n++) {
			String label = codec.encode("RMA-" + n);
			var image = ImageIO.read(new ByteArrayInputStream(QrCodes.png(label)));
			if (!QrCodes.decode(image).filter(label::equals).isPresent()) {
				failures.add(label);
			}
		}
		assertThat(failures).isEmpty();
	}

	@Test
	void imagesWithoutAQrCodeDecodeToNothing() {
		var blank = new java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
		assertThat(QrCodes.decode(blank)).isEmpty();
	}
}

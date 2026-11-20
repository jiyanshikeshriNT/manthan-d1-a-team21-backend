package com.example.customer_retail_system.system.returns.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * Renders and reads return-label QR codes (ZXing). Every rendered label is decoded again before it
 * is issued: ZXing's detector cannot locate roughly 1% of symbols (depending on content and error
 * correction level), so a label that fails the self-check is re-rendered at a higher level.
 */
public final class QrCodes {

	static final int SIZE_PX = 300;

	/** Q (25% recovery) suits printed labels that get creased; H is the fallback for the self-check. */
	private static final List<ErrorCorrectionLevel> LEVELS = List.of(ErrorCorrectionLevel.Q, ErrorCorrectionLevel.H);

	private QrCodes() {
	}

	static byte[] png(String content) {
		BufferedImage image = null;
		for (ErrorCorrectionLevel level : LEVELS) {
			image = render(content, level);
			if (decode(image).filter(content::equals).isPresent()) {
				break;
			}
		}
		return toPng(image);
	}

	/** Reads a QR code from an image (photo or screenshot of a label); empty if none is found. */
	public static Optional<String> decode(BufferedImage image) {
		Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
		hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
		hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
		Optional<String> text = read(image, hints);
		if (text.isEmpty()) {
			hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE); // clean, unrotated images (e.g. the PNG itself)
			text = read(image, hints);
		}
		return text;
	}

	private static Optional<String> read(BufferedImage image, Map<DecodeHintType, Object> hints) {
		try {
			return Optional.of(new MultiFormatReader()
					.decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))), hints)
					.getText());
		}
		catch (NotFoundException ex) {
			return Optional.empty();
		}
	}

	private static BufferedImage render(String content, ErrorCorrectionLevel level) {
		try {
			// Quiet zone of 4 modules, as the QR specification requires.
			return MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE_PX,
					SIZE_PX, Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.ERROR_CORRECTION, level)));
		}
		catch (WriterException ex) {
			throw new IllegalStateException("Could not encode QR code", ex);
		}
	}

	private static byte[] toPng(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "PNG", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}

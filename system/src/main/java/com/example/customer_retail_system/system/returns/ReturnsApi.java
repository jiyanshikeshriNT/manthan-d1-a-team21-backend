package com.example.customer_retail_system.system.returns;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import com.example.customer_retail_system.system.returns.internal.LabelCodec;
import com.example.customer_retail_system.system.returns.internal.QrCodes;
import com.example.customer_retail_system.system.returns.internal.ReturnRequestRepository;
import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of the returns module for other modules (inspection, refund, manager review). Returned
 * aggregates are meant to be used inside the caller's transaction, where status changes go through
 * {@link ReturnStateMachine}.
 */
@Service
public class ReturnsApi {

	static final String LABEL_INVALID_COPY = "This label isn't valid. Check you scanned the right parcel.";
	static final String RETURN_NOT_FOUND_COPY = "We couldn't find that return.";

	private final ReturnRequestRepository returns;
	private final LabelCodec labels;

	ReturnsApi(ReturnRequestRepository returns, LabelCodec labels) {
		this.returns = returns;
		this.labels = labels;
	}

	@Transactional
	public Optional<ReturnRequest> findByRma(String rma) {
		return returns.findByRmaNumber(rma);
	}

	@Transactional
	public ReturnRequest requireByRma(String rma) {
		return returns.findByRmaNumber(rma).orElseThrow(ReturnsApi::notFound);
	}

	@Transactional
	public ReturnRequest requireById(UUID id) {
		return returns.findWithItemsById(id).orElseThrow(ReturnsApi::notFound);
	}

	@Transactional
	public List<ReturnRequest> findByStatus(ReturnStatus status) {
		return returns.findByStatusOrderByCreatedAtAsc(status);
	}

	@Transactional
	public Map<UUID, String> rmaNumbers(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return returns.findByIdIn(ids).stream()
				.collect(Collectors.toMap(ReturnRequest::getId, ReturnRequest::getRmaNumber));
	}

	/** The RMA a scanned label belongs to; 400 LABEL_INVALID if the HMAC signature does not verify (G14). */
	public String rmaFromLabel(String labelCode) {
		return labels.verify(labelCode == null ? null : labelCode.trim())
				.orElseThrow(() -> new DomainException(ErrorCode.LABEL_INVALID, LABEL_INVALID_COPY));
	}

	/** Reads a QR code from a JPEG/PNG photo of a label and returns the (verified) label code. */
	public String decodeLabelImage(byte[] image) {
		BufferedImage bitmap;
		try {
			bitmap = ImageIO.read(new ByteArrayInputStream(image));
		}
		catch (IOException ex) {
			bitmap = null;
		}
		if (bitmap == null) {
			throw new DomainException(ErrorCode.LABEL_INVALID, "We couldn't read that image. Upload a JPEG or PNG photo.");
		}
		String text = QrCodes.decode(bitmap)
				.orElseThrow(() -> new DomainException(ErrorCode.LABEL_INVALID, "No QR code was found in that image."));
		rmaFromLabel(text);
		return text;
	}

	private static DomainException notFound() {
		return new DomainException(ErrorCode.RETURN_NOT_FOUND, RETURN_NOT_FOUND_COPY);
	}
}

package com.example.customer_retail_system.system.returns.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores return photos safely (G13): JPEG/PNG only, detected from magic bytes (the client's
 * content type and filename are ignored); at most 5 MB; stored under a server-generated UUID name
 * in {@code app.storage.dir}, so no user input ever reaches a file path. If the surrounding
 * transaction rolls back, the file is deleted again.
 */
public class PhotoStorage {

	public static final long MAX_BYTES = 5L * 1024 * 1024;
	public static final int MAX_PER_ITEM = 3;

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private final Path dir;

	public PhotoStorage(Path dir) {
		this.dir = dir.toAbsolutePath().normalize();
	}

	public record StoredPhoto(String storedName, String contentType, long sizeBytes) {
	}

	public StoredPhoto store(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw invalid("One of the photos is empty.");
		}
		if (file.getSize() > MAX_BYTES) {
			throw invalid("Each photo must be 5 MB or smaller.");
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw invalid("We couldn't read one of the photos. Please try again.");
		}
		if (bytes.length > MAX_BYTES) {
			throw invalid("Each photo must be 5 MB or smaller.");
		}
		String contentType = detect(bytes);
		if (contentType == null) {
			throw invalid("Photos must be JPEG or PNG images.");
		}
		String storedName = UUID.randomUUID() + (contentType.equals("image/png") ? ".png" : ".jpg");
		Path target = dir.resolve(storedName);
		try {
			Files.createDirectories(dir);
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not store photo", ex);
		}
		deleteOnRollback(target);
		return new StoredPhoto(storedName, contentType, bytes.length);
	}

	Path resolve(String storedName) {
		return dir.resolve(storedName);
	}

	/** {@code image/jpeg}, {@code image/png}, or null when the bytes are neither. */
	static String detect(byte[] b) {
		if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
			return "image/jpeg";
		}
		if (b.length >= PNG_SIGNATURE.length) {
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (b[i] != PNG_SIGNATURE[i]) {
					return null;
				}
			}
			return "image/png";
		}
		return null;
	}

	private static void deleteOnRollback(Path target) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					try {
						Files.deleteIfExists(target);
					}
					catch (IOException ignored) {
						// best effort: an orphan file is harmless and has no database row
					}
				}
			}
		});
	}

	private static DomainException invalid(String message) {
		return new DomainException(ErrorCode.PHOTO_INVALID, message);
	}
}

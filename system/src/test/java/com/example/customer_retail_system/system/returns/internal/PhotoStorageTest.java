package com.example.customer_retail_system.system.returns.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.example.customer_retail_system.system.shared.DomainException;
import com.example.customer_retail_system.system.shared.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PhotoStorageTest {

	static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
	static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	@TempDir
	Path dir;

	static byte[] withMagic(byte[] magic, int size) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 7);
		System.arraycopy(magic, 0, bytes, 0, magic.length);
		return bytes;
	}

	static MockMultipartFile file(String name, String declaredType, byte[] content) {
		return new MockMultipartFile("photos_1", name, declaredType, content);
	}

	@Test
	void storesJpegUnderServerGeneratedName() throws Exception {
		byte[] content = withMagic(JPEG_MAGIC, 1_000);
		var stored = new PhotoStorage(dir).store(file("holiday.jpeg", "image/jpeg", content));

		assertThat(stored.contentType()).isEqualTo("image/jpeg");
		assertThat(stored.sizeBytes()).isEqualTo(1_000);
		assertThat(stored.storedName()).matches("[0-9a-f-]{36}\\.jpg");
		assertThat(Files.readAllBytes(dir.resolve(stored.storedName()))).isEqualTo(content);
	}

	@Test
	void storesPngDetectedByMagicBytesRegardlessOfDeclaredType() {
		var stored = new PhotoStorage(dir).store(file("x.bin", "application/octet-stream", withMagic(PNG_MAGIC, 64)));
		assertThat(stored.contentType()).isEqualTo("image/png");
		assertThat(stored.storedName()).endsWith(".png");
	}

	@Test
	void rejectsNonImagesEvenWhenNamedAndTypedAsJpeg() {
		assertInvalid(file("evil.jpg", "image/jpeg", "<?php echo 'hi'; ?>".getBytes()),
				"Photos must be JPEG or PNG images.");
		assertInvalid(file("anim.gif", "image/gif", "GIF89a....".getBytes()), "Photos must be JPEG or PNG images.");
	}

	@Test
	void sizeLimitIsFiveMegabytesInclusive() {
		new PhotoStorage(dir).store(file("max.jpg", "image/jpeg", withMagic(JPEG_MAGIC, (int) PhotoStorage.MAX_BYTES)));
		assertInvalid(file("big.jpg", "image/jpeg", withMagic(JPEG_MAGIC, (int) PhotoStorage.MAX_BYTES + 1)),
				"Each photo must be 5 MB or smaller.");
	}

	@Test
	void rejectsEmptyFile() {
		assertInvalid(file("empty.jpg", "image/jpeg", new byte[0]), "One of the photos is empty.");
	}

	@Test
	void userSuppliedPathIsIgnored() throws Exception {
		var stored = new PhotoStorage(dir).store(file("../../etc/passwd.jpg", "image/jpeg", withMagic(JPEG_MAGIC, 10)));
		try (var files = Files.list(dir)) {
			assertThat(files).containsExactly(dir.resolve(stored.storedName()));
		}
		assertThat(stored.storedName()).doesNotContain("passwd").doesNotContain("..");
	}

	@Test
	void fileIsDeletedWhenTheTransactionRollsBack() {
		TransactionSynchronizationManager.initSynchronization();
		try {
			var stored = new PhotoStorage(dir).store(file("a.jpg", "image/jpeg", withMagic(JPEG_MAGIC, 10)));
			Path path = dir.resolve(stored.storedName());
			assertThat(path).exists();
			TransactionSynchronizationManager.getSynchronizations()
					.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
			assertThat(path).doesNotExist();
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void fileIsKeptWhenTheTransactionCommits() {
		TransactionSynchronizationManager.initSynchronization();
		try {
			var stored = new PhotoStorage(dir).store(file("a.png", "image/png", withMagic(PNG_MAGIC, 10)));
			TransactionSynchronizationManager.getSynchronizations()
					.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
			assertThat(dir.resolve(stored.storedName())).exists();
		}
		finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private void assertInvalid(MockMultipartFile file, String message) {
		assertThatThrownBy(() -> new PhotoStorage(dir).store(file))
				.isInstanceOfSatisfying(DomainException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ErrorCode.PHOTO_INVALID);
					assertThat(ex.getMessage()).isEqualTo(message);
				});
	}
}

package com.example.customer_retail_system.system.returns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Metadata of a stored photo. The file itself lives under {@code app.storage.dir} with a server-generated name. */
@Entity
@Table(name = "return_photo")
public class ReturnPhoto {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "return_item_id", nullable = false)
	private ReturnItem returnItem;

	@Column(name = "stored_name", nullable = false, unique = true, length = 64)
	private String storedName;

	@Column(name = "content_type", nullable = false, length = 20)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	protected ReturnPhoto() {
	}

	ReturnPhoto(ReturnItem returnItem, String storedName, String contentType, long sizeBytes) {
		this.returnItem = returnItem;
		this.storedName = storedName;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
	}

	public Long getId() {
		return id;
	}

	public String getStoredName() {
		return storedName;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}
}

package com.example.customer_retail_system.system.returns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** One order line (part or all of its quantity) inside a return. Inspection fields are filled in Phase 5. */
@Entity
@Table(name = "return_item")
public class ReturnItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "return_id", nullable = false)
	private ReturnRequest returnRequest;

	@Column(name = "order_line_id", nullable = false)
	private Long orderLineId;

	@Column(name = "requested_qty", nullable = false)
	private int requestedQty;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", nullable = false, length = 30)
	private ReasonCode reasonCode;

	@Column(name = "reason_text", length = 500)
	private String reasonText;

	@Column(name = "condition_grade", length = 1)
	private String conditionGrade;

	@Column(length = 10)
	private String decision;

	@Column(name = "accepted_qty")
	private Integer acceptedQty;

	@Column(name = "refund_paise")
	private Long refundPaise;

	@Column(name = "inspection_note", length = 500)
	private String inspectionNote;

	@OneToMany(mappedBy = "returnItem", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<ReturnPhoto> photos = new ArrayList<>();

	protected ReturnItem() {
	}

	ReturnItem(ReturnRequest returnRequest, long orderLineId, int requestedQty, ReasonCode reasonCode,
			String reasonText) {
		this.returnRequest = returnRequest;
		this.orderLineId = orderLineId;
		this.requestedQty = requestedQty;
		this.reasonCode = reasonCode;
		this.reasonText = reasonText;
	}

	public ReturnPhoto addPhoto(String storedName, String contentType, long sizeBytes) {
		ReturnPhoto photo = new ReturnPhoto(this, storedName, contentType, sizeBytes);
		photos.add(photo);
		return photo;
	}

	public Long getId() {
		return id;
	}

	public ReturnRequest getReturnRequest() {
		return returnRequest;
	}

	public long getOrderLineId() {
		return orderLineId;
	}

	public int getRequestedQty() {
		return requestedQty;
	}

	public ReasonCode getReasonCode() {
		return reasonCode;
	}

	public String getReasonText() {
		return reasonText;
	}

	public String getConditionGrade() {
		return conditionGrade;
	}

	public String getDecision() {
		return decision;
	}

	public Integer getAcceptedQty() {
		return acceptedQty;
	}

	public Long getRefundPaise() {
		return refundPaise;
	}

	public String getInspectionNote() {
		return inspectionNote;
	}

	public List<ReturnPhoto> getPhotos() {
		return Collections.unmodifiableList(photos);
	}
}

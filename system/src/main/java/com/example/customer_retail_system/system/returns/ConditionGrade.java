package com.example.customer_retail_system.system.returns;

/** Condition of a returned item at inspection (G5): A/B refund 100%, C refunds 50%. */
public enum ConditionGrade {
	A(100),
	B(100),
	C(50);

	private final int refundPercent;

	ConditionGrade(int refundPercent) {
		this.refundPercent = refundPercent;
	}

	public int refundPercent() {
		return refundPercent;
	}
}

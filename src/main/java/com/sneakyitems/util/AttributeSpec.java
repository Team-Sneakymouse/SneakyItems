package com.nisovin.magicspells.util;

/**
 * Bukkit-free parsed attribute line: {@code name amount [operation] [slot]}.
 */
public final class AttributeSpec {

	private final String attributeName;
	private final double amount;
	private final String operationName;
	private final String slotName;

	public AttributeSpec(String attributeName, double amount, String operationName, String slotName) {
		this.attributeName = attributeName;
		this.amount = amount;
		this.operationName = operationName;
		this.slotName = slotName;
	}

	public String attributeName() {
		return attributeName;
	}

	public double amount() {
		return amount;
	}

	/** Canonical operation name (e.g. {@code add_number}). */
	public String operationName() {
		return operationName;
	}

	/** Raw slot token, or {@code null} if omitted. */
	public String slotName() {
		return slotName;
	}

}

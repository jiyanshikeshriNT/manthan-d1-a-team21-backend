package com.example.customer_retail_system.system.shared;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks emails and phone numbers in free text before it reaches logs or the audit log. */
public final class PiiMasker {

	private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
	/** 10+ digits, optionally with a leading + and spaces/dashes between digits. */
	private static final Pattern PHONE = Pattern.compile("\\+?\\d(?:[ -]?\\d){9,}");

	private PiiMasker() {
	}

	/** {@code asha.rao@example.com} → {@code a***@example.com}; {@code +91 98765 43210} → {@code ***10}. */
	public static String mask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String masked = EMAIL.matcher(text).replaceAll("$1***@$2");
		Matcher m = PHONE.matcher(masked);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String digits = m.group().replaceAll("\\D", "");
			m.appendReplacement(out, Matcher.quoteReplacement("***" + digits.substring(digits.length() - 2)));
		}
		m.appendTail(out);
		return out.toString();
	}
}

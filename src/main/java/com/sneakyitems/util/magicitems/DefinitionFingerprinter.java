package com.sneakyitems.util.magicitems;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable FNV-1a 64-bit fingerprints for nested maps/lists/primitives.
 * Bukkit-free so unit tests do not need the server API.
 */
public final class DefinitionFingerprinter {

	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private DefinitionFingerprinter() {
	}

	public static long fingerprintString(String value) {
		if (value == null)
			return fingerprintBytes(new byte[] { 0 });
		return fingerprintBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Fingerprints a nested structure of {@link Map}, {@link Collection}, arrays, and scalars.
	 * Map keys are sorted for order independence.
	 */
	public static long fingerprintValue(Object value) {
		Hasher hasher = new Hasher();
		hashValue(hasher, value);
		return hasher.hash;
	}

	private static long fingerprintBytes(byte[] bytes) {
		Hasher hasher = new Hasher();
		hasher.update((byte) 'B');
		hasher.updateInt(bytes.length);
		for (byte b : bytes) {
			hasher.update(b);
		}
		return hasher.hash;
	}

	private static void hashValue(Hasher hasher, Object value) {
		if (value == null) {
			hasher.update((byte) 'N');
			return;
		}
		if (value instanceof Map<?, ?> map) {
			hasher.update((byte) 'M');
			TreeMap<String, Object> sorted = new TreeMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() == null)
					continue;
				sorted.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			hasher.updateInt(sorted.size());
			for (Map.Entry<String, Object> entry : sorted.entrySet()) {
				hashString(hasher, entry.getKey());
				hashValue(hasher, entry.getValue());
			}
			return;
		}
		if (value instanceof Collection<?> collection) {
			hasher.update((byte) 'L');
			hasher.updateInt(collection.size());
			for (Object element : collection) {
				hashValue(hasher, element);
			}
			return;
		}
		if (value.getClass().isArray()) {
			hasher.update((byte) 'A');
			int length = java.lang.reflect.Array.getLength(value);
			hasher.updateInt(length);
			for (int i = 0; i < length; i++) {
				hashValue(hasher, java.lang.reflect.Array.get(value, i));
			}
			return;
		}
		if (value instanceof Boolean bool) {
			hasher.update((byte) 'Z');
			hasher.update((byte) (bool ? 1 : 0));
			return;
		}
		if (value instanceof Number number) {
			hasher.update((byte) 'D');
			hashString(hasher, canonicalizeNumber(number));
			return;
		}
		hashString(hasher, String.valueOf(value));
	}

	private static String canonicalizeNumber(Number number) {
		if (number instanceof Double || number instanceof Float) {
			double d = number.doubleValue();
			if (d == Math.rint(d) && !Double.isInfinite(d))
				return Long.toString((long) d);
			return Double.toString(d);
		}
		return Long.toString(number.longValue());
	}

	private static void hashString(Hasher hasher, String value) {
		hasher.update((byte) 'S');
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		hasher.updateInt(bytes.length);
		for (byte b : bytes) {
			hasher.update(b);
		}
	}

	/** Convenience: copy a map with list values normalized to {@link ArrayList}. */
	public static Map<String, Object> copyAsSortedMap(Map<String, ?> source) {
		TreeMap<String, Object> copy = new TreeMap<>();
		if (source == null)
			return copy;
		for (Map.Entry<String, ?> entry : source.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Map<?, ?> nested) {
				@SuppressWarnings("unchecked")
				Map<String, Object> nestedMap = (Map<String, Object>) nested;
				copy.put(entry.getKey(), copyAsSortedMap(nestedMap));
			} else if (value instanceof Collection<?> collection) {
				copy.put(entry.getKey(), new ArrayList<>(collection));
			} else {
				copy.put(entry.getKey(), value);
			}
		}
		return copy;
	}

	private static final class Hasher {
		private long hash = FNV_OFFSET;

		void update(byte b) {
			hash ^= b & 0xffL;
			hash *= FNV_PRIME;
		}

		void updateInt(int value) {
			update((byte) (value >>> 24));
			update((byte) (value >>> 16));
			update((byte) (value >>> 8));
			update((byte) value);
		}
	}

}

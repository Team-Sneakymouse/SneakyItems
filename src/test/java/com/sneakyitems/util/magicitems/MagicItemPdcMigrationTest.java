package com.sneakyitems.util.magicitems;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MagicItemPdcMigrationTest {
    private static NamespacedKey key(String value) { return NamespacedKey.fromString(value); }

    @Test
    void updaterBehaviorSnapshotRestoresExactLegacyValuesWithoutNormalizationOrReset() throws Exception {
        PersistentDataContainer source = container();
        PersistentDataContainer replacement = container();
        String exactOwner = "ABCDEFAB-1234-5678-9ABC-ABCDEFABCDEF";
        long exactDeadline = 9007199254740993L;
        source.set(key("magicspells:soulbound_owner"), PersistentDataType.STRING, exactOwner);
        source.set(key("magicspells:expires_at"), PersistentDataType.LONG, exactDeadline);
        replacement.set(key("magicspells:soulbound_owner"), PersistentDataType.STRING, "new-definition-owner");
        replacement.set(key("magicspells:expires_at"), PersistentDataType.LONG, 1L);
        Class<?> snapshot = Class.forName("com.sneakyitems.util.magicitems.MagicItemBehaviorState");
        Object state = snapshot.getDeclaredMethod("capture", PersistentDataContainer.class).invoke(null, source);
        snapshot.getDeclaredMethod("restore", PersistentDataContainer.class).invoke(state, replacement);
        assertEquals(exactOwner, replacement.get(key("magicspells:soulbound_owner"), PersistentDataType.STRING));
        assertEquals(exactDeadline, replacement.get(key("magicspells:expires_at"), PersistentDataType.LONG));
        assertEquals(exactOwner, source.get(key("magicspells:soulbound_owner"), PersistentDataType.STRING));
        assertEquals(exactDeadline, source.get(key("magicspells:expires_at"), PersistentDataType.LONG));
    }

    @Test
    void updaterBehaviorSnapshotIgnoresWrongTypedLegacyKeys() {
        PersistentDataContainer source = container();
        PersistentDataContainer replacement = container();
        source.set(key("magicspells:soulbound_owner"), PersistentDataType.LONG, 42L);
        source.set(key("magicspells:expires_at"), PersistentDataType.STRING, "not-a-deadline");

        MagicItemBehaviorState state = assertDoesNotThrow(() -> MagicItemBehaviorState.capture(source));
        state.restore(replacement);

        assertFalse(replacement.has(key("magicspells:soulbound_owner")));
        assertFalse(replacement.has(key("magicspells:expires_at")));
        assertEquals(42L, source.get(key("magicspells:soulbound_owner"), PersistentDataType.LONG));
        assertEquals("not-a-deadline", source.get(key("magicspells:expires_at"), PersistentDataType.STRING));
    }

    @Test
    void migratesForeignTypedDataAndOldValueWinsConflicts() {
        PersistentDataContainer source = container();
        PersistentDataContainer target = container();
        source.set(key("foreign:counter"), PersistentDataType.INTEGER, 42);
        target.set(key("foreign:counter"), PersistentDataType.STRING, "definition");
        MagicItemPdcMigration.copy(source, target, "magicspells");
        assertEquals(42, target.get(key("foreign:counter"), PersistentDataType.INTEGER));
        assertEquals(42, source.get(key("foreign:counter"), PersistentDataType.INTEGER));
    }

    @Test
    void definitionOwnsPermanentDataIdentityAndVersion() {
        PersistentDataContainer source = container();
        PersistentDataContainer target = container();
        for (String name : new String[]{"magicspellpermanentdata_removed", "magicspellpermanentdata_changed", "magicitem", "magicitem_version"}) {
            source.set(key("magicspells:" + name), PersistentDataType.STRING, "old");
        }
        target.set(key("magicspells:magicspellpermanentdata_changed"), PersistentDataType.INTEGER, 7);
        target.set(key("magicspells:magicspellpermanentdata_new"), PersistentDataType.STRING, "new");
        target.set(key("magicspells:magicitem"), PersistentDataType.STRING, "definition");
        target.set(key("magicspells:magicitem_version"), PersistentDataType.LONG, 9L);
        source.set(key("foreign:magicspellpermanentdata_removed"), PersistentDataType.BYTE, (byte) 1);
        MagicItemPdcMigration.copy(source, target, "magicspells");
        assertFalse(target.has(key("magicspells:magicspellpermanentdata_removed")));
        assertEquals(7, target.get(key("magicspells:magicspellpermanentdata_changed"), PersistentDataType.INTEGER));
        assertEquals("new", target.get(key("magicspells:magicspellpermanentdata_new"), PersistentDataType.STRING));
        assertEquals("definition", target.get(key("magicspells:magicitem"), PersistentDataType.STRING));
        assertEquals(9L, target.get(key("magicspells:magicitem_version"), PersistentDataType.LONG));
        assertEquals((byte) 1, target.get(key("foreign:magicspellpermanentdata_removed"), PersistentDataType.BYTE));
        assertEquals("old", source.get(key("magicspells:magicspellpermanentdata_removed"), PersistentDataType.STRING));
    }

    @Test
    void definitionOwnsChangedAndRemovedTagsWithoutChangingSourceOrForeignTags() {
        PersistentDataContainer source = container();
        PersistentDataContainer changed = container();
        PersistentDataContainer removed = container();
        source.set(key("magicspells:tags"), PersistentDataType.STRING, "old");
        source.set(key("foreign:tags"), PersistentDataType.STRING, "foreign");
        changed.set(key("magicspells:tags"), PersistentDataType.STRING, "definition");

        MagicItemPdcMigration.copy(source, changed, "magicspells");
        MagicItemPdcMigration.copy(source, removed, "magicspells");

        assertAll(
                () -> assertEquals("definition", changed.get(key("magicspells:tags"), PersistentDataType.STRING)),
                () -> assertFalse(removed.has(key("magicspells:tags"))),
                () -> assertEquals("foreign", changed.get(key("foreign:tags"), PersistentDataType.STRING)),
                () -> assertEquals("foreign", removed.get(key("foreign:tags"), PersistentDataType.STRING)),
                () -> assertEquals("old", source.get(key("magicspells:tags"), PersistentDataType.STRING)),
                () -> assertEquals("foreign", source.get(key("foreign:tags"), PersistentDataType.STRING)));
    }

    @Test
    void preservesEveryPrimitiveAndNestedAndListData() {
        verifyType(PersistentDataType.BYTE, (byte) 1);
        verifyType(PersistentDataType.SHORT, (short) 2);
        verifyType(PersistentDataType.INTEGER, 3);
        verifyType(PersistentDataType.LONG, 4L);
        verifyType(PersistentDataType.FLOAT, 5F);
        verifyType(PersistentDataType.DOUBLE, 6D);
        verifyType(PersistentDataType.STRING, "seven");
        verifyType(PersistentDataType.BYTE_ARRAY, new byte[]{1, 2});
        verifyType(PersistentDataType.INTEGER_ARRAY, new int[]{3, 4});
        verifyType(PersistentDataType.LONG_ARRAY, new long[]{5, 6});
        verifyType(PersistentDataType.LIST.strings(), java.util.List.of("one", "two"));
        PersistentDataContainer nested = container();
        nested.set(key("nested:value"), PersistentDataType.INTEGER, 8);
        verifyType(PersistentDataType.TAG_CONTAINER, nested);
        verifyType(PersistentDataType.TAG_CONTAINER_ARRAY, new PersistentDataContainer[]{nested});
    }

    private <P, C> void verifyType(PersistentDataType<P, C> type, C value) {
        PersistentDataContainer source = container();
        PersistentDataContainer target = container();
        NamespacedKey name = key("magicspells:magicspelldata_typed");
        source.set(name, type, value);
        MagicItemPdcMigration.copy(source, target, "magicspells");
        assertTrue(target.has(name, type));
        assertTrue(java.util.Objects.deepEquals(value, target.get(name, type)));
    }

    @Test
    void updaterUsesMigrationAndDoesNotAllowAttributeOverridesOfPermanentDataOrTags() throws Exception {
        String updater = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/sneakyitems/util/magicitems/MagicItemUpdater.java"));
        assertTrue(updater.contains("MagicItemPdcMigration.copy(sourceContainer, meta.getPersistentDataContainer()"));
        assertEquals(2, updater.split("attr == MagicItemAttribute.PERMANENT_DATA", -1).length - 1);
        assertEquals(2, updater.split("attr == MagicItemAttribute.TAGS", -1).length - 1);
    }

    // Narrow in-memory implementation of the Bukkit PDC contract, not a server mock.
    // Values are stored using their primitive type; copyTo transfers opaque primitives.
    private record Value(Class<?> type, Object primitive) {}
    static PersistentDataContainer container() {
        Map<NamespacedKey, Value> values = new HashMap<>();
        PersistentDataAdapterContext context = () -> container();
        return (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAdapterContext": return context;
                        case "getKeys": return new HashSet<>(values.keySet());
                        case "isEmpty": return values.isEmpty();
                        case "remove": values.remove(args[0]); return null;
                        case "set": {
                            PersistentDataType type = (PersistentDataType) args[1];
                            values.put((NamespacedKey) args[0], new Value(type.getPrimitiveType(), type.toPrimitive(args[2], context)));
                            return null;
                        }
                        case "has": {
                            Value value = values.get(args[0]);
                            return value != null && (args.length == 1 || value.type() == ((PersistentDataType<?, ?>) args[1]).getPrimitiveType());
                        }
                        case "get": {
                            Value value = values.get(args[0]);
                            if (value == null) return null;
                            PersistentDataType type = (PersistentDataType) args[1];
                            if (value.type() != type.getPrimitiveType()) throw new IllegalArgumentException("Wrong primitive type");
                            return type.fromPrimitive(value.primitive(), context);
                        }
                        case "copyTo": {
                            PersistentDataContainer destination = (PersistentDataContainer) args[0];
                            for (var entry : values.entrySet()) {
                                if (!(boolean) args[1] && destination.has(entry.getKey())) continue;
                                Value value = entry.getValue();
                                PersistentDataType opaque = new PersistentDataType<Object, Object>() {
                                    public Class<Object> getPrimitiveType() { return (Class<Object>) value.type(); }
                                    public Class<Object> getComplexType() { return (Class<Object>) value.type(); }
                                    public Object toPrimitive(Object v, PersistentDataAdapterContext c) { return v; }
                                    public Object fromPrimitive(Object v, PersistentDataAdapterContext c) { return v; }
                                };
                                destination.set(entry.getKey(), opaque, value.primitive());
                            }
                            return null;
                        }
                        default: throw new UnsupportedOperationException(method.toString());
                    }
                });
    }
}

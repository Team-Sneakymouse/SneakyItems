package com.sneakyitems.util.magicitems;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedItemBehaviorOwnershipTest {
    @Test
    void sneakyItemsDelegatesSoulboundAndExpirationToSneakyUtils() throws Exception {
        Path behaviors = Path.of("src/main/java/com/sneakyitems/util/magicitems/MagicItemBehaviors.java");
        String source = Files.readString(behaviors);
        assertTrue(source.contains("ItemBehaviors.applySoulbound"));
        assertTrue(source.contains("ItemBehaviors.applyExpiration"));
        assertFalse(source.contains("System.currentTimeMillis"));
    }
}

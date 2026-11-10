package com.sneakyitems.util.magicitems;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.sneakyitems.SneakyItems;
import com.sneakyitems.integration.Probe;
import com.sneakyitems.util.performance.PerformanceDiagnostics;
import com.teamsneakymouse.sneakydiagnostics.api.Diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-item sections stay strictly nested even when item processing throws outside any enclosing section. */
class ItemTimingTest {

    /** Records the open sections like the service's recorder: a stack of tokens. */
    static final class RecordingProbe implements Diagnostics.Probe {
        final List<Long> open = new ArrayList<>();
        final List<String> ended = new ArrayList<>();
        long next = 1;

        @Override public long start() { open.add(next); return next++; }
        @Override public void end(String section, long token) { open.remove(token); ended.add(section); }
        @Override public void count(String key, long n) {}
        @Override public boolean sampleDue() { return false; }
        @Override public void level(String key, long value) {}
        @Override public boolean on() { return true; }
        @Override public void close() {}
    }

    private RecordingProbe recording;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        SneakyItems plugin = MockBukkit.load(SneakyItems.class);
        recording = new RecordingProbe();
        Probe shim = new Probe(plugin, "test");
        var field = Probe.class.getDeclaredField("probe");     // the shim's handle, as a capture would set it
        field.setAccessible(true);
        field.set(shim, recording);
        PerformanceDiagnostics.attach(shim);
    }

    @AfterEach
    void tearDown() {
        PerformanceDiagnostics.detach();
        MagicItemTagging.unregister(this);
        MockBukkit.unmock();
    }

    @Test
    void aTaggingFailureInAStandaloneItemCallStillEndsItsSection() {
        // A mapping that fails while tagging compares it: the call has no enclosing section to unwind it.
        MagicItemData broken = new MagicItemData() {
            @Override
            public boolean matches(MagicItemData data, EnumSet<MagicItemData.MagicItemAttribute> additionalIgnored) {
                throw new IllegalStateException("broken mapping");
            }
        };
        broken.setAttribute(MagicItemData.MagicItemAttribute.TYPE, Material.PAPER);
        MagicItemTagging.register(this, List.of(new MagicItemTagging.TagMapping("broken", broken, null)));
        var stack = new ItemStack(Material.PAPER);
        assertThrows(IllegalStateException.class, () -> MagicItemUpdater.processItemStack(stack, null));
        assertTrue(recording.ended.contains("item.tag"), "item.tag ended on the exception path");
        assertEquals(List.of(), recording.open, "no section left open for later calls to nest under");
    }

    @Test
    void callsFromOtherThreadsAreNotRecorded() throws Exception {
        Thread other = new Thread(() -> MagicItems.matches(null, new ItemStack(Material.PAPER)));
        other.start();
        other.join();
        long before = recording.next;
        Thread timed = new Thread(() -> PerformanceDiagnostics.start());
        timed.start();
        timed.join();
        assertEquals(before, recording.next, "the probe is server-thread only");
    }
}

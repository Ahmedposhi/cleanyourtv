package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LastOpenedTest {
    @Test public void newestPackagePicksLatestTimestamp() {
        Map<String, Object> all = new HashMap<>();
        all.put("com.netflix.ninja", 100L);
        all.put("org.smarttube.stable", 300L);
        all.put("com.disneyplus.mea", 200L);
        all.put("_latest", "ignored-string");
        assertEquals("org.smarttube.stable", LastOpened.newestPackage(all));
    }

    @Test public void newestPackageEmpty() {
        assertNull(LastOpened.newestPackage(new HashMap<>()));
    }

    @Test public void orderFromLegacySortsNewestFirst() {
        Map<String, Object> all = new HashMap<>();
        all.put("a", 1L);
        all.put("b", 3L);
        all.put("c", 2L);
        assertEquals(Arrays.asList("b", "c", "a"), LastOpened.orderFromLegacy(all));
    }

    @Test public void encodeDecodeRoundTrip() {
        List<String> order = Arrays.asList("com.netflix.ninja", "org.smarttube.stable");
        assertEquals(order, LastOpened.decode(LastOpened.encode(order)));
        assertTrue(LastOpened.decode("").isEmpty());
    }
}

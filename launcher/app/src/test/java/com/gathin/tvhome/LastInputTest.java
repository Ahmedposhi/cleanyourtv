package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LastInputTest {

    @Test
    public void prefersRememberedPrevious() {
        assertEquals("hdmi2", LastInput.other("hdmi1", "hdmi2", Arrays.asList("hdmi1", "hdmi2", "hdmi3")));
    }

    @Test
    public void skipsPreviousWhenSameAsCurrent() {
        assertEquals("hdmi2", LastInput.other("hdmi1", "hdmi1", Arrays.asList("hdmi1", "hdmi2")));
    }

    @Test
    public void picksOtherConnectedWithoutPrevious() {
        assertEquals("hdmi2", LastInput.other("hdmi1", "", Arrays.asList("hdmi1", "hdmi2")));
    }

    @Test
    public void nullWhenOnlyCurrentConnected() {
        assertNull(LastInput.other("hdmi1", "", Collections.singletonList("hdmi1")));
    }

    @Test
    public void nullWhenNoCurrentAndNoPrevious() {
        assertNull(LastInput.other("", "", Arrays.asList("hdmi1", "hdmi2")));
    }

    @Test
    public void previousWinsWithEmptyConnectedList() {
        assertEquals("hdmi2", LastInput.other("hdmi1", "hdmi2", Collections.emptyList()));
    }
}

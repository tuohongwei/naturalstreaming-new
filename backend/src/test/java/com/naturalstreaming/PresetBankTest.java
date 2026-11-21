package com.naturalstreaming;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PresetBankTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsBothLoopModesAndRejectsInvalidClipCount() throws Exception {
        ObjectNode aa = track("aa", 1, 0.5);
        ObjectNode ab = track("ab", 2, 0.5);

        assertDoesNotThrow(() -> PresetBank.validateTrackShape(aa));
        assertDoesNotThrow(() -> PresetBank.validateTrackShape(ab));
        assertThrows(ResponseStatusException.class, () -> PresetBank.validateTrackShape(track("ab", 1, 0.5)));
        assertThrows(ResponseStatusException.class, () -> PresetBank.validateTrackShape(track("aa", 2, 0.5)));
        assertThrows(ResponseStatusException.class, () -> PresetBank.validateTrackShape(track("aa", 1, 1.1)));
    }

    @Test
    void checksOverlapAgainstCroppedFrames() {
        assertDoesNotThrow(() -> PresetBank.validateClipBounds(24000, 72000, 96000, 48000, 0.5));
        assertThrows(ResponseStatusException.class,
                () -> PresetBank.validateClipBounds(24000, 72000, 96000, 48000, 0.5001));
        assertThrows(ResponseStatusException.class,
                () -> PresetBank.validateClipBounds(24000, 96001, 96000, 48000, 0));
        assertThrows(ResponseStatusException.class,
                () -> PresetBank.validateClipBounds(24000, 24000, 96000, 48000, 0));
    }

    private ObjectNode track(String loopMode, int clips, double gain) {
        ObjectNode track = json.createObjectNode().put("track_id", "rain").put("loop_mode", loopMode)
                .put("gain", gain).put("overlap_seconds", 0.5);
        var entries = track.putArray("clips");
        for (int index = 0; index < clips; index++) {
            entries.addObject();
        }
        return track;
    }
}

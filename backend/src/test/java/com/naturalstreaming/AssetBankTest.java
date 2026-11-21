package com.naturalstreaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssetBankTest {
    @Test
    void archivePathsCannotEscapeOrUseDriveSyntax() {
        assertEquals("group/clip.wav", AssetBank.safeEntry("group/clip.wav"));
        assertThrows(IllegalArgumentException.class, () -> AssetBank.safeEntry("../clip.wav"));
        assertThrows(IllegalArgumentException.class, () -> AssetBank.safeEntry("group/../clip.wav"));
        assertThrows(IllegalArgumentException.class, () -> AssetBank.safeEntry("C:/clip.wav"));
        assertThrows(IllegalArgumentException.class, () -> AssetBank.safeEntry("group\\clip.wav"));
    }

    @Test
    void analysisOnlyMetadataGetsStableId() throws Exception {
        String hash = "a".repeat(64);
        String json = """
                {"media_ref":"clip.wav","analysis":{"sha256":"%s"},"status":"unresolved"}
                """.formatted(hash);
        assertEquals("sha256-" + hash, AssetBank.validateMetadata(json, "clip.wav", hash));
    }

    @Test
    @EnabledIfSystemProperty(named = "ns.assetFixture", matches = ".+")
    void importsRealAudioBankAndMeasuresPresetRanges(@TempDir Path directory) throws Exception {
        var assets = new LinkedHashMap<String, Object[]>();
        var database = new JdbcTemplate() {
            @Override public int update(String sql, Object... args) {
                return assets.putIfAbsent((String) args[0], args) == null ? 1 : 0;
            }
            @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                return assets.containsKey(args[0]) ? List.of(type.cast(assets.get(args[0])[1])) : List.of();
            }
        };
        var bank = new AssetBank(database, directory.toString());
        ReflectionTestUtils.setField(bank, "ffmpeg", System.getProperty("ns.assetFixtureFfmpeg", "ffmpeg"));
        Path fixture = Path.of(System.getProperty("ns.assetFixture"));
        try (var input = Files.newInputStream(fixture)) {
            assertEquals(Map.of("added", 60, "reused", 0, "skipped_presets", 2), bank.importZip(input));
        }
        for (var asset : assets.values()) {
            assertTrue((Integer) asset[3] > 0);
            assertTrue((Long) asset[4] > 0);
            assertEquals((Long) asset[6], Files.size(bank.path((String) asset[0])));
        }
        var expectedRanges = Map.of(
                "80e7b05f2eb3f0a562c4c29f5b34a031b1e0c3d45342fa274e011cdbe98ce857", 1592728L,
                "841658846bad6abc0e2b3ddb3358c9e86d5cfebd416dd82bbb51014cd2843f22", 1084800L,
                "c4b7fb0deecc6e7ccc55784762d9c4ddc5ce25f7ac322317f54d8e0d09738994", 451200L,
                "bf4b74a2b29206f2b7eb4ce2be0e7074a1f266d131eb472da13ca11d910a70b7", 560000L,
                "50a8351d1f5d777651384e1856c77591e75a6f7a02e64b38361bb821b15fb8d4", 556800L);
        for (var required : expectedRanges.entrySet()) {
            var actual = assets.get(required.getKey());
            assertNotNull(actual, required.getKey());
            assertTrue((Long) actual[4] >= required.getValue(), () -> java.util.Arrays.toString(actual));
        }
    }
}

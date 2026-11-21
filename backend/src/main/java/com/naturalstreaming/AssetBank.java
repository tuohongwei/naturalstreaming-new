package com.naturalstreaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.DataInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class AssetBank {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "asset_id,sha256,filename,sample_rate,frames,byte_size";
    private static final long MIB = 1024L * 1024;
    private static final long MAX_PCM = 1024 * MIB;
    private final JdbcTemplate jdbc;
    private final Path directory;

    @Value("${ns.ffmpeg:ffmpeg}")
    private String ffmpeg = "ffmpeg";

    public AssetBank(JdbcTemplate jdbc, @Value("${ns.media-dir}") String mediaDir) {
        this.jdbc = jdbc;
        this.directory = Path.of(mediaDir).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM ns_asset ORDER BY asset_id");
    }

    public Map<String, Object> get(String sha) {
        requireHash(sha);
        var rows = jdbc.queryForList("SELECT " + COLUMNS + " FROM ns_asset WHERE sha256=?", sha);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Path path(String sha) {
        requireHash(sha);
        return directory.resolve(sha);
    }

    public void export(OutputStream output) throws IOException {
        var rows = jdbc.queryForList("SELECT " + COLUMNS + ",metadata FROM ns_asset ORDER BY asset_id");
        List<Map<String, Object>> index = new ArrayList<>();
        try (var zip = new ZipOutputStream(new FilterOutputStream(output) {
            @Override public void close() throws IOException { flush(); }
        })) {
            for (var row : rows) {
                var item = new LinkedHashMap<>(row);
                String metadata = (String) item.remove("metadata");
                String metadataPath = "assets/" + item.get("asset_id") + ".json";
                writeEntry(zip, metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
                item.put("metadata_path", metadataPath);
                item.put("metadata_status", JSON.readTree(metadata).path("schema_version").asText().equals("soundasset/v1")
                        ? "asset_description" : "analysis_only");
                index.add(item);
            }
            writeEntry(zip, "index.json", JSON.writeValueAsBytes(Map.of(
                    "schema_version", "asset-index/v1", "assets", index)));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importZip(InputStream archive) throws IOException {
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, ".import-");
        try {
            var entries = new LinkedHashMap<String, Path>();
            var budget = new Budget();
            unpack(archive, staging, "", entries, budget, true);
            var paired = new HashSet<String>();
            var pending = new ArrayList<Asset>();
            for (var entry : entries.entrySet()) {
                String key = entry.getKey();
                if (!audioName(key)) continue;
                String leaf = key.substring(key.lastIndexOf('/') + 1);
                Path sidecar = entries.get(key + ".json");
                if (sidecar == null) throw invalid("missing_sidecar", key);
                paired.add(key + ".json");
                String metadata = Files.readString(sidecar);
                MessageDigest digest = digest();
                try (var input = new DigestInputStream(Files.newInputStream(entry.getValue()), digest)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                String sha = HexFormat.of().formatHex(digest.digest());
                String id = validateMetadata(metadata, leaf, sha);
                Signal signal = decode(entry.getValue(), leaf);
                pending.add(new Asset(entry.getValue(), leaf, sha, id, metadata, signal));
            }
            int skippedPresets = 0;
            for (var entry : entries.entrySet()) {
                if (!entry.getKey().endsWith(".json") || paired.contains(entry.getKey())) continue;
                JsonNode node = parseJson(Files.readString(entry.getValue()), entry.getKey());
                if (node != null && node.path("schema_version").asText().equals("playback-preset/v1")) {
                    skippedPresets++;
                } else {
                    throw invalid("unpaired_sidecar", entry.getKey());
                }
            }
            if (pending.isEmpty()) throw invalid("no_audio", "ZIP");
            int added = 0;
            int reused = 0;
            for (Asset asset : pending) {
                Path destination = path(asset.sha);
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createLink(destination, asset.file);
                    } catch (FileAlreadyExistsException ignored) {
                        // A concurrent import has written the same content-addressed file.
                    }
                }
                if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Media storage contains a non-file entry");
                }
                long size = Files.size(asset.file);
                int result = jdbc.update("""
                        INSERT IGNORE INTO ns_asset(sha256,asset_id,filename,sample_rate,frames,metadata,byte_size)
                        VALUES(?,?,?,?,?,?,?)
                        """, asset.sha, asset.id, asset.filename, asset.signal.sampleRate,
                        asset.signal.frames, asset.metadata, size);
                var identities = jdbc.queryForList("SELECT asset_id FROM ns_asset WHERE sha256=? FOR UPDATE",
                        String.class, asset.sha);
                if (identities.isEmpty() || !asset.id.equals(identities.getFirst())) {
                    throw invalid("asset_identity_conflict", asset.id);
                }
                if (result == 1) added++;
                else reused++;
            }
            return Map.of("added", added, "reused", reused, "skipped_presets", skippedPresets);
        } finally {
            try (var files = Files.list(staging)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(staging);
        }
    }

    private static void unpack(InputStream input, Path stage, String namespace, Map<String, Path> entries,
                               Budget budget, boolean allowNested) throws IOException {
        Set<String> distinct = new HashSet<>();
        try (var zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++budget.entries > 4096) throw invalid("archive_entry_limit", "ZIP");
                String raw = entry.getName();
                String name = safeEntry(entry.isDirectory() ? raw.substring(0, raw.length() - 1) : raw);
                if (!distinct.add(name)) throw invalid("duplicate_entry", name);
                boolean mac = List.of(name.split("/")).stream()
                        .anyMatch(part -> part.equals("__MACOSX") || part.equals(".DS_Store"));
                if (entry.isDirectory() || mac) {
                    boundedCopy(zip, OutputStream.nullOutputStream(), MIB, budget);
                    continue;
                }
                boolean nested = name.toLowerCase(Locale.ROOT).endsWith(".zip");
                if (nested && !allowNested) throw invalid("nested_archive_depth", name);
                if (!nested && !audioName(name) && !name.endsWith(".json")) {
                    // Preserve support for export-bundle manifests or harmless archive artefacts.
                    boundedCopy(zip, OutputStream.nullOutputStream(), MIB, budget);
                    continue;
                }
                Path temporary = Files.createTempFile(stage, "entry-", ".bin");
                long max = nested ? 256 * MIB : name.endsWith(".json") ? 4 * MIB : 128 * MIB;
                try (var output = Files.newOutputStream(temporary)) {
                    boundedCopy(zip, output, max, budget);
                }
                if (nested) {
                    unpack(Files.newInputStream(temporary), stage, namespace + name + "!/", entries, budget, false);
                } else if (entries.putIfAbsent(namespace + name, temporary) != null) {
                    throw invalid("duplicate_entry", name);
                }
            }
        }
    }

    static String safeEntry(String name) {
        if (name.isBlank() || name.length() > 1024 || name.contains("\\") || name.contains(":")) {
            throw invalid("unsafe_entry", name);
        }
        for (String part : name.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.length() > 255
                    || part.chars().anyMatch(Character::isISOControl)) {
                throw invalid("unsafe_entry", name);
            }
        }
        return name;
    }

    static String validateMetadata(String metadata, String filename, String sha) throws IOException {
        JsonNode json = parseJson(metadata, filename);
        if (json == null || !json.isObject() || !filename.equals(json.path("media_ref").asText())) {
            throw invalid("media_ref_mismatch", filename);
        }
        boolean descriptor = json.path("schema_version").asText().equals("soundasset/v1");
        JsonNode declaredHash = descriptor ? json.path("sha256") : json.path("analysis").path("sha256");
        if (!sha.equals(declaredHash.asText())) throw invalid("hash_mismatch", filename);
        String id = descriptor ? json.path("asset_id").asText() : "sha256-" + sha;
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) throw invalid("invalid_asset_id", filename);
        return id;
    }

    private static JsonNode parseJson(String source, String subject) {
        try {
            return JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(source);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exp) {
            throw invalid("invalid_json", subject);
        }
    }

    private Signal decode(Path file, String filename) throws IOException {
        String demuxer = filename.toLowerCase(Locale.ROOT).endsWith(".wav") ? "wav" : "mov";
        Process process = new ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-xerror",
                "-protocol_whitelist", "file,pipe", "-f", demuxer,
                "-i", file.toString(), "-map", "0:a:0", "-c:a", "pcm_s16le", "-f", "wav", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var timeout = CompletableFuture.runAsync(() -> {
            if (process.isAlive()) process.destroyForcibly();
        }, CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS));
        try (var input = new DataInputStream(process.getInputStream())) {
            Signal reading = parsePcm(input, filename);
            if (process.waitFor() != 0) throw invalid("audio_decode_failed", filename);
            return reading;
        } catch (InterruptedException exp) {
            Thread.currentThread().interrupt();
            throw new IOException("Audio processing interrupted", exp);
        } catch (java.io.EOFException exp) {
            throw invalid("audio_decode_failed", filename);
        } finally {
            timeout.cancel(false);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static Signal parsePcm(DataInputStream input, String filename) throws IOException {
        if (input.readInt() != 0x52494646) throw invalid("audio_decode_failed", filename);
        input.readInt();
        if (input.readInt() != 0x57415645) throw invalid("audio_decode_failed", filename);
        int rate = 0;
        int channels = 0;
        while (true) {
            int chunk = input.readInt();
            long size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (chunk == 0x64617461) {
                long bytes = boundedCopy(input, OutputStream.nullOutputStream(), MAX_PCM, null);
                if (rate <= 0 || channels <= 0 || bytes < 2L * channels || bytes % (channels * 2L) != 0) {
                    throw invalid("audio_decode_failed", filename);
                }
                return new Signal(rate, bytes / (channels * 2L));
            }
            if (size > MIB) throw invalid("audio_decode_failed", filename);
            if (chunk == 0x666d7420 && size >= 16) {
                input.readShort();
                channels = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
                rate = Integer.reverseBytes(input.readInt());
                size -= 8;
            }
            input.skipNBytes(size + size % 2);
        }
    }

    private static boolean audioName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".wav") || lower.endsWith(".m4a");
    }

    private static long boundedCopy(InputStream input, OutputStream output, long max, Budget budget)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long bytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes += read;
            if (budget != null) budget.expanded += read;
            if (bytes > max || (budget != null && budget.expanded > 768 * MIB)) {
                throw invalid("size_limit", "ZIP entry or decoded audio");
            }
            output.write(buffer, 0, read);
        }
        return bytes;
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void requireHash(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw invalid("invalid_sha256", "");
    }

    private static IllegalArgumentException invalid(String code, String subject) {
        return new IllegalArgumentException(code + ": " + subject);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exp) {
            throw new IllegalStateException(exp);
        }
    }

    record Signal(int sampleRate, long frames) {}
    private record Asset(Path file, String filename, String sha, String id, String metadata, Signal signal) {}
    private static final class Budget {
        int entries;
        long expanded;
    }
}

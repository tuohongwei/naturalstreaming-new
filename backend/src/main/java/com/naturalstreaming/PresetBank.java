package com.naturalstreaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PresetBank {
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,95}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();

    public PresetBank(JdbcTemplate db) {
        this.db = db;
    }

    public Object themes() {
        return db.queryForList("SELECT id,name FROM ns_theme ORDER BY id");
    }

    public Object createTheme(JsonNode request) {
        String id = id(request, "id");
        String name = text(request, "name").strip();
        require(!name.isEmpty() && name.length() <= 160, "name must have 1–160 characters");
        try {
            db.update("INSERT INTO ns_theme(id,name) VALUES (?,?)", id, name);
        } catch (DuplicateKeyException duplicate) {
            String existing = db.queryForObject("SELECT name FROM ns_theme WHERE id=?", String.class, id);
            if (!name.equals(existing)) {
                throw conflict("theme id already exists");
            }
        }
        return Map.of("id", id, "name", name);
    }

    public Object drafts() {
        return db.query("""
                SELECT r.document,r.published,COALESCE(p.current_revision=r.revision,FALSE) AS current_flag
                FROM ns_preset_revision r JOIN ns_preset p ON p.id=r.preset_id
                ORDER BY r.preset_id,r.revision DESC
                """, (row, index) -> read(row.getString(1), row.getBoolean(2), row.getBoolean(3)));
    }

    @Transactional
    public Object importPreset(JsonNode request) {
        require(request != null && request.isObject(), "preset must be an object");
        require("playback-preset/v1".equals(text(request, "schema_version")), "unsupported preset schema");
        String presetId = id(request, "preset_id");
        long revision = integer(request, "revision");
        require(revision > 0, "revision must be positive");
        JsonNode target = request.path("target");
        String scene = id(target, "scene_id");
        String weather = id(target, "weather_id");
        String function = id(target, "function_id");
        Set<String> hashes = validateTracks(request.path("tracks"));
        Boolean themeExists = db.queryForObject("SELECT EXISTS(SELECT 1 FROM ns_theme WHERE id=?)", Boolean.class, scene);
        require(Boolean.TRUE.equals(themeExists), "target.scene_id must reference an existing theme");

        db.update("""
                INSERT INTO ns_preset(id,scene_id,weather_id,function_id) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, presetId, scene, weather, function);
        List<Target> existing = db.query("""
                SELECT scene_id,weather_id,function_id,current_revision
                FROM ns_preset WHERE id=? FOR UPDATE
                """, (row, index) -> new Target(row.getString(1), row.getString(2),
                        row.getString(3), row.getObject(4, Long.class)), presetId);
        if (existing.isEmpty()) {
            throw conflict("target already belongs to another preset");
        }
        Target old = existing.getFirst();
        if (!old.scene().equals(scene) || !old.weather().equals(weather) || !old.function().equals(function)) {
            throw conflict("a preset id keeps its original target");
        }

        ObjectNode normalized = ((ObjectNode) request).deepCopy();
        normalized.remove(List.of("published", "current"));
        normalized.put("status", "draft");
        List<StoredRevision> stored = db.query("""
                SELECT document,published FROM ns_preset_revision WHERE preset_id=? AND revision=? FOR UPDATE
                """, (row, index) -> new StoredRevision(row.getString(1), row.getBoolean(2)), presetId, revision);
        if (!stored.isEmpty()) {
            StoredRevision previous = stored.getFirst();
            if (!normalized.equals(parse(previous.document()))) {
                throw conflict("revision already exists with different rules");
            }
            return read(previous.document(), previous.published(), Long.valueOf(revision).equals(old.current()));
        }

        if (old.current() != null && revision <= old.current()) {
            throw conflict("new revision must be newer than the current published revision");
        }
        db.update("INSERT INTO ns_preset_revision(preset_id,revision,document) VALUES (?,?,?)",
                presetId, revision, normalized.toString());
        for (String hash : hashes) {
            db.update("INSERT INTO ns_preset_asset(preset_id,revision,sha256) VALUES (?,?,?)",
                    presetId, revision, hash);
        }
        return read(normalized.toString(), false, false);
    }

    @Transactional
    public Object publish(String presetId, long revision) {
        List<Long> pointers = db.query("SELECT current_revision FROM ns_preset WHERE id=? FOR UPDATE",
                (row, index) -> row.getLong(1), presetId);
        if (pointers.isEmpty()) {
            throw notFound();
        }
        List<String> revisions = db.query("SELECT document FROM ns_preset_revision WHERE preset_id=? AND revision=? FOR UPDATE",
                (row, index) -> row.getString(1), presetId, revision);
        if (revisions.isEmpty()) {
            throw notFound();
        }
        long current = pointers.getFirst();
        if (revision < current) {
            throw conflict("published revision cannot move backward");
        }
        if (revision != current) {
            db.update("UPDATE ns_preset_revision SET published=TRUE WHERE preset_id=? AND revision=?",
                    presetId, revision);
            db.update("UPDATE ns_preset SET current_revision=? WHERE id=?", revision, presetId);
        }
        return read(revisions.getFirst(), true, true);
    }

    public Object catalog() {
        return db.query("""
                SELECT p.id,p.current_revision,p.scene_id,p.weather_id,p.function_id,t.name
                FROM ns_preset p JOIN ns_theme t ON t.id=p.scene_id
                WHERE p.current_revision IS NOT NULL ORDER BY p.id
                """, (row, index) -> Map.of("preset_id", row.getString(1), "revision", row.getLong(2),
                        "target", Map.of("scene_id", row.getString(3), "weather_id", row.getString(4),
                                "function_id", row.getString(5)), "theme_name", row.getString(6)));
    }

    public Object version(String presetId, long revision) {
        List<String> documents = db.query(
                "SELECT document FROM ns_preset_revision WHERE preset_id=? AND revision=? AND published=TRUE",
                (row, index) -> row.getString(1), presetId, revision);
        if (documents.isEmpty()) {
            throw notFound();
        }
        List<Map<String, Object>> assets = db.queryForList("""
                SELECT a.asset_id,a.sha256,a.filename,a.sample_rate,a.frames,a.byte_size
                FROM ns_preset_asset r JOIN ns_asset a ON a.sha256=r.sha256
                WHERE r.preset_id=? AND r.revision=? ORDER BY a.sha256
                """, presetId, revision);
        ObjectNode preset = parse(documents.getFirst());
        preset.put("status", "published");
        return Map.of("preset", toMap(preset), "assets", assets);
    }

    public boolean publishedAsset(String sha256) {
        return SHA256.matcher(sha256).matches() && Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM ns_preset_asset a JOIN ns_preset_revision r
                    ON r.preset_id=a.preset_id AND r.revision=a.revision
                    WHERE a.sha256=? AND r.published=TRUE
                )
                """, Boolean.class, sha256));
    }

    private Set<String> validateTracks(JsonNode tracks) {
        require(tracks.isArray() && !tracks.isEmpty(), "tracks must be a nonempty array");
        Set<String> trackIds = new HashSet<>();
        Set<String> hashes = new LinkedHashSet<>();
        for (JsonNode track : tracks) {
            validateTrackShape(track);
            String trackId = id(track, "track_id");
            require(trackIds.add(trackId), "duplicate track_id " + trackId);
            double overlap = track.path("overlap_seconds").doubleValue();
            for (JsonNode clip : track.path("clips")) {
                String hash = text(clip, "sha256");
                String assetId = text(clip, "asset_id");
                require(SHA256.matcher(hash).matches(), "invalid clip sha256 in track " + trackId);
                List<AssetFrames> assets = db.query("SELECT asset_id,sample_rate,frames FROM ns_asset WHERE sha256=?",
                        (row, index) -> new AssetFrames(row.getString(1), row.getInt(2), row.getLong(3)), hash);
                require(!assets.isEmpty(), "missing asset " + hash + " in track " + trackId);
                AssetFrames asset = assets.getFirst();
                require(asset.id().equals(assetId), "asset_id does not match SHA-256 in track " + trackId);
                validateClipBounds(integer(clip, "start_frame"), integer(clip, "end_frame_exclusive"),
                        asset.frames(), asset.sampleRate(), overlap);
                hashes.add(hash);
            }
        }
        return hashes;
    }

    static void validateTrackShape(JsonNode track) {
        String mode = text(track, "loop_mode");
        require("aa".equals(mode) || "ab".equals(mode), "loop_mode must be aa or ab");
        JsonNode clips = track.path("clips");
        require(clips.isArray() && clips.size() == ("aa".equals(mode) ? 1 : 2), "invalid clip count for " + mode);
        double gain = number(track, "gain");
        require(gain >= 0 && gain <= 1, "gain must be between 0 and 1");
        require(number(track, "overlap_seconds") >= 0, "overlap_seconds must be nonnegative");
    }

    static void validateClipBounds(long start, long end, long frames, int sampleRate, double overlap) {
        require(start >= 0 && end > start && end <= frames && sampleRate > 0, "clip frame range exceeds asset");
        require(overlap <= ((double) (end - start)) / (2 * (double) sampleRate),
                "overlap exceeds half of the cropped clip duration");
    }

    private Map<String, Object> read(String document, boolean published, boolean current) {
        ObjectNode preset = parse(document).put("status", published ? "published" : "draft")
                .put("published", published).put("current", current);
        return toMap(preset);
    }

    private Map<String, Object> toMap(ObjectNode preset) {
        return json.convertValue(preset, new TypeReference<Map<String, Object>>() {});
    }

    private ObjectNode parse(String document) {
        try {
            return (ObjectNode) json.readTree(document);
        } catch (JsonProcessingException invalidStorage) {
            throw new IllegalStateException("invalid stored preset", invalidStorage);
        }
    }

    private static String id(JsonNode value, String field) {
        String identifier = text(value, field);
        require(ID.matcher(identifier).matches(), "invalid " + field);
        return identifier;
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual() && !node.textValue().isBlank(), "missing or invalid " + field);
        return node.textValue();
    }

    private static long integer(JsonNode value, String field) {
        JsonNode node = value.path(field);
        require(node.isIntegralNumber() && node.canConvertToLong(), "invalid " + field);
        return node.longValue();
    }

    private static double number(JsonNode value, String field) {
        JsonNode node = value.path(field);
        require(node.isNumber() && Double.isFinite(node.doubleValue()), "invalid " + field);
        return node.doubleValue();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
        }
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "preset revision not found");
    }

    private record Target(String scene, String weather, String function, Long current) {}
    private record StoredRevision(String document, boolean published) {}
    private record AssetFrames(String id, int sampleRate, long frames) {}
}

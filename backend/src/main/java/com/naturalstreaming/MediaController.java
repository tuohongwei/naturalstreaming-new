package com.naturalstreaming;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
public class MediaController {
    private final Sessions sessions;
    private final PresetBank presets;
    private final AssetBank assets;

    MediaController(Sessions sessions, PresetBank presets, AssetBank assets) {
        this.sessions = sessions;
        this.presets = presets;
        this.assets = assets;
    }

    @GetMapping("/v1/media/{sha}")
    ResponseEntity<Resource> download(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String sha) {
        sessions.require("app", HttpResults.bearer(authorization));
        if (!presets.publishedAsset(sha)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "asset_not_published");
        return resource(assets, sha);
    }

    static ResponseEntity<Resource> resource(AssetBank assets, String sha) {
        var asset = assets.get(sha);
        Path path = assets.path(sha);
        if (asset == null || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "asset_missing");
        String name = asset.get("filename").toString().toLowerCase(java.util.Locale.ROOT);
        MediaType type = MediaType.parseMediaType(name.endsWith(".wav") ? "audio/wav" : "audio/mp4");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type).body(new FileSystemResource(path));
    }
}

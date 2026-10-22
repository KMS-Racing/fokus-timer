package dev.worldplanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Liest und schreibt JSON-Dateien. Schreiben passiert "atomar", damit bei einem Absturz keine halbe Datei übrig bleibt. */
final class JsonFiles {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private JsonFiles() {}

    /** Gibt null zurück, wenn die Datei (noch) nicht existiert. */
    static JsonElement read(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    }

    static void write(Path path, JsonElement json, boolean pretty) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, (pretty ? PRETTY : GSON).toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

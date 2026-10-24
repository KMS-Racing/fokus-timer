package dev.worldplanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
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

    /**
     * Schreibt erst in eine .tmp-Datei, zwingt das Betriebssystem, sie wirklich auf die Platte zu schreiben
     * (force), und benennt sie dann um. So gibt es nach einem Absturz oder Stromausfall entweder die alte
     * oder die neue Datei – aber nie eine halbe.
     */
    static void write(Path path, JsonElement json, boolean pretty) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = ((pretty ? PRETTY : GSON).toJson(json) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

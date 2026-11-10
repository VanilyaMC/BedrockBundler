package dev.bedrockbundler.paper.itemsadder;

import dev.bedrockbundler.common.input.EmoteAnimation;
import dev.bedrockbundler.paper.EmotePlayer;
import dev.lone.itemsadder.api.Events.PlayerEmoteEndEvent;
import dev.lone.itemsadder.api.Events.PlayerEmotePlayEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** ItemsAdder emotes: their animation files for the converter, and their events for Bedrock viewers. */
final class ItemsAdderEmotes implements Listener {

    static final String FILE_NAME = ".player_advanced_animations";
    /** ItemsAdder's built-in emotes, a resource of its jar in the same format. */
    static final String BUILT_IN = "internal/player/player.player_animations";

    private final EmotePlayer player;

    ItemsAdderEmotes(EmotePlayer player) {
        this.player = player;
    }

    /** Every player animation file in ItemsAdder's content packs ({@code contents/<pack>/.../models/...}). */
    static List<EmoteAnimation> read(Path contents) throws IOException {
        List<EmoteAnimation> emotes = new ArrayList<>();
        if (!Files.isDirectory(contents)) {
            return emotes;
        }
        try (Stream<Path> files = Files.walk(contents)) {
            for (Path file : files.filter(p -> p.getFileName().toString().equals(FILE_NAME)).sorted().toList()) {
                emotes.add(new EmoteAnimation(contents.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return emotes;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlay(PlayerEmotePlayEvent event) {
        player.play(event.getPlayer(), event.getEmoteName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(PlayerEmoteEndEvent event) {
        player.stop(event.getPlayer(), event.getEmoteName());
    }
}

package dev.bedrockbundler.paper;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;
import java.util.function.Predicate;

/** Tells Bedrock (Geyser) players apart from Java players. */
final class BedrockPlayers {

    private final Predicate<UUID> check;
    private final String source;

    private BedrockPlayers(Predicate<UUID> check, String source) {
        this.check = check;
        this.source = source;
    }

    /**
     * Floodgate works in every setup (it runs on the backend even when Geyser is on a proxy); the Geyser API is
     * a fallback when Geyser-Spigot runs on this server without Floodgate.
     *
     * @return null when neither is installed
     */
    static BedrockPlayers detect(Server server) {
        if (server.getPluginManager().isPluginEnabled("floodgate")) {
            return new BedrockPlayers(uuid -> FloodgateApi.getInstance().isFloodgatePlayer(uuid), "Floodgate");
        }
        if (server.getPluginManager().isPluginEnabled("Geyser-Spigot")) {
            return new BedrockPlayers(uuid -> GeyserApi.api().isBedrockPlayer(uuid), "Geyser");
        }
        return null;
    }

    boolean isBedrock(Player player) {
        try {
            return check.test(player.getUniqueId());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    String source() {
        return source;
    }
}

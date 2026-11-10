package dev.bedrockbundler.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class BedrockBundlerCommand implements TabExecutor {

    private final BedrockBundlerPlugin plugin;

    BedrockBundlerCommand(BedrockBundlerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Locale.ROOT: on Turkish systems a default-locale toLowerCase turns 'I' into a dotless 'ı'.
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload", "rl" -> {
                boolean force = Arrays.asList(args).contains("-f");
                sender.sendMessage("[BedrockBundler] Converting " + plugin.source().pluginName() + " content" + (force ? " (forced)" : "") + "...");
                plugin.convertFromDisk(force, sender);
            }
            case "status" -> status(sender);
            case "animate" -> animate(sender, label, args);
            default -> sender.sendMessage("Usage: /" + label + " <reload [-f]|status|animate <animation> [player]>");
        }
        return true;
    }

    /** Plays a Bedrock animation on a player for Bedrock viewers, e.g. animation.player.sleeping. */
    private void animate(CommandSender sender, String label, String[] args) {
        org.bukkit.entity.Player target = args.length >= 3 ? plugin.getServer().getPlayerExact(args[2])
                : sender instanceof org.bukkit.entity.Player self ? self : null;
        if (args.length < 2 || target == null) {
            sender.sendMessage("Usage: /" + label + " animate <animation> [player]");
            return;
        }
        String animation = args[1].startsWith("animation.") ? args[1]
                : dev.bedrockbundler.common.convert.EmoteConverter.animation(args[1]);
        target.getScheduler().run(plugin, t -> sender.sendMessage("[BedrockBundler] " + animation + " sent to "
                + plugin.emotePlayer().test(target, animation) + " Bedrock player(s)"), null);
    }

    private void status(CommandSender sender) {
        ConversionService service = plugin.conversion();
        ConversionService.Status status = service.status();
        sender.sendMessage("[BedrockBundler] Target: " + service.deployer().describe());
        if (status == null) {
            sender.sendMessage("[BedrockBundler] No conversion in this session yet. Run /bedrockbundler reload.");
        } else {
            sender.sendMessage(String.format("[BedrockBundler] Last run %s: %d items, %d blocks, %d entity blocks skipped, %d warnings%s",
                    status.time(), status.items(), status.blocks(), status.skippedBlocks(), status.warnings(),
                    status.converted() ? "" : " (unchanged)"));
            sender.sendMessage("[BedrockBundler] Bundle hash: " + status.hash().substring(0, 12));
        }
        if (service.restartRequired()) {
            sender.sendMessage("[BedrockBundler] Geyser is running an older bundle; restart it to apply the new one.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status", "animate").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return List.of("-f");
        }
        return List.of();
    }
}

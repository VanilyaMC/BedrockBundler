package dev.bedrockadder.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class BedrockAdderCommand implements TabExecutor {

    private final BedrockAdderPlugin plugin;

    BedrockAdderCommand(BedrockAdderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Locale.ROOT: on Turkish systems a default-locale toLowerCase turns 'I' into a dotless 'ı'.
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload", "rl" -> {
                boolean force = Arrays.asList(args).contains("-f");
                sender.sendMessage("[BedrockAdder] Converting " + plugin.source().pluginName() + " content" + (force ? " (forced)" : "") + "...");
                plugin.convertFromDisk(force, sender);
            }
            case "status" -> status(sender);
            default -> sender.sendMessage("Usage: /" + label + " <reload [-f]|status>");
        }
        return true;
    }

    private void status(CommandSender sender) {
        ConversionService service = plugin.conversion();
        ConversionService.Status status = service.status();
        sender.sendMessage("[BedrockAdder] Target: " + service.deployer().describe());
        if (status == null) {
            sender.sendMessage("[BedrockAdder] No conversion in this session yet. Run /bedrockadder reload.");
        } else {
            sender.sendMessage(String.format("[BedrockAdder] Last run %s: %d items, %d blocks, %d entity blocks skipped, %d warnings%s",
                    status.time(), status.items(), status.blocks(), status.skippedBlocks(), status.warnings(),
                    status.converted() ? "" : " (unchanged)"));
            sender.sendMessage("[BedrockAdder] Bundle hash: " + status.hash().substring(0, 12));
        }
        if (service.restartRequired()) {
            sender.sendMessage("[BedrockAdder] Geyser is running an older bundle; restart it to apply the new one.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return List.of("-f");
        }
        return List.of();
    }
}

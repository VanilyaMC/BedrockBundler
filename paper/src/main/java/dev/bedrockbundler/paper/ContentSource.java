package dev.bedrockbundler.paper;

import dev.bedrockbundler.common.convert.ConversionContext;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.List;
import java.util.Map;

/**
 * A custom content plugin BedrockBundler converts from (ItemsAdder, Nexo). Implementations live next to the plugin's
 * API and are only loaded when that plugin is installed, so BedrockBundler runs with either one.
 */
public interface ContentSource {

    /** Plugin name, as in its plugin.yml. */
    String pluginName();

    /** Registers the listeners that start a conversion when the plugin builds its resource pack. */
    void enable(BedrockBundlerPlugin plugin);

    /** Registry snapshot of the custom items. Runs on the global (main) thread. */
    List<IaItem> items();

    /**
     * Registry snapshot of the custom blocks that replace a vanilla block state. Runs on the global (main) thread.
     *
     * @param skipped receives ids of blocks that are not state-based (entity/furniture blocks)
     */
    List<IaBlock> blocks(List<String> skipped);

    /** The generated Java resource pack as read from disk, or null when the plugin has not built one yet. */
    Map<String, byte[]> packFromDisk() throws java.io.IOException;

    /** Whether this item display is one of the plugin's furniture pieces. Called on the display's own thread. */
    boolean isFurniture(ItemDisplay display);

    /** Whether a stack is one of the plugin's custom items (used for thrown tridents). */
    boolean isCustomItem(ItemStack item);

    /**
     * The item an item display shows. Plugins that send the item with packets only (Nexo furniture) leave the
     * entity's own item empty. Called on the display's own thread.
     */
    default ItemStack displayItem(ItemDisplay display) {
        return display.getItemStack();
    }

    /**
     * The transformation an item display is drawn with. Plugins that animate furniture with packets (Nexo doors)
     * leave the entity's own transformation at the closed state. Called on the display's own thread.
     */
    default Transformation transformation(ItemDisplay display) {
        return display.getTransformation();
    }

    /** Adjusts conversion options to how this plugin renders its content. Called on the global thread per run. */
    default ConversionContext.Options adjust(ConversionContext.Options options) {
        return options;
    }
}

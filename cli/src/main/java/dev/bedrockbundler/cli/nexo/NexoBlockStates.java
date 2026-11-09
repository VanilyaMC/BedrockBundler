package dev.bedrockbundler.cli.nexo;

import java.util.List;

/**
 * The vanilla block state Nexo uses for a {@code custom_variation}, as encoded by Nexo 1.2x-1.30
 * ({@code IBlockUtils.encodeToBlockData}, {@code StringMechanicHelpers.modernBlockData},
 * {@code ChorusMechanicHelpers.blockData}). A running server should read the state from
 * {@code NexoBlocks.blockData(id)} instead; this is for converting a Nexo folder offline.
 * Properties are written sorted by name, the order Geyser expects.
 */
public final class NexoBlockStates {

    /**
     * Nexo's instrument order for note blocks ({@code IBlockUtils.BASE_INSTRUMENTS}, then the 26.x copper
     * trumpets), as vanilla state values.
     */
    static final List<String> INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "zombie", "skeleton", "creeper",
            "dragon", "wither_skeleton", "piglin", "custom_head",
            "trumpet", "trumpet_exposed", "trumpet_oxidized", "trumpet_weathered");

    private NexoBlockStates() {
    }

    /** @return the Java block state, or null for an unknown type or out-of-range variation */
    public static String state(String type, int variation) {
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "NOTEBLOCK" -> noteBlock(variation);
            case "STRINGBLOCK" -> stringBlock(variation);
            case "CHORUSBLOCK" -> chorusBlock(variation);
            default -> null;
        };
    }

    static String noteBlock(int variation) {
        if (variation < 0 || variation / 50 >= INSTRUMENTS.size()) {
            return null;
        }
        return "minecraft:note_block[instrument=" + INSTRUMENTS.get(variation / 50)
                + ",note=" + (variation % 25)
                + ",powered=" + (variation % 50 >= 25) + "]";
    }

    static String stringBlock(int variation) {
        if (variation < 0 || variation > 127) {
            return null;
        }
        return "minecraft:tripwire[attached=" + bit(variation, 16)
                + ",disarmed=" + bit(variation, 32)
                + ",east=" + bit(variation, 4)
                + ",north=" + bit(variation, 1)
                + ",powered=" + bit(variation, 64)
                + ",south=" + bit(variation, 2)
                + ",west=" + bit(variation, 8) + "]";
    }

    static String chorusBlock(int variation) {
        if (variation < 0 || variation > 63) {
            return null;
        }
        return "minecraft:chorus_plant[down=" + bit(variation, 32)
                + ",east=" + bit(variation, 4)
                + ",north=" + bit(variation, 1)
                + ",south=" + bit(variation, 2)
                + ",up=" + bit(variation, 16)
                + ",west=" + bit(variation, 8) + "]";
    }

    private static boolean bit(int value, int mask) {
        return (value & mask) != 0;
    }
}

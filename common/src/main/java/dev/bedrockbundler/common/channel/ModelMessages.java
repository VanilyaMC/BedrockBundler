package dev.bedrockbundler.common.channel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The messages the Paper plugin sends to the Geyser extension, as plugin messages on {@link #CHANNEL}.
 *
 * <p>They carry what Geyser cannot learn from the Java entity itself: which Bedrock model a mirror entity stands
 * for, and the animation and bone visibility bits that change while the model runs. Both ends are in this
 * repository, so the format is private and versioned by {@link #VERSION}.
 *
 * <pre>
 * ASSIGN  type=1  entityId:int  model:utf  bundleHash:utf  scale:float
 * STATE   type=2  entityId:int  scale:float  count:short  { kind:byte  index:byte  value:int } * count
 * REMOVE  type=3  entityId:int
 * </pre>
 *
 * Property kinds: {@link #BONE}, {@link #ANIMATION}, {@link #TINT_MASK}, {@link #TINT_COLOR}.
 */
public final class ModelMessages {

    public static final String CHANNEL = "bedrockbundler:main";
    public static final int VERSION = 1;

    public static final byte BONE = 'b';
    public static final byte ANIMATION = 'a';
    public static final byte TINT_MASK = 't';
    public static final byte TINT_COLOR = 'c';

    private static final int TYPE_ASSIGN = 1;
    private static final int TYPE_STATE = 2;
    private static final int TYPE_REMOVE = 3;

    private ModelMessages() {
    }

    /** One property value: kind plus index name a registered entity property ({@code bone0}, {@code anim1}, ...). */
    public record Value(byte kind, int index, int value) {
    }

    public sealed interface Message permits Assign, State, Remove {
        int entityId();
    }

    /** Sent before the entity is shown: from now on {@code entityId} is drawn with {@code model}. */
    public record Assign(int entityId, String model, String bundleHash, float scale) implements Message {
    }

    public record State(int entityId, float scale, java.util.List<Value> values) implements Message {
    }

    public record Remove(int entityId) implements Message {
    }

    public static byte[] assign(int entityId, String model, String bundleHash, float scale) {
        return write(out -> {
            out.writeByte(TYPE_ASSIGN);
            out.writeInt(entityId);
            out.writeUTF(model);
            out.writeUTF(bundleHash);
            out.writeFloat(scale);
        });
    }

    public static byte[] state(int entityId, float scale, java.util.List<Value> values) {
        return write(out -> {
            out.writeByte(TYPE_STATE);
            out.writeInt(entityId);
            out.writeFloat(scale);
            out.writeShort(values.size());
            for (Value value : values) {
                out.writeByte(value.kind());
                out.writeByte(value.index());
                out.writeInt(value.value());
            }
        });
    }

    public static byte[] remove(int entityId) {
        return write(out -> {
            out.writeByte(TYPE_REMOVE);
            out.writeInt(entityId);
        });
    }

    /** Decodes a message, or returns null for data this version does not understand. */
    public static Message decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readUnsignedByte();
            switch (type) {
                case TYPE_ASSIGN -> {
                    return new Assign(in.readInt(), in.readUTF(), in.readUTF(), in.readFloat());
                }
                case TYPE_STATE -> {
                    int entityId = in.readInt();
                    float scale = in.readFloat();
                    int count = in.readUnsignedShort();
                    java.util.List<Value> values = new java.util.ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        values.add(new Value(in.readByte(), in.readUnsignedByte(), in.readInt()));
                    }
                    return new State(entityId, scale, values);
                }
                case TYPE_REMOVE -> {
                    return new Remove(in.readInt());
                }
                default -> {
                    return null;
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Name of the entity property a value belongs to, e.g. {@code bedrockbundler:bone0}. */
    public static String propertyName(String namespace, byte kind, int index) {
        String base = switch (kind) {
            case BONE -> "bone";
            case ANIMATION -> "anim";
            case TINT_MASK -> "tintmask";
            case TINT_COLOR -> "tintcolor";
            default -> null;
        };
        if (base == null) {
            return null;
        }
        return namespace + ":" + base + (kind == TINT_COLOR ? "" : Integer.toString(index));
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}

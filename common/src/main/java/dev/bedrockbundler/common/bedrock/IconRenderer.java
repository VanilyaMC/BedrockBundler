package dev.bedrockbundler.common.bedrock;

import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.java.JavaModel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Renders a 3D Java model into an inventory icon, the way the Java client draws it in the GUI:
 * the model's {@code gui} display transform, an orthographic camera and simple per-face shading.
 */
public final class IconRenderer {

    private static final JsonObject DEFAULT_GUI = Json.GSON.fromJson(
            "{\"rotation\": [30, 225, 0], \"translation\": [0, 0, 0], \"scale\": [0.625, 0.625, 0.625]}", JsonObject.class);

    private IconRenderer() {
    }

    private record Quad(double[] topLeft, double[] topRight, double[] bottomLeft, double depth,
                        JavaModel.Face face, float shade) {
    }

    public static BufferedImage render(JavaModel model, Function<String, BufferedImage> textures, int size) {
        JsonObject gui = model.display().getOrDefault("gui", DEFAULT_GUI);
        float[] rotation = Json.floats(Json.array(gui, "rotation"), 3, 0);
        float[] translation = Json.floats(Json.array(gui, "translation"), 3, 0);
        float[] scale = Json.floats(Json.array(gui, "scale"), 3, 1);

        List<Quad> quads = new ArrayList<>();
        for (JavaModel.Element element : model.elements()) {
            for (var entry : element.faces().entrySet()) {
                double[][] corners = faceCorners(entry.getKey(), element.from(), element.to());
                for (int i = 0; i < 3; i++) {
                    corners[i] = transform(corners[i], element.rotation(), rotation, translation, scale);
                }
                // Back-face culling: skip faces pointing away from the camera.
                double ax = corners[1][0] - corners[0][0];
                double ay = corners[1][1] - corners[0][1];
                double bx = corners[2][0] - corners[0][0];
                double by = corners[2][1] - corners[0][1];
                if (ax * by - ay * bx > 0) {
                    continue;
                }
                double depth = (corners[1][2] + corners[2][2]) / 2;
                quads.add(new Quad(corners[0], corners[1], corners[2], depth, entry.getValue(), shade(entry.getKey())));
            }
        }
        quads.sort(Comparator.comparingDouble(Quad::depth));

        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (Quad quad : quads) {
            BufferedImage texture = quad.face().texture() == null ? null : textures.apply(quad.face().texture());
            if (texture != null) {
                draw(icon, quad, texture);
            }
        }
        return icon;
    }

    /** Top-left, top-right and bottom-left corners of a face, matching how Java lays out its UVs. */
    private static double[][] faceCorners(String face, float[] f, float[] t) {
        return switch (face) {
            case "north" -> new double[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {t[0], f[1], f[2]}};
            case "south" -> new double[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {f[0], f[1], t[2]}};
            case "west" -> new double[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], f[2]}};
            case "east" -> new double[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], t[2]}};
            case "up" -> new double[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {f[0], t[1], t[2]}};
            default -> new double[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], f[2]}};
        };
    }

    private static float shade(String face) {
        return switch (face) {
            case "up" -> 1.0f;
            case "north", "south" -> 0.8f;
            case "east", "west" -> 0.6f;
            default -> 0.5f;
        };
    }

    /** Element rotation, then display transform (scale, rotate X-Y-Z, translate), centred on the model. */
    private static double[] transform(double[] point, JavaModel.Rotation elementRotation,
                                      float[] rotation, float[] translation, float[] scale) {
        double[] p = point.clone();
        if (elementRotation != null) {
            float[] o = elementRotation.origin();
            double[] local = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            local = rotate(local, elementRotation.axis(), elementRotation.angle());
            p = new double[]{local[0] + o[0], local[1] + o[1], local[2] + o[2]};
        }
        p = new double[]{(p[0] - 8) * scale[0], (p[1] - 8) * scale[1], (p[2] - 8) * scale[2]};
        // Java applies rotationXYZ, i.e. Z first, then Y, then X.
        p = rotate(p, "z", rotation[2]);
        p = rotate(p, "y", rotation[1]);
        p = rotate(p, "x", rotation[0]);
        return new double[]{p[0] + translation[0], p[1] + translation[1], p[2] + translation[2]};
    }

    private static double[] rotate(double[] p, String axis, double degrees) {
        if (degrees == 0) {
            return p;
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return switch (axis) {
            case "x" -> new double[]{p[0], p[1] * cos - p[2] * sin, p[1] * sin + p[2] * cos};
            case "y" -> new double[]{p[0] * cos + p[2] * sin, p[1], -p[0] * sin + p[2] * cos};
            default -> new double[]{p[0] * cos - p[1] * sin, p[0] * sin + p[1] * cos, p[2]};
        };
    }

    private static void draw(BufferedImage icon, Quad quad, BufferedImage texture) {
        int size = icon.getWidth();
        double[][] screen = new double[3][];
        double[][] corners = {quad.topLeft(), quad.topRight(), quad.bottomLeft()};
        for (int i = 0; i < 3; i++) {
            // 16 model units fill the slot; screen y grows downwards.
            screen[i] = new double[]{(corners[i][0] / 16 + 0.5) * size, (0.5 - corners[i][1] / 16) * size};
        }
        double ex = screen[1][0] - screen[0][0];
        double ey = screen[1][1] - screen[0][1];
        double fx = screen[2][0] - screen[0][0];
        double fy = screen[2][1] - screen[0][1];
        double det = ex * fy - ey * fx;
        if (Math.abs(det) < 1e-9) {
            return;
        }
        // Bounding box of all four corners (the fourth is bottom-left + top edge).
        double minX = Math.min(Math.min(screen[0][0], screen[1][0]), Math.min(screen[2][0], screen[2][0] + ex));
        double maxX = Math.max(Math.max(screen[0][0], screen[1][0]), Math.max(screen[2][0], screen[2][0] + ex));
        double minY = Math.min(Math.min(screen[0][1], screen[1][1]), Math.min(screen[2][1], screen[2][1] + ey));
        double maxY = Math.max(Math.max(screen[0][1], screen[1][1]), Math.max(screen[2][1], screen[2][1] + ey));

        float[] uv = quad.face().uv();
        for (int y = Math.max(0, (int) Math.floor(minY)); y < Math.min(size, (int) Math.ceil(maxY)); y++) {
            for (int x = Math.max(0, (int) Math.floor(minX)); x < Math.min(size, (int) Math.ceil(maxX)); x++) {
                double px = x + 0.5 - screen[0][0];
                double py = y + 0.5 - screen[0][1];
                double s = (px * fy - py * fx) / det;
                double t = (ex * py - ey * px) / det;
                if (s < 0 || s > 1 || t < 0 || t > 1) {
                    continue;
                }
                double[] st = rotateUv(s, t, quad.face().rotation());
                double u = uv[0] + st[0] * (uv[2] - uv[0]);
                double v = uv[1] + st[1] * (uv[3] - uv[1]);
                int tx = clamp((int) Math.floor(u / 16 * texture.getWidth()), texture.getWidth());
                int ty = clamp((int) Math.floor(v / 16 * texture.getHeight()), texture.getHeight());
                int argb = texture.getRGB(tx, ty);
                if ((argb >>> 24) < 16) {
                    continue;
                }
                icon.setRGB(x, y, shadePixel(argb, quad.shade()));
            }
        }
    }

    private static double[] rotateUv(double s, double t, int rotation) {
        return switch (rotation) {
            case 90 -> new double[]{t, 1 - s};
            case 180 -> new double[]{1 - s, 1 - t};
            case 270 -> new double[]{1 - t, s};
            default -> new double[]{s, t};
        };
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static int shadePixel(int argb, float shade) {
        int r = (int) (((argb >> 16) & 0xFF) * shade);
        int g = (int) (((argb >> 8) & 0xFF) * shade);
        int b = (int) ((argb & 0xFF) * shade);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

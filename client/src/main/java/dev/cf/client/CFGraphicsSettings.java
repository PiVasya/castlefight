package dev.cf.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small, local-only graphic preferences. This is NOT a disk cache of map data:
 * the server remains the sole authority for routes, geometry and combat.
 */
public final class CFGraphicsSettings {
    public record Options(int routeWidthPreset, int opacityPercent, int projectileQuality,
                          boolean trails, int drawDistance) {
        public Options {
            if (routeWidthPreset < 0 || routeWidthPreset > 2 ||
                    (opacityPercent != 50 && opacityPercent != 75 && opacityPercent != 100) ||
                    projectileQuality < 0 || projectileQuality > 2 ||
                    (drawDistance != 64 && drawDistance != 96 && drawDistance != 128))
                throw new IllegalArgumentException("Некорректные настройки графики КФ");
        }
        public float routeWidth() { return switch (routeWidthPreset) {
            case 0 -> .25f; case 1 -> .34f; default -> .46f;
        }; }
        public String qualityName() { return switch (projectileQuality) {
            case 0 -> "ВЫКЛ"; case 1 -> "ПРОСТО"; default -> "ПОДРОБНО";
        }; }
        public Options nextWidth() { return new Options((routeWidthPreset+1)%3,opacityPercent,
                projectileQuality,trails,drawDistance); }
        public Options nextOpacity() { return new Options(routeWidthPreset,
                opacityPercent==50?75:opacityPercent==75?100:50,projectileQuality,trails,drawDistance); }
        public Options nextQuality() { return new Options(routeWidthPreset,opacityPercent,
                (projectileQuality+1)%3,trails,drawDistance); }
        public Options toggleTrails() { return new Options(routeWidthPreset,opacityPercent,
                projectileQuality,!trails,drawDistance); }
        public Options nextDistance() { return new Options(routeWidthPreset,opacityPercent,
                projectileQuality,trails,drawDistance==64?96:drawDistance==96?128:64); }
    }
    public static final Options DEFAULT = new Options(1,100,2,true,96);
    private static volatile Options current = DEFAULT;
    private static Path configFile;

    private CFGraphicsSettings() { }
    public static Options current() { return current; }
    public static void initialize(Path path) {
        configFile = path;
        current = read(path);
    }
    public static Options read(Path path) {
        if (!Files.isRegularFile(path)) return DEFAULT;
        Properties p = new Properties();
        try (Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            p.load(reader);
            return new Options(
                    number(p,"route_width",DEFAULT.routeWidthPreset()),
                    number(p,"overlay_opacity",DEFAULT.opacityPercent()),
                    number(p,"projectile_quality",DEFAULT.projectileQuality()),
                    Boolean.parseBoolean(p.getProperty("projectile_trails",Boolean.toString(DEFAULT.trails()))),
                    number(p,"draw_distance",DEFAULT.drawDistance()));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[КФ КЛИЕНТ] Настройки графики не прочитаны: " + e.getMessage());
            return DEFAULT;
        }
    }
    private static int number(Properties p,String key,int fallback) {
        return Integer.parseInt(p.getProperty(key,Integer.toString(fallback)));
    }
    public static synchronized void set(Options value) {
        if (value == null) throw new IllegalArgumentException("Null graphics settings");
        current = value;
        if (configFile == null) return;
        try { write(configFile,value); }
        catch (IOException e) { System.err.println("[КФ КЛИЕНТ] Не удалось сохранить графику: "+e.getMessage()); }
    }
    public static void write(Path path, Options value) throws IOException {
        Path parent=path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties p=new Properties();
        p.setProperty("route_width",Integer.toString(value.routeWidthPreset()));
        p.setProperty("overlay_opacity",Integer.toString(value.opacityPercent()));
        p.setProperty("projectile_quality",Integer.toString(value.projectileQuality()));
        p.setProperty("projectile_trails",Boolean.toString(value.trails()));
        p.setProperty("draw_distance",Integer.toString(value.drawDistance()));
        try (Writer writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
            p.store(writer,"Castle Fight Fabric client graphics. No map geometry is stored here.");
        }
    }
}

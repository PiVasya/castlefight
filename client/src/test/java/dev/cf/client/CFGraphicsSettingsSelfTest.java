package dev.cf.client;

import java.nio.file.Files;

public final class CFGraphicsSettingsSelfTest {
    public static void main(String[] args) throws Exception {
        var temp=Files.createTempFile("cf-graphics", ".properties");
        try {
            var options=CFGraphicsSettings.DEFAULT.nextWidth().nextOpacity()
                    .nextQuality().toggleTrails().nextDistance();
            CFGraphicsSettings.write(temp,options);
            if (!CFGraphicsSettings.read(temp).equals(options))
                throw new AssertionError("Client preferences roundtrip");
            if (!CFGraphicsSettings.read(temp).nextWidth().equals(options.nextWidth()))
                throw new AssertionError("Visual width option changed unexpectedly");
            if (CFGraphicsSettings.DEFAULT.routeWidth() >= .58f)
                throw new AssertionError("Route should be thinner than v10.5");
            Files.writeString(temp,"route_width=100\n");
            if (!CFGraphicsSettings.read(temp).equals(CFGraphicsSettings.DEFAULT))
                throw new AssertionError("Invalid client prefs should be ignored");
            System.out.println("[CF CLIENT] PASS: graphics menu settings and local preferences");
        } finally { Files.deleteIfExists(temp); }
    }
}

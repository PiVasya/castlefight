package dev.cf;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Единственный допустимый контракт карт Castle Fight: schema=3.0.
 * НЕ добавлять поддержку schema=2.2/2.3, дорожных ковров и "мягкий" переход
 * на старые конфигурации. Карты должны быть явно подготовлены для CF Map 3.
 * Правило автора: ковры = ТОЛЬКО строительные зоны; путь = ТОЛЬКО маяки
 * [цветное стекло] НА [магнетите] НА [поверхности дороги].
 */
final class CFMapFormat {
    static final String SCHEMA="3.0";
    static final String FILE="cf/map.properties";

    static Properties read(Path root) {
        Path file=root.resolve(FILE);
        try(InputStream in=Files.newInputStream(file)) {
            Properties p=new Properties();p.load(in);
            String schema=p.getProperty("schema","").trim();
            if(!SCHEMA.equals(schema))
                throw new IllegalArgumentException("Карта не поддерживается: нужен НОВЫЙ формат schema="+SCHEMA+
                        " в "+FILE+", получено: "+(schema.isEmpty()?"нет schema":schema)+
                        ". Старые схемы и ковры-маркеры путей удалены из ядра.");
            return p;
        }catch(NoSuchFileException missing){
            throw new IllegalArgumentException("Карта не поддерживается: отсутствует "+FILE+
                    " (нужен schema="+SCHEMA+"). Старые карты не загружаются.",missing);
        }catch(IOException e){
            throw new IllegalStateException("Не удалось прочитать настройки карты: "+file,e);
        }
    }
    private CFMapFormat() { }
}
